package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.common.inventory.SlotAcceptance;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.container.slot.AppEngSlot;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Public API fixtures for pending buffers, full pattern identity and enabled native slots. */
public final class AeObservationSelfTest {
    static int checks;
    public static class Duality {
        public final List<Object> waiting = new ArrayList<Object>();
        public final InventoryBasic patterns = new InventoryBasic("patterns", true, 2);
        public List<Object> getWaitingToSend() { return waiting; }
        public IInventory getInventoryByName(String name) { return patterns; }
    }
    public static int run() {
        checks = 0;
        ItemStack stack = new ItemStack(new Item(), 1); NBTTagCompound tag = new NBTTagCompound(); tag.setBoolean("accepted", true); tag.setString("recipe", "different-full-pattern"); stack.setTagCompound(tag);
        AppEngSlot slot = new AppEngSlot(); JsonObject data = new JsonObject(); SlotAcceptance.describe(data, slot, stack);
        check(data.getAsJsonObject("slotAcceptance").get("enabled").getAsBoolean() && data.getAsJsonObject("slotAcceptance").get("acceptsCandidate").getAsBoolean(), "Native enablement and exact candidate acceptance are distinct positive evidence.");
        slot.enabled = false; expect("invalid_state", () -> SlotAcceptance.preflight(slot, stack, 0));
        check(stack.stackSize == 1 && stack.getTagCompound().getBoolean("accepted"), "Disabled preflight retains cursor identity and count.");
        slot.enabled = true; ItemStack rejected = stack.copy(); rejected.getTagCompound().setBoolean("accepted", false);
        expect("invalid_state", () -> SlotAcceptance.preflight(slot, rejected, 0));
        slot.accepts = false; slot.putStack(stack.copy()); SlotAcceptance.preflight(slot, stack, 0);
        check(slot.getStack().stackSize == 1, "An occupied output slot is left to native take/merge semantics despite insertion refusal.");
        JsonObject unknown = new JsonObject(); SlotAcceptance.describe(unknown, slot, null);
        check(unknown.getAsJsonObject("slotAcceptance").get("acceptsCandidate").isJsonNull(), "Absent cursor is unknown candidate acceptance, not inferred from labels.");
        Duality duality = new Duality(); duality.patterns.setInventorySlotContents(0, stack.copy());
        JsonObject empty = AeWorldAdapter.interfaceState(duality);
        check(empty.get("bufferEmpty").getAsBoolean() && !empty.get("cancelClearsBuffer").getAsBoolean(), "Only actual empty waiting-to-send proves no interface buffer.");
        check(empty.getAsJsonObject("patterns").getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("item").get("nbt").getAsString().contains("different-full-pattern"), "World-side patterns retain full NBT rather than their shared display name.");
        duality.waiting.add(new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), stack, "identity", 9007199254740993L));
        JsonObject pending = AeWorldAdapter.interfaceState(duality);
        check(!pending.get("bufferEmpty").getAsBoolean() && pending.getAsJsonArray("waitingToSend").get(0).getAsJsonObject().get("amount").getAsString().equals("9007199254740993"), "Pending quantities retain exact decimal longs and block empty-buffer assumptions.");
        for (int index = 1; index <= 512; index++) duality.waiting.add(duality.waiting.get(0));
        JsonObject partial = AeWorldAdapter.interfaceState(duality);
        check(!partial.get("complete").getAsBoolean() && !partial.get("bufferEmpty").getAsBoolean(), "Bounded partial pending data cannot permit disk switching.");
        JsonObject saturated = AeWorldAdapter.resource(new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), stack, "identity", Long.MAX_VALUE));
        check(!saturated.get("quantityUnsaturated").getAsBoolean() && !saturated.has("finiteAmount"), "Finite storage is never inferred from displayed quantity alone.");
        check(!AeWorldAdapter.backing(new Object()).get("finiteBackingVerified").getAsBoolean(), "Unknown backing never certifies finite materials.");
        expect("inspection_failed", () -> AeWorldAdapter.resource(new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), stack, "identity", -1)));
        return checks;
    }
    static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "Existing refusal is retained."); } }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
