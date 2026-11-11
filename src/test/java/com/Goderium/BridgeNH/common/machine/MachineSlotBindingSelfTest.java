package com.Goderium.BridgeNH.common.machine;

import com.cleanroommc.modularui.screen.ModularContainer;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;

public final class MachineSlotBindingSelfTest {
    public static int run() {
        int checks = 0;
        Object actual = new Object(), foreign = new Object();
        MTEHatchInputBus bus = new MTEHatchInputBus(25);
        ModularContainer container = new ModularContainer();
        ModularSlot input = add(container, actual, 17), circuit = add(container, actual, 25), player = add(container, foreign, 17);
        MachineSlotBinding.Context bindings = bind(container, bus, actual, 26);
        JsonObject data = read(bindings, input);
        check(verified(data) && data.get("machineInventoryIndex").getAsInt() == 17 && data.get("nativeContainerSlotId").getAsInt() == 0,
                "Actual handler index17 remains separate from native GUI slot0 and its InventoryBasic shadow"); checks++;
        check(data.get("machineInventoryBinding").getAsString().equals(MachineSlotBinding.BINDING)
                && data.get("machineInventoryOwner").getAsJsonObject().get("metaTileId").getAsInt() == 74
                && data.get("machineInventoryOwner").getAsJsonObject().get("x").getAsInt() == -260,
                "Only a proved owner publishes exact identity and the fixed public binding contract"); checks++;
        check(consumed(data) && data.get("machineInventoryRole").getAsString().equals("consumed_input"), "An ordinary non-circuit input-bus slot is a real consumed-input candidate"); checks++;
        data = read(bindings, circuit);
        check(verified(data) && !consumed(data) && data.get("machineInventoryRole").getAsString().equals("other"), "A bus circuit slot is owned but never a consumed-input candidate"); checks++;
        check(!verified(read(bindings, player)), "A foreign handler with the same numeric index and shadow inventory is not machine ownership"); checks++;
        data = new JsonObject(); data.addProperty("playerInventoryBindingVerified", true); bindings.describe(data, input);
        check(!verified(data) && !data.has("machineInventoryIndex"), "A verified player slot cannot become a machine input even if another predicate conflicts"); checks++;

        ModularContainer basicContainer = new ModularContainer();
        ModularSlot before = add(basicContainer, actual, 7), basicInput = add(basicContainer, actual, 8), output = add(basicContainer, actual, 10);
        MachineSlotBinding.Context basic = bind(basicContainer, new MTEBasicMachine(8, 10), actual, 20);
        check(!consumed(read(basic, before)) && consumed(read(basic, basicInput)) && !consumed(read(basic, output)),
                "Runtime public input/output boundaries select inputs without hardcoded GUI or inventory offsets"); checks++;
        MachineSlotBinding.Context invalidRange = bind(basicContainer, new MTEBasicMachine(8, 30), actual, 20);
        data = read(invalidRange, basicInput);
        check(verified(data) && !consumed(data) && data.get("machineInventoryRole").getAsString().equals("unavailable"), "Invalid public boundaries cannot manufacture an input role"); checks++;
        MachineSlotBinding.Context unknown = bind(basicContainer, new SimilarMethods(), actual, 20);
        data = read(unknown, basicInput);
        check(verified(data) && !consumed(data) && data.get("machineInventoryRole").getAsString().equals("unavailable"), "Lookalike getter names on unknown machine APIs do not imply a consumed-input role"); checks++;

        ModularContainer mirrorContainer = new ModularContainer();
        ModularSlot mirrorA = add(mirrorContainer, actual, 17), mirrorB = add(mirrorContainer, actual, 17);
        MachineSlotBinding.Context mirrors = bind(mirrorContainer, bus, actual, 26);
        check(!verified(read(mirrors, mirrorA)) && !verified(read(mirrors, mirrorB))
                && read(mirrors, mirrorA).get("machineInventoryBindingReason").getAsString().equals("ambiguous_machine_inventory_index"),
                "All actual mirrors of one machine index remain unverified rather than hiding duplicate ambiguity"); checks++;

        ModularContainer phantomContainer = new ModularContainer();
        ModularSlot phantom = add(phantomContainer, actual, 17); phantom.phantom = true;
        ModularSlot disabled = add(phantomContainer, actual, 18); disabled.canPut = false;
        MachineSlotBinding.Context permissions = bind(phantomContainer, bus, actual, 26);
        check(!verified(read(permissions, phantom)) && !read(permissions, phantom).has("machineInventoryIndex"), "Phantom slots do not publish a finite consumed-input binding"); checks++;
        check(verified(read(permissions, disabled)) && !consumed(read(permissions, disabled)), "Native disabled insertion prevents automatic input selection despite known inventory ownership"); checks++;

        ModularContainer failedContainer = new ModularContainer();
        ModularSlot initiallyValid = add(failedContainer, actual, 17); failedContainer.add(new BrokenSlot(actual, 18));
        check(!verified(read(bind(failedContainer, bus, actual, 26), initiallyValid)), "A later public slot getter failure invalidates whole-container uniqueness proof"); checks++;
        BrokenLookupContainer brokenLookup = new BrokenLookupContainer();
        ModularSlot wrongLookup = add(brokenLookup, actual, 17);
        check(!verified(read(bind(brokenLookup, bus, actual, 26), wrongLookup)), "Native slot lookup must return the exact current actual slot object"); checks++;

        ModularContainer boundsContainer = new ModularContainer();
        ModularSlot outOfRange = add(boundsContainer, actual, 26);
        check(!verified(read(bind(boundsContainer, bus, actual, 26), outOfRange)), "An out-of-range handler index does not publish ownership"); checks++;
        ModularContainer partialContainer = new ModularContainer();
        ModularSlot partialInput = add(partialContainer, actual, 17);
        for (int i = 1; i < 513; i++) add(partialContainer, foreign, 0);
        check(!verified(read(bind(partialContainer, bus, actual, 26), partialInput)), "Truncated native container coverage cannot prove a unique input binding"); checks++;
        ModularContainer unknownSlotContainer = new ModularContainer();
        ModularSlot known = add(unknownSlotContainer, actual, 17);
        unknownSlotContainer.add(new Slot(new InventoryBasic("unknown", false, 1), 0, 0, 0));
        check(!verified(read(bind(unknownSlotContainer, bus, actual, 26), known)), "An unknown slot API prevents incomplete native-container uniqueness proof"); checks++;
        data = read(bind(container, new MTEHatchInputBus(30), actual, 26), input);
        check(verified(data) && !consumed(data), "A circuit getter outside the actual inventory cannot establish a bus input role"); checks++;
        data = read(MachineSlotBinding.observe(null, null), input);
        check(!verified(data) && !data.has("machineInventoryOwner") && !data.has("machineInventoryIndex"), "Absent current client/owner APIs remain explicit unavailable without a target guess"); checks++;
        Container legacy = new Container() { @Override public boolean canInteractWith(EntityPlayer value) { return true; } };
        data = read(MachineSlotBinding.observe(null, legacy), input);
        check(!verified(data) && !data.has("machineInventoryOwner") && data.get("machineInventoryBindingReason").getAsString().equals("public_machine_owner_api_unavailable"),
                "MUI1 or ordinary containers lacking the supported public PosGuiData owner path remain explicit unavailable"); checks++;
        return checks;
    }

    private static ModularSlot add(ModularContainer container, Object handler, int index) { ModularSlot slot = new ModularSlot(handler, index); container.add(slot); return slot; }
    private static MachineSlotBinding.Context bind(ModularContainer container, Object meta, Object handler, int size) {
        JsonObject owner = new JsonObject(); owner.addProperty("x", -260); owner.addProperty("y", 119); owner.addProperty("z", -120);
        owner.addProperty("dimension", 180); owner.addProperty("registryName", "gregtech:gt.blockmachines"); owner.addProperty("metaTileId", 74); owner.addProperty("metaClass", meta.getClass().getName());
        return new MachineSlotBinding.Context(container, meta, handler, owner, size);
    }
    private static JsonObject read(MachineSlotBinding.Context context, Slot slot) { JsonObject value = new JsonObject(); context.describe(value, slot); return value; }
    private static boolean verified(JsonObject value) { return value.get("machineInventoryBindingVerified").getAsBoolean(); }
    private static boolean consumed(JsonObject value) { return value.get("consumedInput").getAsBoolean(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static class SimilarMethods { public int getInputSlot() { return 8; } public int getOutputSlot() { return 10; } }
    public static class BrokenSlot extends ModularSlot {
        public BrokenSlot(Object handler, int index) { super(handler, index); }
        @Override public Object getItemHandler() { throw new IllegalStateException("Unavailable public getter"); }
    }
    public static class BrokenLookupContainer extends ModularContainer { @Override public ModularSlot getModularSlot(int id) { return null; } }
}
