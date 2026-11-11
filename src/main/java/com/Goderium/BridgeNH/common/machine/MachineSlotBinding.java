package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransfer;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Client-side public GUI owner/handler identity; independent server identity guards remain required. */
public final class MachineSlotBinding {
    static final String BINDING = "public_native_slot_actual_machine_handler_identity";
    private static final String CONTAINER = "com.cleanroommc.modularui.screen.ModularContainer";
    private static final String POS_DATA = "com.cleanroommc.modularui.factory.PosGuiData";
    private static final String MODULAR_SLOT = "com.cleanroommc.modularui.widgets.slot.ModularSlot";
    private static final String GT_TILE = "gregtech.api.interfaces.tileentity.IGregTechTileEntity";
    private static final String BASIC_MACHINE = "gregtech.api.metatileentity.implementations.MTEBasicMachine";
    private static final String INPUT_BUS = "gregtech.api.metatileentity.implementations.MTEHatchInputBus";

    public static Context observe(Minecraft client, Container container) {
        return observe(client == null ? null : client.theWorld, client == null ? null : client.thePlayer, container, false);
    }

    public static Context observe(World world, EntityPlayer player, Container container, boolean server) {
        if (container == null || !ExpandedGuiApi.is(container, CONTAINER)
                || player == null || world == null)
            return Context.unavailable("public_machine_owner_api_unavailable");
        String stage = "container_identity";
        try {
            if (player.openContainer != container || container.windowId <= 0)
                return Context.unavailable("current_native_container_unverified");
            stage = "container_initialized";
            if (!Boolean.TRUE.equals(ExpandedGuiApi.call(container, "isInitialized")))
                return Context.unavailable("current_native_container_unverified");
            stage = "container_client";
            if (!Boolean.valueOf(!server).equals(ExpandedGuiApi.call(container, "isClient")))
                return Context.unavailable("current_native_container_unverified");
            stage = "container_client_only";
            if (!Boolean.FALSE.equals(ExpandedGuiApi.call(container, "isClientOnly")))
                return Context.unavailable("current_native_container_unverified");
            stage = "container_player";
            if (ExpandedGuiApi.call(container, "getPlayer") != player)
                return Context.unavailable("current_native_container_unverified");
            stage = "container_gui_data";
            Object data = ExpandedGuiApi.call(container, "getGuiData");
            if (!ExpandedGuiApi.is(data, POS_DATA))
                return Context.unavailable("public_machine_owner_world_unverified");
            stage = "gui_data_player";
            if (ExpandedGuiApi.call(data, "getPlayer") != player)
                return Context.unavailable("public_machine_owner_world_unverified");
            stage = "gui_data_world";
            if (ExpandedGuiApi.call(data, "getWorld") != world)
                return Context.unavailable("public_machine_owner_world_unverified");
            stage = "gui_data_x";
            int x = integer(ExpandedGuiApi.call(data, "getX"));
            stage = "gui_data_y";
            int y = integer(ExpandedGuiApi.call(data, "getY"));
            stage = "gui_data_z";
            int z = integer(ExpandedGuiApi.call(data, "getZ"));
            stage = "gui_data_tile";
            Object observed = ExpandedGuiApi.call(data, "getTileEntity");
            stage = "world_current_tile";
            TileEntity actual = world.getTileEntity(x, y, z);
            if (!(observed instanceof TileEntity) || observed != actual || actual.isInvalid()
                    || actual.xCoord != x || actual.yCoord != y || actual.zCoord != z
                    || world.provider == null || !ExpandedGuiApi.is(actual, GT_TILE))
                return Context.unavailable("public_machine_owner_tile_unverified");
            stage = "tile_world";
            if (ExpandedGuiApi.call(actual, "getWorld") != world)
                return Context.unavailable("public_machine_owner_tile_unverified");
            stage = "tile_meta";
            Object meta = ExpandedGuiApi.call(actual, "getMetaTileEntity");
            if (meta == null) return Context.unavailable("public_machine_owner_meta_unverified");
            stage = "meta_base_tile";
            if (ExpandedGuiApi.call(meta, "getBaseMetaTileEntity") != actual)
                return Context.unavailable("public_machine_owner_meta_unverified");
            if (!(meta instanceof IInventory))
                return Context.unavailable("public_machine_inventory_api_unavailable");
            stage = "meta_inventory_handler";
            Object handler = ExpandedGuiApi.call(meta, "getInventoryHandler");
            stage = "meta_inventory_size";
            int size = ((IInventory) meta).getSizeInventory();
            stage = "tile_meta_id";
            int id = integer(ExpandedGuiApi.call(actual, "getMetaTileID"));
            stage = "world_block_registry";
            Object registry = Block.blockRegistry.getNameForObject(world.getBlock(x, y, z));
            if (handler == null || size < 1 || size > 256
                    || id < 0 || id > 32767 || registry == null || registry.toString().isEmpty())
                return Context.unavailable("public_machine_inventory_unverified");
            stage = "handler_inventory_size";
            if (integer(ExpandedGuiApi.call(handler, "getSlots")) != size)
                return Context.unavailable("public_machine_inventory_unverified");
            stage = "tile_meta_recheck";
            if (ExpandedGuiApi.call(actual, "getMetaTileEntity") != meta)
                return Context.unavailable("public_machine_owner_meta_changed");
            JsonObject owner = new JsonObject();
            owner.addProperty("x", x); owner.addProperty("y", y); owner.addProperty("z", z);
            owner.addProperty("dimension", world.provider.dimensionId);
            owner.addProperty("registryName", registry.toString()); owner.addProperty("metaTileId", id);
            owner.addProperty("metaClass", meta.getClass().getName());
            stage = "native_slot_binding";
            return new Context(container, meta, handler, owner, size);
        } catch (RuntimeException | LinkageError error) {
            return Context.unavailable("public_machine_owner_api_unavailable_at_" + stage);
        }
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Short) && !(value instanceof Byte))
            throw new BridgeException("unsupported", "Fixed public machine inventory API did not return an integer.");
        return ((Number) value).intValue();
    }

    /** Constructed only after current client GUI owner proof; tests supply an already-proved owner fixture. */
    public static final class Context {
        private final Object meta;
        private final JsonObject owner;
        private final int size;
        private final Map<Slot, Integer> indices = new IdentityHashMap<Slot, Integer>();
        private final Map<Integer, Integer> multiplicity = new HashMap<Integer, Integer>();
        private final Map<Slot, Boolean> canPut = new IdentityHashMap<Slot, Boolean>();
        private final Map<Slot, String> reasons = new IdentityHashMap<Slot, String>();
        private String unavailable;
        private boolean wholeStackContainer;

        private Context(String reason) { meta = null; owner = null; size = 0; unavailable = reason; }
        public static Context unavailable(String reason) { return new Context(reason); }

        Context(Container container, Object meta, Object handler, JsonObject owner, int size) {
            this.meta = meta; this.owner = owner; this.size = size;
            wholeStackContainer = container != null && CONTAINER.equals(container.getClass().getName());
            if (container == null || handler == null || owner == null || size < 1 || size > 256
                    || container.inventorySlots.isEmpty() || container.inventorySlots.size() > 512) {
                unavailable = "machine_slot_coverage_incomplete"; return;
            }
            try {
                for (Object member : container.inventorySlots) {
                    if (!(member instanceof Slot) || !ExpandedGuiApi.is(member, MODULAR_SLOT))
                        throw new BridgeException("unsupported", "Every current native modular slot needs its public slot API.");
                    Slot slot = (Slot) member;
                    if (ExpandedGuiApi.call(container, "getModularSlot", slot.slotNumber) != slot)
                        throw new BridgeException("unsupported", "The public native slot lookup must preserve actual slot identity.");
                    if (ExpandedGuiApi.call(slot, "getItemHandler") != handler) {
                        reasons.put(slot, "actual_machine_handler_identity_mismatch"); continue;
                    }
                    if (!Boolean.FALSE.equals(ExpandedGuiApi.call(slot, "isPhantom"))) {
                        reasons.put(slot, "phantom_machine_slot_not_consumed_input"); continue;
                    }
                    int index = slot.getSlotIndex();
                    if (index < 0 || index >= size) {
                        reasons.put(slot, "machine_handler_index_out_of_range"); continue;
                    }
                    Object insertion = ExpandedGuiApi.call(slot, "isCanPut");
                    if (!(insertion instanceof Boolean))
                        throw new BridgeException("unsupported", "Actual modular insertion permission needs its public boolean getter.");
                    indices.put(slot, index); canPut.put(slot, (Boolean) insertion);
                    multiplicity.put(index, multiplicity.containsKey(index) ? multiplicity.get(index) + 1 : 1);
                }
            } catch (RuntimeException | LinkageError error) {
                unavailable = "public_machine_slot_coverage_unavailable";
            }
        }

        public Object meta() { return unavailable == null ? meta : null; }
        public JsonObject owner() { return unavailable == null && owner != null ? new JsonParser().parse(owner.toString()).getAsJsonObject() : null; }
        public JsonObject wholeStackCapability() {
            JsonObject result = WholeStackTransfer.capabilities(); result.addProperty("supported", unavailable == null && owner != null && wholeStackContainer);
            if (unavailable != null) result.addProperty("reason", unavailable);
            else if (!wholeStackContainer) result.addProperty("reason", "exact_normal_modular_container_required");
            else if (owner != null) result.add("owner", owner());
            return result;
        }

        public JsonObject bindFluidTank(Object tank) {
            if (meta == null || owner == null)
                return BasicMachineFluidAdapter.unavailable("client_display", unavailable == null ? "public_machine_owner_unverified" : unavailable);
            return BasicMachineFluidAdapter.bind(meta, owner, tank);
        }

        public void describe(JsonObject entry, Slot slot) {
            entry.addProperty("machineInventoryBindingVerified", false);
            entry.addProperty("machineInventoryRole", "unavailable"); entry.addProperty("consumedInput", false);
            MachineSlotPurpose.unavailable(entry, "machine_inventory_binding_unverified");
            if (unavailable != null) { entry.addProperty("machineInventoryBindingReason", unavailable); return; }
            if (entry.has("playerInventoryBindingVerified") && entry.get("playerInventoryBindingVerified").getAsBoolean()) {
                entry.addProperty("machineInventoryBindingReason", "actual_player_slot_not_machine_inventory"); return;
            }
            Integer index = indices.get(slot);
            if (index == null) { entry.addProperty("machineInventoryBindingReason", reasons.containsKey(slot) ? reasons.get(slot) : "public_machine_slot_binding_unavailable"); return; }
            if (multiplicity.get(index) != 1) { entry.addProperty("machineInventoryBindingReason", "ambiguous_machine_inventory_index"); return; }
            entry.addProperty("machineInventoryBindingVerified", true);
            entry.addProperty("machineInventoryIndex", index); entry.addProperty("nativeContainerSlotId", slot.slotNumber);
            entry.addProperty("machineInventoryBinding", BINDING);
            entry.add("machineInventoryOwner", new JsonParser().parse(owner.toString()).getAsJsonObject());
            entry.addProperty("machineInventoryCanPut", Boolean.TRUE.equals(canPut.get(slot)));
            MachineSlotPurpose.describe(entry, meta, index, size);
            try {
                boolean input;
                if (ExpandedGuiApi.is(meta, BASIC_MACHINE)) {
                    int start = integer(ExpandedGuiApi.call(meta, "getInputSlot"));
                    int end = integer(ExpandedGuiApi.call(meta, "getOutputSlot"));
                    if (start < 0 || end < start || end > size) throw new BridgeException("unsupported", "Public input/output inventory boundaries are invalid.");
                    Object io = ExpandedGuiApi.call(meta, "isIOSlot", index);
                    if (!(io instanceof Boolean)) throw new BridgeException("unsupported", "Public I/O slot role is unavailable.");
                    input = index >= start && index < end && Boolean.TRUE.equals(io);
                } else if (ExpandedGuiApi.is(meta, INPUT_BUS)) {
                    int circuit = integer(ExpandedGuiApi.call(meta, "getCircuitSlot"));
                    if (circuit < 0 || circuit >= size) throw new BridgeException("unsupported", "Public input-bus circuit index is outside its actual inventory.");
                    Object valid = ExpandedGuiApi.call(meta, "isValidSlot", index);
                    if (!(valid instanceof Boolean)) throw new BridgeException("unsupported", "Public input-bus inventory role is unavailable.");
                    input = index != circuit && Boolean.TRUE.equals(valid);
                } else {
                    entry.addProperty("machineInventoryRoleReason", "public_consumed_input_role_unavailable"); return;
                }
                entry.addProperty("machineInventoryRole", input ? "consumed_input" : "other");
                entry.addProperty("consumedInput", input && Boolean.TRUE.equals(canPut.get(slot)));
                if (input && !Boolean.TRUE.equals(canPut.get(slot))) entry.addProperty("machineInventoryRoleReason", "native_slot_insertion_disabled");
            } catch (RuntimeException | LinkageError error) {
                entry.addProperty("machineInventoryRoleReason", "public_consumed_input_role_unavailable");
            }
        }
    }
}
