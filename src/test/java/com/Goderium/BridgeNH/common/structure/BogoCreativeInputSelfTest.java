package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;

/** Public-path contract and side-effect guards; actual mod/client integration requires the live check. */
public final class BogoCreativeInputSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        ApiFixture api = new ApiFixture(); InputFixture input = new InputFixture();
        GuiContainer screen = new GuiContainerCreative();
        Slot slot = new Slot(new InventoryPlayer(null), 0, 0, 0) {
            @Override public boolean canTakeStack(EntityPlayer player) { return api.allowed; }
        };
        JsonObject prepared = BogoCreativeInput.prepare(screen, slot, null, api, input);
        check(api.refreshCalls == 1 && api.handleCalls == 1 && api.actualScreen == screen && prepared.get("prepared").getAsBoolean()
                && prepared.get("canTakeStackAfterInput").getAsBoolean(), "normal public preparation receives the actual GUI once and enables the slot through its own path");
        check(!prepared.get("guardWrites").getAsBoolean() && !prepared.get("hoverWrites").getAsBoolean()
                && !prepared.get("inventoryWrites").getAsBoolean(), "preparation never performs direct guard, hover or inventory writes");

        api.reset();
        Slot insertOnly = new Slot(new InventoryPlayer(null), 0, 0, 0) {
            @Override public boolean canTakeStack(EntityPlayer player) { return false; }
        };
        JsonObject ordinary = BogoCreativeInput.prepareSlotClick(screen, insertOnly, null, api, input);
        check(api.handleCalls == 1 && ordinary.get("prepared").getAsBoolean()
                && !ordinary.get("takeabilityRequired").getAsBoolean() && !ordinary.get("canTakeStackAfterInput").getAsBoolean(),
                "ordinary insertion retains native non-takeable slot semantics after one public preparation");
        api.reset();
        JsonObject outside = BogoCreativeInput.prepareSlotClick(screen, null, null, api, input);
        check(api.handleCalls == 1 && !outside.get("targetSlotPresent").getAsBoolean() && !outside.has("canTakeStackAfterInput"),
                "outside native clicks prepare the actual GUI without inventing or dereferencing a backing slot");
        api.reset(); expect("input_busy", () -> BogoCreativeInput.prepare(screen, insertOnly, null, api, input));
        check(api.handleCalls == 1, "creative pickup still requires a takeable slot and cannot inherit ordinary insertion permission");

        api.reset(); input.busy = true; expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.refreshCalls == 0 && api.handleCalls == 0, "physical input rejection occurs before optional key refresh or shortcut processing"); input.busy = false;
        api.keys = new KeyBinding[] {new KeyBinding("bogo fixture", 239, "fixture")};
        KeyBinding key = api.keys[0];
        KeyBinding.setKeyBindState(239, true);
        try { expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input)); }
        finally { KeyBinding.setKeyBindState(239, false); }
        check(api.handleCalls == 0, "synthetic or held public key state cannot invoke a sorting/movement handler");
        KeyBinding.onTick(239);
        expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        try { check(BogoCreativeInput.pendingPresses(key) == 1 && key.isPressed(), "pending shortcut detection only peeks and leaves the original pending key available"); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        check(api.refreshCalls == 0 && api.handleCalls == 0, "pending shortcut rejection occurs before Bogo handling");
        input.event = true; expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.handleCalls == 0, "a stale matching down event is rejected even after physical release"); input.event = false;
        api.active = true; expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.refreshCalls == 1 && api.handleCalls == 0, "refreshed active key combinations are rejected before invoking the handler");
        api.reset(); api.consumed = true;
        expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.handleCalls == 1, "consumed normal preparation explicitly rejects pickup without retrying its handler");
        api.reset(); api.cooldown = true;
        expect("input_busy", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.handleCalls == 1 && !api.allowed, "cooldown or a remaining slot refusal explicitly prevents native pickup");
        api.reset(); api.refreshFailure = true;
        expect("unsupported", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.handleCalls == 0, "an unavailable public preparation API cannot fall back to unprepared click");
        api.reset(); api.keys = new KeyBinding[17];
        expect("unsupported", () -> BogoCreativeInput.prepare(screen, slot, null, api, input));
        check(api.handleCalls == 0, "oversized optional bindings are rejected before handler execution");
        return checks;
    }
    private static final class ApiFixture implements BogoCreativeInput.Api {
        KeyBinding[] keys = new KeyBinding[0]; boolean active, consumed, cooldown, allowed, refreshFailure;
        int refreshCalls, handleCalls; GuiContainer actualScreen;
        public KeyBinding[] bindings() { return keys; }
        public void refreshKeys() throws ReflectiveOperationException { refreshCalls++; if (refreshFailure) throw new NoSuchMethodException("fixture API"); }
        public boolean shortcutActive() { return active; }
        public boolean handleInput(GuiContainer screen) { handleCalls++; actualScreen = screen; if (!cooldown && !consumed) allowed = true; return consumed; }
        void reset() { keys = new KeyBinding[0]; active = consumed = cooldown = allowed = refreshFailure = false; refreshCalls = handleCalls = 0; actualScreen = null; }
    }
    private static final class InputFixture implements BogoCreativeInput.Input {
        boolean busy, event;
        public void requireNeutral() { if (busy) throw new BridgeException("input_busy", "fixture physical input"); }
        public boolean eventPressed(KeyBinding key) { return event; }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error " + code); }
    }
}
