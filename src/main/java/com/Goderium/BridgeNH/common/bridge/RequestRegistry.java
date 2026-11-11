package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacement;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Bounded request IDs and cancellation tombstones; no Minecraft state. */
public class RequestRegistry {
    private final Map<String, Entry> entries = new LinkedHashMap<String, Entry>(16, 0.75f, true);
    private final int capacity;
    private final long ttlNanos;
    private final LongSupplier nanos;

    public RequestRegistry(int capacity, long ttlMillis) {
        this(capacity, ttlMillis, System::nanoTime);
    }

    RequestRegistry(int capacity, long ttlMillis, LongSupplier nanos) {
        this.capacity = capacity;
        ttlNanos = TimeUnit.MILLISECONDS.toNanos(ttlMillis);
        this.nanos = nanos;
    }

    public synchronized GameQueue.Control register(String id) { return register(id, ""); }

    public synchronized GameQueue.Control register(String id, String operation) {
        prune();
        Entry existing = entries.get(id);
        if (existing != null) {
            if (existing.control.isCancelled()) throw new BridgeException("cancelled", "This requestId was cancelled before submission.");
            throw new BridgeException("duplicate_request", "This requestId has already been submitted.");
        }
        makeRoom();
        if (entries.size() >= capacity) throw new BridgeException("busy", "The bounded request ID registry is full.");
        GameQueue.Control control = OwnedBlockReplacement.OPERATION.equals(operation) || SurvivalConstructionAdapter.OPERATION.equals(operation)
                || "player.place_block".equals(operation) ? new OwnedBlockReplacement.Control() : new GameQueue.Control();
        entries.put(id, new Entry(control, readOnly(operation)));
        return control;
    }

    /** Only a conclusive dispatch result ends the RPC. Timeout/interruption remains protected. */
    public synchronized void complete(String id, GameQueue.Control control) {
        Entry existing = entries.get(id);
        if (existing != null && existing.control == control) existing.complete(nanos.getAsLong());
    }

    public synchronized String cancel(String id) {
        prune();
        Entry existing = entries.get(id);
        if (existing == null) {
            makeRoom();
            if (entries.size() >= capacity) throw new BridgeException("busy", "The bounded cancellation registry is full.");
            GameQueue.Control control = new GameQueue.Control();
            control.cancelBeforeExecution();
            Entry tombstone = new Entry(control, false);
            tombstone.cancelled(nanos.getAsLong());
            entries.put(id, tombstone);
            return "not_found";
        }
        if (!existing.control.cancelBeforeExecution()) return "already_running";
        existing.cancelled(nanos.getAsLong());
        return "cancelled";
    }

    private void prune() {
        long now = nanos.getAsLong();
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.control.isCancelled()) entry.cancelled(now);
            if (entry.completed && now - entry.completedAt >= ttlNanos) iterator.remove();
        }
    }

    /** Completed reads may leave the LRU early; writes and cancellation tombstones keep the full TTL. */
    private void makeRoom() {
        if (entries.size() < capacity) return;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.readOnly && entry.completed && !entry.control.isCancelled()) {
                iterator.remove();
                return;
            }
        }
    }

    /** Exact allowlist: unfamiliar operations retain mutation-grade deduplication. */
    @com.google.common.annotations.VisibleForTesting
    public static boolean readOnly(String operation) {
        if (operation == null) return false;
        switch (operation) {
            case "bridge.health": case "client.snapshot": case "client.window": case "client.screenshot": case "client.diagnostics":
            case "client.mods": case "client.messages": case "client.worlds": case "input.bindings":
            case "gui.inspect": case "gui.tooltip": case "gui.widgets": case "inventory.inspect":
            case "registry.items": case "registry.item": case "registry.variants":
            case "world.inspect": case "world.inspect_many": case "world.scan": case "world.tiles":
            case "server.observe": case "structure.goal": case "world.structure": case "world.entities": case "world.spatial": case "machine.inspect": case "machine.capture":
            case "recipes.query": case "nei.inspect": case "nei.view": case "waila.inspect": case "ae.inspect": case "ae.material_source":
            case "player.plan_approach": case "state.diff": case "qa.catalog": case "qa.status": case "qa.results":
                return true;
            default: return false;
        }
    }

    private static class Entry {
        final GameQueue.Control control;
        final boolean readOnly;
        boolean completed;
        boolean cancellationRecorded;
        long completedAt;

        Entry(GameQueue.Control control, boolean readOnly) {
            this.control = control;
            this.readOnly = readOnly;
        }

        void complete(long now) {
            if (!completed) { completed = true; completedAt = now; }
        }

        void cancelled(long now) {
            if (!cancellationRecorded) {
                cancellationRecorded = true;
                completed = true;
                completedAt = now;
            }
        }
    }
}
