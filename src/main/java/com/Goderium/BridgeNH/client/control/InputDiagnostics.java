package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.client.settings.KeyBinding;

/** Read-only, bounded evidence around native key writes. This class never writes a key or movement state. */
public final class InputDiagnostics {
    static final int MAX_KEYS = 9, MAX_REGISTERED = 4096, MAX_SAME_CODE = 16;
    static final long TTL_NANOS = 10000000000L;
    interface Key {
        int code();
        boolean held();
        default boolean nativeHeld() { return held(); }
        String description();
        Object identity();
    }
    private static final class NativeKey implements Key {
        final KeyBinding binding;
        NativeKey(KeyBinding binding) { this.binding = binding; }
        public int code() { return binding.getKeyCode(); }
        public boolean held() { return binding.getIsKeyPressed(); }
        public boolean nativeHeld() { return OwnedKeyState.nativeRead(binding::getIsKeyPressed); }
        public String description() { return binding.getKeyDescription(); }
        public Object identity() { return binding; }
    }
    private static final class Entry {
        final String name;
        final Key key;
        final List<Key> matching = new ArrayList<Key>();
        int code, codeOnWrite, registeredCount, targetIndex = -1, writePressedCount;
        boolean registeredComplete, sameCodeComplete, requested, writeHeld, nativeWriteHeld;
        Boolean endHeld, nativeEndHeld;
        long writeTick, writeNanos, endTick = -1, endNanos;
        Entry(String name, Key key) { this.name = name; this.key = key; }
    }
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>();
    private String owner = "", session = "", failure = "";
    private Object world, player;
    private Key[] registered;
    private boolean active;
    private long observedNanos, capturedTick;
    private JsonObject endMovement;
    public InputDiagnostics() { this(System::nanoTime); }
    InputDiagnostics(LongSupplier clock) { this.clock = clock; }

    public void beginNative(String owner, Object world, Object player, String session, long tick, KeyBinding[] bindings) {
        int size = bindings == null ? 0 : Math.min(bindings.length, MAX_REGISTERED + 1);
        Key[] keys = new Key[size];
        for (int index = 0; index < size; index++) if (bindings[index] != null) keys[index] = new NativeKey(bindings[index]);
        begin(owner, world, player, session, tick, keys);
    }
    void begin(String owner, Object world, Object player, String session, long tick, Key[] registered) {
        invalidate(); this.owner = owner; this.world = world; this.player = player; this.session = session;
        this.registered = registered; capturedTick = tick; observedNanos = clock.getAsLong(); active = true;
    }
    public void writeNative(String owner, String name, KeyBinding binding, int expectedCode, boolean requested, long tick) {
        try { write(owner, name, new NativeKey(binding), expectedCode, requested, tick); }
        catch (RuntimeException | LinkageError error) { failure = error.getClass().getSimpleName(); }
    }
    void write(String owner, String name, Key key, int expectedCode, boolean requested, long tick) {
        if (!active || !this.owner.equals(owner)) return;
        Entry entry = entries.get(name);
        if (entry == null || entry.key.identity() != key.identity()) {
            if (entry == null && entries.size() >= MAX_KEYS) { failure = "owned_key_limit"; return; }
            entry = new Entry(name, key); entries.put(name, entry); bind(entry, expectedCode);
        } else if (entry.code != expectedCode) bind(entry, expectedCode);
        entry.code = expectedCode; entry.codeOnWrite = key.code(); entry.requested = requested; entry.writeHeld = key.held(); entry.nativeWriteHeld = key.nativeHeld();
        entry.writeTick = tick; entry.writeNanos = observedNanos = clock.getAsLong(); entry.writePressedCount = 0;
        for (Key matching : entry.matching) if (matching.held()) entry.writePressedCount++;
    }
    private void bind(Entry entry, int expectedCode) {
        entry.matching.clear(); entry.targetIndex = -1; entry.registeredCount = 0;
        int length = registered == null ? 0 : registered.length;
        entry.registeredComplete = registered != null && length <= MAX_REGISTERED;
        for (int index = 0; index < Math.min(length, MAX_REGISTERED); index++) {
            Key found = registered[index]; if (found == null) continue;
            if (found.identity() == entry.key.identity()) entry.targetIndex = index;
            if (found.code() == expectedCode) {
                entry.registeredCount++;
                if (entry.matching.size() < MAX_SAME_CODE) entry.matching.add(found);
            }
        }
        entry.sameCodeComplete = entry.registeredComplete && entry.registeredCount <= MAX_SAME_CODE;
    }
    public void tickEnd(long tick, JsonObject actualMovement) {
        if (!active || entries.isEmpty()) return;
        try {
            long now = clock.getAsLong();
            for (Entry entry : entries.values()) { entry.endHeld = entry.key.held(); entry.nativeEndHeld = entry.key.nativeHeld(); entry.endTick = tick; entry.endNanos = now; }
            endMovement = actualMovement; observedNanos = now;
        } catch (RuntimeException | LinkageError error) { failure = error.getClass().getSimpleName(); }
    }
    public boolean sampling() { return active && !entries.isEmpty(); }
    public void release(String owner) { if (this.owner.equals(owner)) { active = false; observedNanos = clock.getAsLong(); } }
    public void invalidate() { entries.clear(); owner = session = failure = ""; world = player = null; registered = null; active = false; endMovement = null; }
    public JsonObject inspect(Object world, Object player, String session, long tick, JsonObject actualMovement) {
        long now = clock.getAsLong(); JsonObject result = new JsonObject(); JsonArray rows = new JsonArray(); result.add("keys", rows);
        if (entries.isEmpty() || this.world != world || this.player != player || !this.session.equals(session) || now - observedNanos > TTL_NANOS) {
            result.addProperty("available", false); result.addProperty("active", false); return result;
        }
        result.addProperty("available", true); result.addProperty("active", active); result.addProperty("owner", owner); result.addProperty("clientTick", tick);
        result.addProperty("ageMillis", Math.max(0, now - observedNanos) / 1000000.0); result.addProperty("registeredCaptureClientTick", capturedTick);
        result.addProperty("retentionMillis", TTL_NANOS / 1000000L); result.addProperty("maxOwnedKeys", MAX_KEYS);
        result.addProperty("status", failure.isEmpty() ? "observed" : "partial"); if (!failure.isEmpty()) result.addProperty("failure", failure);
        for (Entry entry : entries.values()) {
            JsonObject row = new JsonObject(); row.addProperty("name", entry.name); row.addProperty("bindingDescription", JsonData.bounded(entry.key.description(), 128));
            row.addProperty("bindingIdentity", System.identityHashCode(entry.key.identity())); row.addProperty("expectedKeyCode", entry.code); row.addProperty("bindingKeyCodeAtWrite", entry.codeOnWrite);
            row.addProperty("bindingClass", entry.key.identity().getClass().getName());
            row.addProperty("requestedPressed", entry.requested); row.addProperty("heldImmediatelyAfterWrite", entry.writeHeld);
            row.addProperty("nativeHeldImmediatelyAfterWrite", entry.nativeWriteHeld);
            row.addProperty("nativeWriteAccepted", entry.code == entry.codeOnWrite && entry.nativeWriteHeld == entry.requested);
            row.addProperty("writeAccepted", entry.code == entry.codeOnWrite && entry.writeHeld == entry.requested); row.addProperty("writeClientTick", entry.writeTick);
            row.addProperty("writeAgeMillis", Math.max(0, now - entry.writeNanos) / 1000000.0);
            row.add("registeredTargetSameInstance", entry.targetIndex >= 0 ? new com.google.gson.JsonPrimitive(true) : entry.registeredComplete ? new com.google.gson.JsonPrimitive(false) : JsonNull.INSTANCE);
            row.addProperty("registeredTargetIndex", entry.targetIndex); row.addProperty("sameCodeRegisteredCount", entry.registeredCount);
            row.add("firstSameCodeBindingIdentity", entry.matching.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(System.identityHashCode(entry.matching.get(0).identity())));
            row.add("firstSameCodeIsTarget", entry.matching.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.matching.get(0).identity() == entry.key.identity()));
            row.addProperty("registeredCountExact", entry.registeredComplete); row.addProperty("sameCodeHeldImmediatelyAfterWrite", entry.writePressedCount);
            row.addProperty("sameCodeHeldCountExact", entry.sameCodeComplete);
            row.add("heldAtClientTickEnd", entry.endHeld == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.endHeld)); row.addProperty("endClientTick", entry.endTick);
            row.add("nativeHeldAtClientTickEnd", entry.nativeEndHeld == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(entry.nativeEndHeld));
            row.add("endAgeMillis", entry.endHeld == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(Math.max(0, now - entry.endNanos) / 1000000.0));
            row.addProperty("lostBetweenWriteAndEnd", entry.writeTick == entry.endTick && entry.writeHeld && Boolean.FALSE.equals(entry.endHeld));
            row.addProperty("heldNow", entry.key.held()); row.addProperty("nativeHeldNow", entry.key.nativeHeld());
            JsonObject logical = new JsonObject();
            for (Map.Entry<String, Object> field : OwnedKeyState.trace(entry.key.identity()).entrySet()) {
                Object value = field.getValue();
                if (value instanceof Boolean) logical.addProperty(field.getKey(), (Boolean) value);
                else if (value instanceof Number) logical.addProperty(field.getKey(), (Number) value);
                else logical.addProperty(field.getKey(), String.valueOf(value));
            }
            row.add("logicalReadTrace", logical); rows.add(row);
        }
        result.add("actualMovementInput", actualMovement); result.add("movementInputAtClientTickEnd", endMovement == null ? JsonNull.INSTANCE : endMovement);
        result.addProperty("scope", "Read-only client key-write/END evidence. held/writeAccepted use the effective binding getter; nativeHeld/nativeWriteAccepted suspend owned getter and keyboard-read layers but retain other mods' getter gates, and do not read private pressed fields. logicalReadTrace is bounded exact-instance hook evidence; global hook counts do not prove the target was read. Requested input is not proof of movement, flight or server arrival. Registered identity is bound at ownership start, at most4096 bindings and16 same-code states per owned key; no input events, buffer writes, ability writes or replay.");
        return result;
    }
}
