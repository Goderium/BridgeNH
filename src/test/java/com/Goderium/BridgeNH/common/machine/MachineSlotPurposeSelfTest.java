package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.gui.GuiItemSlotBinding;
import com.Goderium.BridgeNH.client.gui.GuiPublicSemantics;
import com.Goderium.BridgeNH.client.gui.WidgetFixtures;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import java.util.Collections;
import java.util.function.Predicate;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Purpose boundaries and real widget identity, without hypothetical insertion or guessed machine adapters. */
public final class MachineSlotPurposeSelfTest {
    private static int checks;
    public static int run() { checks = 0; purposes(); ownerGuards(); displayIdentity(); displayIntegration(); return checks; }

    private static void purposes() {
        MTEBasicMachine machine = new MTEBasicMachine(8, 10);
        check(purpose(machine, 8).equals("input") && purpose(machine, 9).equals("input") && purpose(machine, 10).equals("output")
                && purpose(machine, 11).equals("output"), "Runtime input/output ranges are half-open and use the public output array length");
        check(purpose(machine, 6).equals("special") && purpose(machine, 7).equals("configuration_circuit"), "Runtime special and circuit getters are distinct native purposes");
        JsonObject data = read(machine, 4);
        check(data.get("machineInventoryPurposeVerified").getAsBoolean() && purpose(data).equals("battery")
                && data.get("machineInventoryBatteryCharging").getAsBoolean() && data.get("machineInventoryBatteryDischarging").getAsBoolean()
                && data.getAsJsonArray("machineInventoryPurposeCandidates").size() == 1, "Coincident native charge/discharge ranges form one battery purpose");
        check(purpose(machine, 5).equals("unavailable") && purpose(machine, 12).equals("unavailable"), "Unmapped indices are not guessed from proximity to known slots");
        machine.chargeCount = 0; machine.dischargeCount = 0;
        check(purpose(machine, 4).equals("unavailable"), "Zero native charge/discharge counts never manufacture a battery purpose from a start index");
        machine.chargeCount = 1; machine.dischargeCount = 1; machine.special = 8;
        data = read(machine, 8);
        check(!data.get("machineInventoryPurposeVerified").getAsBoolean() && data.get("machineInventoryPurposeReason").getAsString().equals("overlapping_public_slot_purposes")
                && data.getAsJsonArray("machineInventoryPurposeCandidates").size() == 2, "A real input/special overlap remains ambiguous with both native claims preserved");
        machine.special = 4;
        check(!read(machine, 4).get("machineInventoryPurposeVerified").getAsBoolean(), "Battery versus special overlap cannot be silently prioritized");
        machine.special = -1;
        data = read(machine, 10);
        check(purpose(data).equals("output") && data.get("machineInventoryPurposeVerified").getAsBoolean()
                && !data.get("machineInventoryPurposeCoverageComplete").getAsBoolean() && hasReason(data, "negative_public_range_start"), "An invalid unrelated special getter preserves the independently proved output and its incomplete-coverage warning");
        machine.special = 6; machine.chargeCount = -1;
        check(purpose(machine, 8).equals("input") && hasReason(read(machine, 8), "negative_public_range_count"), "A negative battery count does not discard an independently proved input purpose");
        machine.chargeCount = Integer.MAX_VALUE;
        check(purpose(machine, 10).equals("output") && hasReason(read(machine, 10), "public_range_outside_actual_inventory"), "Public range arithmetic is checked without integer overflow");
        machine.chargeCount = 1; machine.end = 9;
        check(purpose(machine, 8).equals("input") && purpose(machine, 9).equals("output"), "Boundaries are read from this runtime object rather than cached constructor offsets");
        machine.end = 7;
        check(hasReason(read(machine, 8), "negative_public_range_count"), "Reversed public input boundaries retain the exact invalid range reason");
        machine.end = 19;
        check(hasReason(read(machine, 19), "public_range_outside_actual_inventory"), "An output array extending past actual inventory is rejected");
        machine.start = -1; machine.end = 10;
        check(purpose(machine, 10).equals("output") && hasReason(read(machine, 10), "negative_public_range_start"), "A negative input start leaves the independent native output range usable");
        data = read(new ContradictoryMachine(), 8);
        check(!data.get("machineInventoryPurposeVerified").getAsBoolean() && hasReason(data, "native_io_role_conflicts_with_input_range"), "Contradictory native I/O predicate is not converted into a consumed input purpose");
        data = read(new SimilarMethods(), 8);
        check(!data.get("machineInventoryPurposeVerified").getAsBoolean() && !data.get("machineInventoryPurposeCoverageComplete").getAsBoolean(), "Lookalike methods on an unknown API family are not native role evidence");
        data = new JsonObject(); MachineSlotPurpose.describe(data, new MTEBasicMachine(8, 10), -1, 20);
        check(data.get("machineInventoryPurposeReason").getAsString().equals("actual_inventory_index_out_of_range"), "Negative actual inventory indices never enter purpose classification");
    }

    private static void ownerGuards() {
        Object actual = new Object(), foreign = new Object(); ModularContainer container = new ModularContainer();
        ModularSlot input = add(container, actual, 8), stranger = add(container, foreign, 8), ghost = add(container, actual, 7); ghost.phantom = true;
        MachineSlotBinding.Context context = bind(container, actual);
        JsonObject data = read(context, input);
        check(purpose(data).equals("input") && data.get("consumedInput").getAsBoolean() && data.get("machineInventoryRole").getAsString().equals("consumed_input"), "Purpose metadata retains the existing consumed-input contract");
        check(!read(context, stranger).get("machineInventoryPurposeVerified").getAsBoolean() && !read(context, stranger).has("machineInventoryPurposeEvidence"), "Foreign handler with the same numeric index cannot acquire a machine purpose");
        check(!read(context, ghost).get("machineInventoryPurposeVerified").getAsBoolean() && !read(context, ghost).has("machineInventoryIndex"), "Phantom slot remains outside finite machine inventory binding");
        JsonObject player = new JsonObject(); player.addProperty("playerInventoryBindingVerified", true); context.describe(player, input);
        check(!player.get("machineInventoryPurposeVerified").getAsBoolean(), "Verified player binding takes precedence over a conflicting machine claim");
        input.canPut = false; data = read(bind(container, actual), input);
        check(purpose(data).equals("input") && !data.get("consumedInput").getAsBoolean() && !data.get("machineInventoryCanPut").getAsBoolean(), "Native insertion access does not erase purpose or claim candidate acceptance");
        add(container, actual, 8);
        check(!read(bind(container, actual), input).get("machineInventoryPurposeVerified").getAsBoolean(), "Ambiguous actual handler mirrors never publish a machine purpose");
    }

    private static void displayIdentity() {
        Object handler = new Object(); ModularContainer container = new ModularContainer();
        ModularSlot nativeSlot = add(container, handler, 17); ItemSlot widget = new ItemSlot(nativeSlot);
        JsonObject data = GuiItemSlotBinding.read(widget, container);
        check(data.get("bindingVerified").getAsBoolean() && data.get("nativeContainerSlotId").getAsInt() == 0
                && data.get("inventoryIndex").getAsInt() == 17 && data.get("binding").getAsString().equals(GuiItemSlotBinding.BINDING), "Actual display slot preserves native container ID separately from handler index and InventoryBasic shadow");
        check(data.get("canPut").getAsBoolean() && data.get("canTake").getAsBoolean() && !data.get("phantom").getAsBoolean()
                && data.get("filterPresent").getAsBoolean() && !data.get("acceptanceAvailable").getAsBoolean(), "Only public access flags and filter presence are reported; throwing acceptance/filter fixtures are never invoked");
        nativeSlot.canPut = false; nativeSlot.canTake = false; nativeSlot.phantom = true;
        data = GuiItemSlotBinding.read(widget, container);
        check(data.get("bindingVerified").getAsBoolean() && !data.get("canPut").getAsBoolean() && !data.get("canTake").getAsBoolean()
                && data.get("phantom").getAsBoolean(), "Display identity can describe disabled/phantom slots without authorizing finite-machine use");
        check(!GuiItemSlotBinding.read(new SimilarSlot(widget.slot), container).get("bindingVerified").getAsBoolean(), "Similar getter names without public ItemSlot ancestry cannot bind display");
        check(!GuiItemSlotBinding.read(new ItemSlot(new ModularSlot(handler, 17)), container).get("bindingVerified").getAsBoolean(), "An equal index on a detached native slot is not the actual container slot");
        ItemSlot mismatched = new ItemSlot(nativeSlot) { @Override public Slot getVanillaSlot() { return new ModularSlot(handler, 17); } };
        check(!GuiItemSlotBinding.read(mismatched, container).get("bindingVerified").getAsBoolean(), "Both public widget slot APIs must agree on the exact object");
        ModularContainer broken = new ModularContainer() { @Override public ModularSlot getModularSlot(int id) { return null; } }; broken.add(nativeSlot);
        check(!GuiItemSlotBinding.read(widget, broken).get("bindingVerified").getAsBoolean(), "A mismatched public native-container lookup is rejected");
        ModularSlot duplicateNumber = add(container, handler, 18); duplicateNumber.slotNumber = nativeSlot.slotNumber;
        check(!GuiItemSlotBinding.read(widget, container).get("bindingVerified").getAsBoolean(), "Duplicate native IDs cannot bind a display widget to a guessed table row");
        ModularContainer unknown = new ModularContainer(); unknown.add(nativeSlot); unknown.add(new Slot(new InventoryBasic("unknown", false, 1), 0, 0, 0));
        check(!GuiItemSlotBinding.read(widget, unknown).get("bindingVerified").getAsBoolean(), "Unknown native slot API prevents incomplete container proof");
        ModularContainer huge = new ModularContainer(); huge.add(nativeSlot); for (int i = 1; i < 513; i++) add(huge, handler, i);
        check(!GuiItemSlotBinding.read(widget, huge).get("bindingVerified").getAsBoolean(), "Truncated native container coverage remains explicit unavailable");
        ModularContainer optionalFailure = new ModularContainer(); ModularSlot unavailableFilter = new ModularSlot(handler, 17) {
            @Override public Predicate<ItemStack> getFilter() { throw new IllegalStateException("Unavailable public filter"); }
        }; optionalFailure.add(unavailableFilter);
        data = GuiItemSlotBinding.read(new ItemSlot(unavailableFilter), optionalFailure);
        check(data.get("bindingVerified").getAsBoolean() && data.get("filterUnavailable").getAsBoolean() && !data.has("filterPresent"), "Optional native filter getter failure does not discard independently proved display identity");
    }

    private static void displayIntegration() {
        ModularContainer container = new ModularContainer(); ModularSlot nativeSlot = add(container, new Object(), 17);
        DisplayScreen screen = new DisplayScreen(container); ItemSlot widget = new ItemSlot(nativeSlot); screen.root.children.add(widget);
        widget.tooltip = new RichTooltip(); widget.tooltip.builder = () -> widget.tooltip.text.lines.add("Tool or upgrade label supplied by the current GUI");
        JsonObject entry = new JsonObject(); GuiPublicSemantics.describe(widget, entry, screen);
        check(entry.getAsJsonObject("itemSlot").get("bindingVerified").getAsBoolean() && entry.getAsJsonArray("tooltip").get(0).getAsString().equals("Tool or upgrade label supplied by the current GUI")
                && !entry.has("semantic") && !entry.has("machineInventoryPurpose"), "Current slot tooltip stays bound display text, without guessing tool or upgrade purpose");
        JsonObject args = new JsonObject(); args.addProperty("screenId", "purpose-fixture"); args.addProperty("limit", 128);
        JsonArray entries = GuiControlAdapter.read(screen, args, Collections.emptyList(), System.nanoTime() + StructureAdapter.SOFT_BUDGET_NANOS).getAsJsonArray("widgets");
        check(entries.size() == 2 && entries.get(1).getAsJsonObject().getAsJsonObject("itemSlot").get("nativeContainerSlotId").getAsInt() == 0
                && entries.get(1).getAsJsonObject().getAsJsonArray("supportedActions").size() == 0, "gui.widgets reports actual item-slot display identity without adding a generic widget mutation");
        JsonObject inspected = GuiDataAdapter.inspect(null, screen, new JsonObject());
        check(inspected.getAsJsonArray("widgets").size() == 2 && inspected.getAsJsonArray("widgets").get(1).getAsJsonObject().getAsJsonObject("itemSlot").get("bindingVerified").getAsBoolean(), "gui.inspect widget data carries the same actual native slot association");
        screen.inventorySlots = new ModularContainer(); entry = new JsonObject(); GuiPublicSemantics.describe(widget, entry, screen);
        check(!entry.getAsJsonObject("itemSlot").get("bindingVerified").getAsBoolean(), "Replacing the screen container invalidates a previously observed widget association");
        GuiControlAdapter.invalidate();
    }

    private static String purpose(Object machine, int index) { return purpose(read(machine, index)); }
    private static String purpose(JsonObject value) { return value.get("machineInventoryPurpose").getAsString(); }
    private static JsonObject read(Object machine, int index) { JsonObject value = new JsonObject(); MachineSlotPurpose.describe(value, machine, index, 20); return value; }
    private static boolean hasReason(JsonObject value, String reason) {
        for (JsonElement element : value.getAsJsonArray("machineInventoryPurposeEvidence")) {
            JsonObject item = element.getAsJsonObject(); if (item.has("reason") && item.get("reason").getAsString().equals(reason)) return true;
        }
        return false;
    }
    private static ModularSlot add(ModularContainer container, Object handler, int index) { ModularSlot slot = new ModularSlot(handler, index); container.add(slot); return slot; }
    private static MachineSlotBinding.Context bind(ModularContainer container, Object handler) {
        JsonObject owner = new JsonObject(); owner.addProperty("metaTileId", 74); return new MachineSlotBinding.Context(container, new MTEBasicMachine(8, 10), handler, owner, 20);
    }
    private static JsonObject read(MachineSlotBinding.Context context, Slot slot) { JsonObject result = new JsonObject(); context.describe(result, slot); return result; }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static class ContradictoryMachine extends MTEBasicMachine { public ContradictoryMachine() { super(8, 10); } @Override public boolean isIOSlot(int index) { return false; } }
    public static class SimilarMethods { public int getInputSlot() { return 8; } public int getOutputSlot() { return 10; } public int getSpecialSlotIndex() { return 17; } }
    public static class SimilarSlot { private final Slot slot; public SimilarSlot(Slot slot) { this.slot = slot; } public Slot getSlot() { return slot; } public Slot getVanillaSlot() { return slot; } }
    public static class DisplayScreen extends GuiContainer implements IMuiScreen {
        final Widget root = new Widget(); private final WidgetFixtures.Modular screen = new WidgetFixtures.Modular(root);
        DisplayScreen(ModularContainer container) { super(container); }
        public WidgetFixtures.Modular getScreen() { return screen; }
        @Override protected void drawGuiContainerBackgroundLayer(float partial, int x, int y) { throw new AssertionError("Display observation must never render"); }
    }
}
