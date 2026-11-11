package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.nei.NeiGhostDragAdapter;
import com.Goderium.BridgeNH.common.inventory.GhostItemSource;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Explicit native AE actions. Caller supplies current screen/container/takeover/once-only guards. */
public final class AeControlAdapter {
    private static final String GUI = AeDataAdapter.GUI, CONTAINER = AeDataAdapter.CONTAINER;
    private static final String PACKET = "appeng.core.sync.packets.";
    private AeControlAdapter() {}
    public static JsonObject perform(Minecraft client, GuiScreen screen, JsonObject args) {
        if (client.thePlayer == null || !(screen instanceof GuiContainer) || client.thePlayer.openContainer != ((GuiContainer) screen).inventorySlots)
            throw new BridgeException("stale_screen", "The current player container no longer matches the GUI.");
        ItemStack[] inventory = client.thePlayer.inventory.mainInventory;
        return perform(screen, client.thePlayer.openContainer, client.thePlayer.inventory.getItemStack(), inventory, args,
                packet -> send(screen, packet));
    }
    public interface PacketSink { void send(Object packet); }
    static JsonObject perform(Object screen, Object container, ItemStack cursor, ItemStack[] inventory, JsonObject args, PacketSink sink) {
        String id = ExpandedGuiApi.string(args, "screenId", 128); AeDataAdapter.bind(screen, id);
        String action = ExpandedGuiApi.string(args, "action", 64);
        JsonObject result = new JsonObject(); result.addProperty("screenId", id); result.addProperty("action", action);
        if (action.equals("terminal_search")) search(screen, args, result);
        else if (action.equals("terminal_setting")) setting(screen, args, result, sink);
        else if (action.equals("terminal_type_filter")) typeFilter(screen, container, args, result, sink);
        else if (action.equals("resource_container")) resourceContainer(screen, container, args, result, sink);
        else if (action.equals("resource_take") || action.equals("resource_deposit") || action.equals("craft_open")) resource(screen, container, cursor, args, action, result, sink);
        else if (action.equals("craft_request")) craftRequest(screen, args, result, sink);
        else if (action.equals("crafting_output")) craftingOutput(screen, container, cursor, args, result, sink);
        else if (action.equals("pattern_set")) patternSet(screen, container, inventory, args, result, sink);
        else if (action.equals("cell_config_set") || action.equals("cell_config_clear")) cellConfig(screen, container, cursor, inventory, args, action, result);
        else if (action.equals("pattern_mode")) patternMode(screen, container, args, result);
        else if (action.equals("pattern_multiply")) patternMultiply(screen, container, args, result);
        else if (action.equals("pattern_page")) patternPage(screen, container, args, result);
        else if (action.equals("pattern_encode") || action.equals("pattern_clear")) patternAction(screen, container, args, action, result);
        else if (action.equals("preview_start")) previewStart(screen, container, args, sink);
        else if (action.equals("cpu_select")) cpuSelect(screen, args, result, sink);
        else if (action.equals("job_cancel") || action.equals("job_pause")) job(screen, container, args, action, result, sink);
        else if (action.equals("priority_open") || action.equals("priority_set") || action.equals("host_setting"))
            AeConfiguration.action(screen, container, args, result, sink);
        else throw new BridgeException("invalid_args", "Unknown semantic AE action.");
        result.addProperty("submitted", true); result.addProperty("completion", "Native action submitted or local value applied; server completion requires a later inspection. Never automatically repeat a mutation."); return result;
    }
    private static void search(Object screen, JsonObject args, JsonObject result) {
        ExpandedGuiApi.keys(args, "text", "field");
        String text = ExpandedGuiApi.string(args, "text", 1024);
        String field = ExpandedGuiApi.option(args, "field", "resources", "resources", "inputs", "outputs", "names");
        Object target;
        if (field.equals("resources")) {
            if (ExpandedGuiApi.is(screen, GUI + "GuiMEMonitorable")) target = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "searchField");
            else if (ExpandedGuiApi.is(screen, GUI + "GuiCraftingCPU")) target = ExpandedGuiApi.field(screen, GUI + "GuiCraftingCPU", "searchField");
            else if (ExpandedGuiApi.is(screen, GUI + "GuiCraftConfirm")) target = ExpandedGuiApi.field(screen, GUI + "GuiCraftConfirm", "searchField");
            else throw new BridgeException("unsupported", "This AE screen has no resource search.");
        } else {
            AeDataAdapter.require(screen, GUI + "GuiInterfaceTerminal");
            target = ExpandedGuiApi.field(screen, GUI + "GuiInterfaceTerminal", "searchField" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
        }
        result.addProperty("before", String.valueOf(ExpandedGuiApi.call(target, "getText"))); ExpandedGuiApi.call(target, "setText", text);
        result.addProperty("after", String.valueOf(ExpandedGuiApi.call(target, "getText")));
    }
    private static void setting(Object screen, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "setting", "value"); AeDataAdapter.require(screen, GUI + "GuiMEMonitorable");
        String name = ExpandedGuiApi.option(args, "setting", null, "SORT_BY", "SORT_DIRECTION", "VIEW_MODE");
        String value = ExpandedGuiApi.string(args, "value", 64);
        Object setting = ExpandedGuiApi.enumeration(screen, "appeng.api.config.Settings", name);
        Object possible = ExpandedGuiApi.call(setting, "getPossibleValues"); Object selected = null;
        if (possible instanceof Iterable<?>) for (Object option : (Iterable<?>) possible) if (value.equals(String.valueOf(option))) { selected = option; break; }
        if (selected == null) throw new BridgeException("invalid_args", "Value is not an advertised native setting option.");
        Object config = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "configSrc");
        result.addProperty("before", String.valueOf(ExpandedGuiApi.call(config, "getSetting", setting)));
        Object packet = ExpandedGuiApi.create(screen, PACKET + "PacketValueConfig", name, value);
        ExpandedGuiApi.call(config, "putSetting", setting, selected);
        sink.send(packet); result.addProperty("after", value);
    }
    private static void resource(Object screen, Object container, ItemStack cursor, JsonObject args, String action, JsonObject result, PacketSink sink) {
        AeDataAdapter.require(screen, GUI + "GuiMEMonitorable"); AeDataAdapter.require(container, CONTAINER + "ContainerMEMonitorable");
        Object target = null; String nativeAction;
        if (action.equals("resource_deposit")) {
            ExpandedGuiApi.keys(args, "mode");
            String mode = ExpandedGuiApi.option(args, "mode", "all", "all", "single");
            if (cursor == null) throw new BridgeException("invalid_state", "Deposit requires a real nonempty player cursor stack.");
            nativeAction = mode.equals("all") ? "PICKUP_OR_SET_DOWN" : "SPLIT_OR_PLACE_SINGLE";
            result.add("target", AeDataAdapter.actualItem(cursor));
        } else {
            ExpandedGuiApi.keys(args, "resourceId", "mode");
            String resourceId = ExpandedGuiApi.string(args, "resourceId", 128);
            target = AeDataAdapter.resolve(screen, container, resourceId);
            if (action.equals("craft_open")) {
                if (args.has("mode")) throw new BridgeException("invalid_args", "craft_open has no mode.");
                if (!(Boolean) ExpandedGuiApi.call(target, "isCraftable")) throw new BridgeException("invalid_state", "The exact current resource is not craftable.");
                nativeAction = "AUTO_CRAFT";
            } else {
                String mode = ExpandedGuiApi.option(args, "mode", "stack", "stack", "single", "half", "inventory");
                if (cursor != null) throw new BridgeException("invalid_state", "Resource take requires an empty cursor so it cannot accidentally deposit a held item.");
                if (!(Boolean) ExpandedGuiApi.call(target, "isItem")) throw new BridgeException("invalid_state", "Direct take supports item resources; non-item resources require their native containers.");
                if (((Number) ExpandedGuiApi.call(target, "getStackSize")).longValue() <= 0) throw new BridgeException("invalid_state", "No stored amount is currently available.");
                nativeAction = mode.equals("single") ? "PICKUP_SINGLE" : mode.equals("half") ? "SPLIT_OR_PLACE_SINGLE" : mode.equals("inventory") ? "SHIFT_CLICK" : "PICKUP_OR_SET_DOWN";
            }
            result.add("target", AeDataAdapter.stack(target));
        }
        Object nativeEnum = ExpandedGuiApi.enumeration(screen, "appeng.helpers.MonitorableAction", nativeAction);
        ExpandedGuiApi.call(container, "setTargetStack", target);
        Object packet = ExpandedGuiApi.create(screen, PACKET + "PacketMonitorableAction", nativeEnum, -1);
        sink.send(packet);
    }
    private static void typeFilter(Object screen, Object container, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "type", "enabled"); AeDataAdapter.require(screen, GUI + "GuiMEMonitorable");
        String type = ExpandedGuiApi.string(args, "type", 128);
        if (!args.has("enabled")) throw new BridgeException("invalid_args", "enabled is required.");
        boolean enabled = GuiDataAdapter.booleanArg(args, "enabled", false);
        Object filters = ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "typeFilters");
        if (filters == null) throw new BridgeException("invalid_state", "Current terminal does not expose resource type filters.");
        java.util.Map<?, ?> options = (java.util.Map<?, ?>) ExpandedGuiApi.call(filters, "getFiltersMap"); Object target = null;
        for (Object option : options.keySet()) if (type.equals(String.valueOf(ExpandedGuiApi.call(option, "getId")))) { target = option; break; }
        if (target == null) throw new BridgeException("not_found", "Type is absent from this terminal's current filter list.");
        Object copy = ExpandedGuiApi.create(screen, "appeng.util.AEStackTypeFilter", filters);
        ExpandedGuiApi.call(copy, "setEnabled", target, enabled);
        int windowId = container instanceof net.minecraft.inventory.Container ? ((net.minecraft.inventory.Container) container).windowId : ((Number) ExpandedGuiApi.field(container, container.getClass(), "windowId")).intValue();
        Object packet = ExpandedGuiApi.create(screen, PACKET + "PacketMonitorableTypeFilter", ExpandedGuiApi.call(copy, "getImmutableFilters"), windowId);
        ExpandedGuiApi.call(filters, "copyFrom", copy);
        ExpandedGuiApi.call(ExpandedGuiApi.field(screen, GUI + "GuiMEMonitorable", "repo"), "updateView");
        sink.send(packet); result.addProperty("type", type); result.addProperty("enabled", enabled);
    }
    private static void resourceContainer(Object screen, Object container, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "resourceId", "mode"); AeDataAdapter.require(screen, GUI + "GuiMEMonitorable");
        Object target = AeDataAdapter.resolve(screen, container, ExpandedGuiApi.string(args, "resourceId", 128));
        String mode = ExpandedGuiApi.option(args, "mode", null, "fill_single", "fill_all", "drain_single", "drain_all");
        String action = mode.equals("fill_single") ? "FILL_SINGLE_CONTAINER" : mode.equals("fill_all") ? "FILL_CONTAINERS" : mode.equals("drain_single") ? "DRAIN_SINGLE_CONTAINER" : "DRAIN_CONTAINERS";
        Object nativeAction = ExpandedGuiApi.enumeration(screen, "appeng.helpers.MonitorableAction", action);
        result.add("target", AeDataAdapter.stack(target)); result.addProperty("mode", mode);
        ExpandedGuiApi.call(container, "setTargetStack", target);
        Object packet = ExpandedGuiApi.create(screen, PACKET + "PacketMonitorableAction", nativeAction, -1);
        sink.send(packet);
    }
    private static void craftRequest(Object screen, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "amount", "mode", "lite", "autoStart", "follow"); AeDataAdapter.require(screen, GUI + "GuiCraftAmount");
        long amount = ExpandedGuiApi.amount(args, "amount");
        String mode = args.has("mode") ? ExpandedGuiApi.string(args, "mode", 64) : "STANDARD";
        Object nativeMode = ExpandedGuiApi.enumeration(screen, "appeng.api.config.CraftingMode", mode);
        boolean lite = GuiDataAdapter.booleanArg(args, "lite", false), start = GuiDataAdapter.booleanArg(args, "autoStart", false), follow = GuiDataAdapter.booleanArg(args, "follow", false);
        if (follow && !start) throw new BridgeException("invalid_args", "follow requires autoStart.");
        sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketCraftRequest", amount, start && !follow, start && follow, nativeMode, lite));
        result.addProperty("amount", Long.toString(amount)); result.addProperty("mode", mode); result.addProperty("autoStart", start); result.addProperty("follow", follow);
    }
    private static void craftingOutput(Object screen, Object container, ItemStack cursor, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "slot", "mode");
        AeDataAdapter.require(screen, "appeng.client.gui.AEBaseGui");
        AeDataAdapter.require(container, "appeng.container.AEBaseContainer");
        if (!(container instanceof Container)) throw new BridgeException("unsupported", "Current AE container lacks the native Minecraft slot list.");
        Container nativeContainer = (Container) container;
        List<?> slots = nativeContainer.inventorySlots;
        if (slots.size() > 1024) throw new BridgeException("too_large", "AE container exceeds the validated native slot bound.");
        int index = args.has("slot") ? JsonData.integer(args, "slot", 0, 1023) : -1;
        if (index >= slots.size()) throw new BridgeException("stale_slot", "Crafting output slot is absent from the current container.");
        if (index < 0) {
            for (int i = 0; i < slots.size(); i++) if (ExpandedGuiApi.is(slots.get(i), "appeng.container.slot.SlotCraftingTerm")) {
                if (index >= 0) throw new BridgeException("invalid_state", "Multiple native crafting outputs are present; supply the current output slot explicitly.");
                index = i;
            }
            if (index < 0) throw new BridgeException("unsupported", "Current AE container has no native SlotCraftingTerm output.");
        }
        Object value = slots.get(index);
        if (!(value instanceof Slot) || !ExpandedGuiApi.is(value, "appeng.container.slot.SlotCraftingTerm"))
            throw new BridgeException("invalid_state", "Selected slot is not a native AE crafting output.");
        Slot slot = (Slot) value;
        if (slot.slotNumber != index) throw new BridgeException("stale_slot", "Native output slot number no longer matches its current container index.");
        ItemStack output = slot.getStack();
        if (output == null || output.stackSize <= 0) throw new BridgeException("invalid_state", "The native crafting output currently has no recipe result.");
        String mode = ExpandedGuiApi.option(args, "mode", "single", "single", "stack", "inventory");
        String action = mode.equals("inventory") ? "CRAFT_SHIFT" : mode.equals("stack") ? "CRAFT_STACK" : "CRAFT_ITEM";
        int attempts = mode.equals("single") ? 1 : output.getMaxStackSize() / output.stackSize;
        if (attempts < 1) throw new BridgeException("invalid_state", "Native stack crafting has no complete recipe execution within one output stack.");
        long maxOutput = (long) attempts * output.stackSize;
        if (!mode.equals("inventory") && cursor != null &&
                (!cursor.isItemEqual(output) || !ItemStack.areItemStackTagsEqual(cursor, output) || (long) cursor.stackSize + maxOutput > cursor.getMaxStackSize()))
            throw new BridgeException("invalid_state", "The current real cursor cannot accept the native crafting output amount.");
        result.addProperty("slot", index); result.addProperty("windowId", nativeContainer.windowId);
        result.addProperty("slotClass", slot.getClass().getName()); result.addProperty("mode", mode);
        result.addProperty("nativeAction", action); result.addProperty("destination", mode.equals("inventory") ? "player_inventory" : "cursor");
        result.add("outputBefore", AeDataAdapter.actualItem(output));
        result.add("cursorBefore", cursor == null ? com.google.gson.JsonNull.INSTANCE : AeDataAdapter.actualItem(cursor));
        result.addProperty("maxRecipeExecutions", attempts); result.addProperty("maxOutputCount", Long.toString(maxOutput));
        result.addProperty("quantitySemantics", "Native upper bound only; actual ingredients, storage, energy and destination capacity are validated by AE on the server.");
        result.addProperty("serverConfirmed", false);
        Object nativeAction = ExpandedGuiApi.enumeration(screen, "appeng.helpers.InventoryAction", action);
        sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketInventoryAction", nativeAction, index, 0L));
    }
    private static void patternSet(Object screen, Object container, ItemStack[] inventory, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "side", "slot", "amount", "resourceId", "playerSlot", "clear");
        AeDataAdapter.require(screen, GUI + "GuiPatternTerm");
        String side = ExpandedGuiApi.option(args, "side", null, "input", "output");
        Object storage = AeDataAdapter.patternInventory(container, side); int size = ((Number) ExpandedGuiApi.call(storage, "getSizeInventory")).intValue();
        int slot = JsonData.integer(args, "slot", 0, Math.min(1023, size - 1));
        boolean clear = GuiDataAdapter.booleanArg(args, "clear", false);
        if (args.has("clear") && !clear) throw new BridgeException("invalid_args", "Use clear=true without a source, or omit clear for a sourced edit.");
        int sources = (args.has("resourceId") ? 1 : 0) + (args.has("playerSlot") ? 1 : 0);
        if ((clear && (sources != 0 || args.has("amount"))) || (!clear && sources != 1)) throw new BridgeException("invalid_args", "Choose exactly one resourceId/playerSlot source and amount, or clear=true without a source/amount.");
        Object stack = null;
        if (!clear) {
            long amount = ExpandedGuiApi.amount(args, "amount");
            if (args.has("resourceId")) stack = AeDataAdapter.resolve(screen, container, ExpandedGuiApi.string(args, "resourceId", 128));
            else {
                int index = JsonData.integer(args, "playerSlot", 0, 35);
                if (inventory == null || index >= inventory.length || inventory[index] == null) throw new BridgeException("not_found", "Current player inventory source slot is empty.");
                stack = ExpandedGuiApi.call(ExpandedGuiApi.load(screen, "appeng.util.item.AEItemStack"), "create", inventory[index]);
            }
            stack = ExpandedGuiApi.call(stack, "copy"); ExpandedGuiApi.call(stack, "setStackSize", amount);
            boolean crafting = (Boolean) ExpandedGuiApi.call(container, "isCraftingMode");
            if (crafting && (side.equals("output") || amount != 1 || !(Boolean) ExpandedGuiApi.call(stack, "isItem"))) throw new BridgeException("invalid_state", "Crafting mode accepts only unit item inputs; its output is derived by the native recipe.");
        }
        Object kind = ExpandedGuiApi.enumeration(screen, "appeng.api.storage.StorageName", side.equals("input") ? "CRAFTING_INPUT" : "CRAFTING_OUTPUT");
        Object packet = ExpandedGuiApi.create(screen, PACKET + "PacketPatternValueSet", stack, kind, slot, true);
        result.addProperty("side", side); result.addProperty("slot", slot); result.add("target", stack == null ? com.google.gson.JsonNull.INSTANCE : AeDataAdapter.stack(stack));
        sink.send(packet);
    }
    private static void cellConfig(Object screen, Object container, ItemStack cursor, ItemStack[] inventory, JsonObject args, String action, JsonObject result) {
        boolean clear = action.equals("cell_config_clear");
        if (clear) ExpandedGuiApi.keys(args, "slot");
        else ExpandedGuiApi.keys(args, "slot", "resourceId", "playerSlot", "cursor", "source");
        AeDataAdapter.require(screen, GUI + "GuiCellWorkbench");
        Object sync = AeDataAdapter.cellConfigSync(container), storage = ExpandedGuiApi.call(sync, "get");
        int size = ((Number) ExpandedGuiApi.call(storage, "getSizeInventory")).intValue();
        if (size < 1 || size > 63) throw new BridgeException("unsupported", "CellWorkbench config inventory exceeds the validated native slot range.");
        int slot = JsonData.integer(args, "slot", 0, size - 1);
        Object acceptedType = ExpandedGuiApi.call(container, "getStackType");
        if (!(Boolean) ExpandedGuiApi.call(container, "haveCell") || acceptedType == null) throw new BridgeException("invalid_state", "Insert a recognized cell with a current native AEStack type before editing its configuration.");
        requireVisibleCellConfigSlot(screen, storage, slot);
        Object stack = null;
        if (!clear) {
            int sources = (args.has("resourceId") ? 1 : 0) + (args.has("playerSlot") ? 1 : 0) + (args.has("cursor") ? 1 : 0) + (args.has("source") ? 1 : 0);
            if (sources != 1) throw new BridgeException("invalid_args", "Choose exactly one current resourceId, playerSlot, cursor=true or source ghost template.");
            if (args.has("resourceId")) {
                stack = ExpandedGuiApi.call(AeDataAdapter.resolve(screen, container, ExpandedGuiApi.string(args, "resourceId", 128)), "copy");
                ExpandedGuiApi.call(stack, "setStackSize", 1L);
                result.addProperty("source", "resourceId");
            } else {
                ItemStack source;
                if (args.has("source")) {
                    if (!args.get("source").isJsonObject()) throw new BridgeException("invalid_args", "source must be a ghost template object.");
                    source = GhostItemSource.resolve(screen, args.getAsJsonObject("source"), inventory);
                    result.addProperty("source", "ghost_template");
                } else if (args.has("playerSlot")) {
                    int index = JsonData.integer(args, "playerSlot", 0, 35);
                    if (inventory == null || index >= inventory.length) throw new BridgeException("not_found", "Current player inventory source slot is unavailable.");
                    source = inventory[index]; result.addProperty("source", "playerSlot"); result.addProperty("playerSlot", index);
                } else {
                    if (!GuiDataAdapter.booleanArg(args, "cursor", false)) throw new BridgeException("invalid_args", "Cursor source must be cursor=true.");
                    source = cursor; result.addProperty("source", "cursor");
                }
                if (source == null || source.stackSize <= 0) throw new BridgeException("not_found", "Current real ItemStack source is empty.");
                stack = cellConfigFromItem(screen, acceptedType, source);
            }
            AeDataAdapter.require(stack, AeDataAdapter.STACK);
            if (ExpandedGuiApi.call(stack, "getStackType") != acceptedType) throw new BridgeException("invalid_state", "Source AEStack type is not accepted by the current inserted cell.");
            stack = ExpandedGuiApi.call(stack, "copy");
            if (((Number) ExpandedGuiApi.call(stack, "getStackSize")).longValue() <= 0) throw new BridgeException("invalid_state", "Native source conversion returned an empty configuration resource.");
        }
        Object before = ExpandedGuiApi.call(storage, "getAEStackInSlot", slot);
        result.addProperty("slot", slot); result.addProperty("acceptedType", String.valueOf(ExpandedGuiApi.call(acceptedType, "getId")));
        result.add("before", before == null ? com.google.gson.JsonNull.INSTANCE : AeDataAdapter.stack(before));
        result.add("target", stack == null ? com.google.gson.JsonNull.INSTANCE : AeDataAdapter.stack(stack));
        Object syncManager = ExpandedGuiApi.call(container, "getSyncManager");
        if (syncManager == null) throw new BridgeException("unsupported", "Current CellWorkbench has no native synchronization manager.");
        Object delta = ExpandedGuiApi.create(screen, "appeng.container.sync.deltas.AEStackInventoryDelta", slot, stack);
        ExpandedGuiApi.call(sync, "applyAndQueueDelta", delta);
        // Use the same public flush as AEBaseGui.updateScreen before another sequence action can remove the cell.
        ExpandedGuiApi.call(syncManager, "flushSync");
        result.addProperty("nativeProtocol", "CellWorkbench configSync.applyAndQueueDelta(AEStackInventoryDelta); getSyncManager().flushSync()");
        result.addProperty("syncFlushInvoked", true); result.addProperty("serverConfirmed", false);
        result.addProperty("configurationOnly", true); result.addProperty("inventoryConsumed", false);
        result.addProperty("serverAcceptance", "pending; read cell_config again after native synchronization");
        if (args.has("source")) result.add("ghostRelease", NeiGhostDragAdapter.afterMark(screen));
    }
    private static Object cellConfigFromItem(Object screen, Object acceptedType, ItemStack source) {
        // Match a normal left click on this showAmount=false phantom slot, preserving the real source NBT.
        ItemStack hand = source.copy(); hand.stackSize = 1;
        Object stack = ExpandedGuiApi.call(acceptedType, "convertStackFromItem", hand);
        if (stack != null) return stack;
        Object itemType = ExpandedGuiApi.field(null, ExpandedGuiApi.load(screen, "appeng.util.item.AEItemStackType"), "ITEM_STACK_TYPE");
        if (acceptedType == itemType) return ExpandedGuiApi.call(ExpandedGuiApi.load(screen, "appeng.util.item.AEItemStack"), "create", hand);
        stack = ExpandedGuiApi.call(acceptedType, "getStackFromContainerItem", hand);
        if (stack == null) throw new BridgeException("invalid_state", "The real source ItemStack has no native conversion or container content accepted by this cell type.");
        return stack;
    }
    private static void requireVisibleCellConfigSlot(Object screen, Object storage, int index) {
        Object source = ExpandedGuiApi.field(screen, "appeng.client.gui.AEBaseGui", "virtualSlots");
        if (!(source instanceof List<?>) || ((List<?>) source).size() > 64) throw new BridgeException("unsupported", "Current CellWorkbench virtual slot list is unavailable or exceeds its validated bound.");
        Object name = ExpandedGuiApi.call(storage, "getStorageName"), found = null;
        for (Object value : (List<?>) source) {
            if (!ExpandedGuiApi.is(value, "appeng.client.gui.slots.VirtualMEPhantomSlot") || ((Number) ExpandedGuiApi.call(value, "getSlotIndex")).intValue() != index || !name.equals(ExpandedGuiApi.call(value, "getStorageName"))) continue;
            if (found != null) throw new BridgeException("unsupported", "Config index maps to multiple native phantom slots.");
            found = value;
        }
        if (found == null) throw new BridgeException("unsupported", "Config index has no current native phantom slot.");
        if ((Boolean) ExpandedGuiApi.call(found, "isHidden")) throw new BridgeException("invalid_state", "Current native CellWorkbench configuration slot is hidden.");
    }
    private static void patternMode(Object screen, Object container, JsonObject args, JsonObject result) {
        ExpandedGuiApi.keys(args, "field", "value"); AeDataAdapter.require(screen, GUI + "GuiPatternTerm");
        String field = ExpandedGuiApi.option(args, "field", null, "crafting", "substitute", "beSubstitute", "inverted");
        boolean value = GuiDataAdapter.booleanArg(args, "value", false);
        if (!args.has("value")) throw new BridgeException("invalid_args", "value is required.");
        if (field.equals("crafting") && value && !(Boolean) ExpandedGuiApi.call(container, "getCraftingModeSupport")) throw new BridgeException("invalid_state", "This extended processing terminal does not support crafting mode.");
        String owner = field.equals("inverted") ? CONTAINER + "ContainerPatternTermEx" : CONTAINER + "ContainerPatternTerm";
        Object sync = ExpandedGuiApi.field(container, owner, (field.equals("crafting") ? "craftingMode" : field) + "Sync");
        result.addProperty("before", (Boolean) ExpandedGuiApi.call(sync, "get")); ExpandedGuiApi.call(sync, "set", value);
        if (ExpandedGuiApi.is(screen, GUI + "GuiPatternTermEx")) ExpandedGuiApi.call(screen, "onUpdateInvertedOrActivePage");
        result.addProperty("after", value);
    }
    private static void patternMultiply(Object screen, Object container, JsonObject args, JsonObject result) {
        ExpandedGuiApi.keys(args, "factor"); AeDataAdapter.require(screen, GUI + "GuiPatternTerm");
        int factor = JsonData.integer(args, "factor", -8, 8);
        if (factor != -8 && factor != -2 && factor != 2 && factor != 8) throw new BridgeException("invalid_args", "factor must be -8/-2/2/8.");
        if ((Boolean) ExpandedGuiApi.call(container, "isCraftingMode")) throw new BridgeException("invalid_state", "Pattern multiplication applies to processing mode.");
        for (String side : new String[] {"input", "output"}) {
            Object inv = AeDataAdapter.patternInventory(container, side); int size = ((Number) ExpandedGuiApi.call(inv, "getSizeInventory")).intValue();
            if (size > 1024) throw new BridgeException("too_large", "Pattern inventory exceeds the validated slot bound.");
            for (int i = 0; i < size; i++) { Object stack = ExpandedGuiApi.call(inv, "getAEStackInSlot", i); if (stack != null) validateFactor(((Number) ExpandedGuiApi.call(stack, "getStackSize")).longValue(), factor); }
        }
        Object handler = ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTerm", "doubleAction");
        int nativeValue = (Math.abs(factor) == 8 ? 1 : 0) | (factor < 0 ? 2 : 0);
        ExpandedGuiApi.call(handler, "send", nativeValue); result.addProperty("factor", factor);
    }
    static void validateFactor(long amount, int factor) {
        if (amount < 0 || (factor > 0 && amount > Long.MAX_VALUE / factor) || (factor < 0 && amount % -factor != 0)) throw new BridgeException("invalid_state", "Pattern multiplication would overflow or division would lose a fractional amount.");
    }
    private static void patternPage(Object screen, Object container, JsonObject args, JsonObject result) {
        ExpandedGuiApi.keys(args, "page"); AeDataAdapter.require(screen, GUI + "GuiPatternTermEx");
        Object scroll = ExpandedGuiApi.field(screen, GUI + "GuiPatternTermEx", "processingScrollBar");
        // The exact RC1 extended terminal has a native 0..1 scrollbar for its two processing views.
        int page = JsonData.integer(args, "page", 0, 1);
        ExpandedGuiApi.call(ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTermEx", "activePageSync"), "set", page);
        ExpandedGuiApi.call(scroll, "setCurrentScroll", page); ExpandedGuiApi.call(screen, "onUpdateInvertedOrActivePage"); result.addProperty("page", page);
    }
    private static void patternAction(Object screen, Object container, JsonObject args, String action, JsonObject result) {
        AeDataAdapter.require(screen, GUI + "GuiPatternTerm");
        if (action.equals("pattern_clear")) { ExpandedGuiApi.keys(args); ExpandedGuiApi.call(ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTerm", "clearAction"), "send"); return; }
        ExpandedGuiApi.keys(args, "move", "wholeStack");
        boolean move = GuiDataAdapter.booleanArg(args, "move", false), whole = GuiDataAdapter.booleanArg(args, "wholeStack", false);
        if (whole && !move) throw new BridgeException("invalid_args", "wholeStack requires move.");
        Object handler = ExpandedGuiApi.field(container, CONTAINER + "ContainerPatternTerm", move ? "encodeAndMoveToInventoryAction" : "encodeAction");
        if (move) ExpandedGuiApi.call(handler, "send", whole); else ExpandedGuiApi.call(handler, "send");
        result.addProperty("move", move); result.addProperty("wholeStack", whole);
    }
    private static void previewStart(Object screen, Object container, JsonObject args, PacketSink sink) {
        ExpandedGuiApi.keys(args, "follow"); AeDataAdapter.require(screen, GUI + "GuiCraftConfirm");
        boolean follow = GuiDataAdapter.booleanArg(args, "follow", false);
        if (!AeDataAdapter.readyPreview(container)) throw new BridgeException("invalid_state", "Preview is a simulation, still pending or has no suitable selected CPU.");
        sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketValueConfig", follow ? "Terminal.StartWithFollow" : "Terminal.Start", "Start"));
    }
    private static void cpuSelect(Object screen, JsonObject args, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "serial");
        if (!ExpandedGuiApi.is(screen, GUI + "GuiCraftConfirm") && !ExpandedGuiApi.is(screen, GUI + "GuiCraftingStatus")) throw new BridgeException("unsupported", "This screen has no CPU selection table.");
        int serial = JsonData.integer(args, "serial", -1, Integer.MAX_VALUE);
        Object table = AeDataAdapter.cpuTable(screen); boolean found = serial == -1;
        for (Object cpu : (List<?>) ExpandedGuiApi.call(table, "getCPUs")) if (((Number) ExpandedGuiApi.call(cpu, "getSerial")).intValue() == serial) { found = true; break; }
        if (!found) throw new BridgeException("not_found", "CPU serial is absent from the current synchronized CPU table.");
        sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketValueConfig", "CPUTable.Cpu.Set", Integer.toString(serial))); result.addProperty("serial", serial);
    }
    private static void job(Object screen, Object container, JsonObject args, String action, JsonObject result, PacketSink sink) {
        ExpandedGuiApi.keys(args, "expectedCpuSerial", "paused"); AeDataAdapter.require(screen, GUI + "GuiCraftingStatus");
        if (action.equals("job_cancel") && args.has("paused")) throw new BridgeException("invalid_args", "job_cancel has no paused argument.");
        int serial = JsonData.integer(args, "expectedCpuSerial", 0, Integer.MAX_VALUE);
        Object table = AeDataAdapter.cpuTable(screen);
        int actual = ((Number) ExpandedGuiApi.field(table, CONTAINER + "ContainerCPUTable", "selectedCpuSerial")).intValue();
        Object selected = ExpandedGuiApi.call(table, "getSelectedCPU");
        if (actual != serial || selected == null || !(Boolean) ExpandedGuiApi.call(selected, "isBusy")) throw new BridgeException("stale_job", "Expected selected busy CPU does not match current synchronized state.");
        if (action.equals("job_pause")) {
            if (!args.has("paused")) throw new BridgeException("invalid_args", "paused is required.");
            boolean desired = GuiDataAdapter.booleanArg(args, "paused", false);
            boolean current = (Boolean) ExpandedGuiApi.field(container, CONTAINER + "ContainerCraftingCPU", "cachedSuspend");
            if (current == desired) { result.addProperty("unchanged", true); return; }
            sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketValueConfig", "TileCrafting.Suspend", "Suspend")); result.addProperty("requestedPaused", desired);
        } else sink.send(ExpandedGuiApi.create(screen, PACKET + "PacketValueConfig", "TileCrafting.Cancel", "Cancel"));
        result.addProperty("expectedCpuSerial", serial);
    }
    public static void send(Object screen, Object packet) {
        Class<?> network = ExpandedGuiApi.load(screen, "appeng.core.sync.network.NetworkHandler");
        Object instance = ExpandedGuiApi.field(null, network, "instance"); ExpandedGuiApi.call(instance, "sendToServer", packet);
    }
}
