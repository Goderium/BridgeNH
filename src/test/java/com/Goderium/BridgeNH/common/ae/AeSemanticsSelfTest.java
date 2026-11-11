package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.client.gui.implementations.*;
import appeng.container.implementations.*;
import appeng.core.sync.packets.*;
import appeng.container.AEBaseContainer;
import appeng.container.slot.SlotCraftingTerm;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.nbt.NBTTagCompound;

/** Public AE API/state fixtures exercise real adapters; Minecraft/server operations remain mocked. */
public class AeSemanticsSelfTest {
    private static int checks;
    public static int run() { checks = 0; requests(); terminal(); pattern(); craftingOutput(); previewPending(); previewCpuInterfaces(); validation(); AeDataAdapter.invalidate(); return checks; }
    private static void requests() {
        com.glodblock.github.client.gui.GuiLevelTerminal gui = new com.glodblock.github.client.gui.GuiLevelTerminal();
        com.glodblock.github.client.gui.GuiLevelTerminal.LevelTerminalEntry entry = new com.glodblock.github.client.gui.GuiLevelTerminal.LevelTerminalEntry();
        entry.id = 9007199254740993L; entry.x = 97; entry.infoList = new appeng.api.features.LevelItemInfo[65];
        appeng.api.features.LevelItemInfo info = new appeng.api.features.LevelItemInfo(); info.quantity = Long.MAX_VALUE; info.batchSize = 9007199254740993L;
        info.stack = new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), icon(), "request-full-NBT", 17); entry.infoList[0] = info;
        gui.masterList.list.put(entry.id, entry);
        com.glodblock.github.client.gui.GuiLevelTerminal.LevelTerminalEntry pending = new com.glodblock.github.client.gui.GuiLevelTerminal.LevelTerminalEntry(); pending.id = 2;
        gui.masterList.list.put(pending.id, pending);
        JsonObject args = readArgs(); args.addProperty("limit", 1);
        JsonObject first = AeDataAdapter.read(gui, null, args, generousStart());
        check(first.get("scope").getAsString().equals("requests") && first.get("hasMore").getAsBoolean()
                && first.getAsJsonArray("entries").get(0).getAsJsonObject().get("slotsPending").getAsBoolean(), "Native request list distinguishes pending slots and pages by stable long provider identity.");
        args.addProperty("offset", first.get("nextOffset").getAsInt());
        JsonObject page = AeDataAdapter.read(gui, null, args, generousStart()), row = page.getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject slot = row.getAsJsonArray("slots").get(0).getAsJsonObject();
        check(row.get("providerId").getAsString().equals("9007199254740993") && slot.get("quantity").getAsString().equals(Long.toString(Long.MAX_VALUE))
                && slot.get("batchSize").getAsString().equals("9007199254740993"), "Request quantities and provider IDs preserve exact decimal longs.");
        check(row.get("slotsTruncated").getAsBoolean() && row.getAsJsonArray("slots").size() == 64
                && row.getAsJsonArray("slots").get(1).getAsJsonObject().get("resource").isJsonNull(), "Request list bounds slots and preserves real empty positions.");
        check(!page.getAsJsonObject("state").get("nativeEntryCraftSupported").getAsBoolean()
                && slot.getAsJsonObject("resource").getAsJsonObject("item").get("nbt").getAsString().contains("actual NBT"), "Unsupported native entry crafting remains explicit while resource NBT is retained.");
    }
    private static void craftingOutput() {
        GuiCraftingTerm gui = new GuiCraftingTerm(); AEBaseContainer container = new AEBaseContainer(); container.windowId = 23;
        ItemStack output = icon(); output.stackSize = 2;
        Slot ordinary = new Slot(new InventoryBasic("ordinary fixture", false, 1), 0, 0, 0);
        container.append(ordinary); SlotCraftingTerm craft = new SlotCraftingTerm(output); container.append(craft);
        List<Object> packets = new ArrayList<Object>(); JsonObject single = action("crafting_output");
        JsonObject receipt = AeControlAdapter.perform(gui, container, null, null, single, packets::add);
        PacketInventoryAction packet = (PacketInventoryAction) packets.get(0);
        check(!craft.canTakeStack(null) && packet.action.name().equals("CRAFT_ITEM") && packet.slot == 1 && packet.id == 0L, "native output refuses vanilla pickup and sends one normal AE crafting packet to the real slot");
        check(receipt.get("windowId").getAsInt() == 23 && receipt.get("destination").getAsString().equals("cursor") && receipt.get("maxRecipeExecutions").getAsInt() == 1, "default single mode reports current window and one native recipe execution upper bound");
        check(!receipt.get("serverConfirmed").getAsBoolean() && receipt.get("maxOutputCount").getAsString().equals("2") && output.stackSize == 2, "submission receipt does not invent server completion or mutate output prediction");
        ItemStack compatibleCursor = output.copy(); compatibleCursor.stackSize = 62;
        AeControlAdapter.perform(gui, container, compatibleCursor, null, single, packets::add);
        check(packets.size() == 2 && compatibleCursor.stackSize == 62, "compatible cursor with exact capacity is preserved for native server validation");
        compatibleCursor.stackSize = 63;
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, compatibleCursor, null, single, packets::add));
        ItemStack wrongNbt = output.copy(); wrongNbt.getTagCompound().setString("fixture", "different NBT");
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, wrongNbt, null, single, packets::add));
        JsonObject stack = action("crafting_output"); stack.addProperty("mode", "stack"); stack.addProperty("slot", 1);
        receipt = AeControlAdapter.perform(gui, container, null, null, stack, packets::add);
        check(((PacketInventoryAction) packets.get(2)).action.name().equals("CRAFT_STACK") && receipt.get("maxRecipeExecutions").getAsInt() == 32 && receipt.get("maxOutputCount").getAsString().equals("64"), "stack mode uses native whole-output-stack semantics without an arbitrary requested quantity");
        JsonObject inventory = action("crafting_output"); inventory.addProperty("mode", "inventory");
        receipt = AeControlAdapter.perform(gui, container, wrongNbt, null, inventory, packets::add);
        check(((PacketInventoryAction) packets.get(3)).action.name().equals("CRAFT_SHIFT") && receipt.get("destination").getAsString().equals("player_inventory"), "inventory mode uses native shift action and does not overwrite an occupied cursor");
        JsonObject invalid = action("crafting_output"); invalid.addProperty("slot", 0);
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, null, null, invalid, packets::add));
        invalid.addProperty("slot", 2); expect("stale_slot", () -> AeControlAdapter.perform(gui, container, null, null, invalid, packets::add));
        invalid.addProperty("slot", 1); invalid.addProperty("amount", "1");
        expect("invalid_args", () -> AeControlAdapter.perform(gui, container, null, null, invalid, packets::add));
        craft.slotNumber = 0; expect("stale_slot", () -> AeControlAdapter.perform(gui, container, null, null, single, packets::add)); craft.slotNumber = 1;
        craft.putStack(null); expect("invalid_state", () -> AeControlAdapter.perform(gui, container, null, null, single, packets::add)); craft.putStack(output);
        container.append(new SlotCraftingTerm(output));
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, null, null, single, packets::add));
        expect("unsupported", () -> AeControlAdapter.perform(gui, new AEBaseContainer(), null, null, single, packets::add));
        expect("unsupported", () -> AeControlAdapter.perform(new GuiCraftAmount(), container, null, null, single, packets::add));
        check(packets.size() == 4, "empty, wrong, stale, ambiguous and unsupported outputs never send additional crafting mutations");
    }
    private static void previewPending() {
        GuiCraftConfirm gui = new GuiCraftConfirm(); ContainerCraftConfirm container = new ContainerCraftConfirm();
        AeGuiFixtures.Stack target = new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), icon(), "pending target identity", 2);
        container.requested = target; container.cpuTable.cpus.add(new CraftingCPUStatus()); gui.pending.add(target);
        JsonObject data = AeDataAdapter.read(gui, container, readArgs(), generousStart());
        check(data.getAsJsonObject("state").get("requestedPending").getAsBoolean() && data.getAsJsonObject("state").get("requested").isJsonNull() && !data.getAsJsonObject("state").get("startReady").getAsBoolean(), "new preview explicitly reports an unsynchronized native target as pending");
        check(container.targetReads == 0 && data.getAsJsonArray("entries").size() == 1, "empty native buffer is not decoded and already synchronized plan entries remain readable");
        List<Object> packets = new ArrayList<Object>();
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, null, null, action("preview_start"), packets::add));
        check(container.targetReads == 0 && packets.isEmpty(), "pending preview cannot start or trigger a decoder error");
        container.serializedItemToCraft = "synchronized fixture payload";
        data = AeDataAdapter.read(gui, container, readArgs(), generousStart());
        check(!data.getAsJsonObject("state").get("requestedPending").getAsBoolean() && data.getAsJsonObject("state").get("startReady").getAsBoolean() && container.targetReads == 1, "later read naturally becomes startReady without repeating a craft request");
        AeControlAdapter.perform(gui, container, null, null, action("preview_start"), packets::add);
        check(packets.size() == 1, "synchronized preview sends the start mutation once");
        container.corruptTarget = true;
        expect("adapter_error", () -> AeDataAdapter.read(gui, container, readArgs(), generousStart()));
        expect("adapter_error", () -> AeControlAdapter.perform(gui, container, null, null, action("preview_start"), packets::add));
        check(packets.size() == 1, "nonempty corrupt native payload is a real failure and never masquerades as pending or sends start");
    }
    private static void terminal() {
        GuiMEMonitorable gui = new GuiMEMonitorable(); ContainerMEMonitorable container = new ContainerMEMonitorable();
        AeGuiFixtures.Type itemType = new AeGuiFixtures.Type("item"), fluidType = new AeGuiFixtures.Type("fluid");
        ItemStack icon = icon();
        AeGuiFixtures.Stack first = new AeGuiFixtures.Stack(itemType, icon, "same registry/meta, distinct NBT A", 9007199254740993L); first.craftable = true;
        AeGuiFixtures.Stack second = new AeGuiFixtures.Stack(itemType, icon, "same registry/meta, distinct NBT B", Long.MAX_VALUE);
        AeGuiFixtures.Stack fluid = new AeGuiFixtures.Stack(fluidType, icon, "same icon, fluid type", 2000);
        gui.repo.view.add(first); gui.repo.view.add(second); gui.repo.view.add(fluid);
        gui.typeFilters.values.put(itemType, true); gui.typeFilters.values.put(fluidType, true);
        JsonObject args = readArgs(); args.addProperty("limit", 1);
        JsonObject data = AeDataAdapter.read(gui, container, args, generousStart());
        JsonObject entry = data.getAsJsonArray("entries").get(0).getAsJsonObject(); String firstId = entry.get("resourceId").getAsString();
        check(entry.get("amount").getAsString().equals("9007199254740993"), "quantity above JavaScript integer precision stays decimal string");
        check(entry.get("craftable").getAsBoolean() && entry.get("identityNbt").getAsString().contains("NBT A"), "craftable and normalized real NBT identity retained");
        check(icon.hasTagCompound() && first.amount == 9007199254740993L && first.craftable, "read normalizes a copy, never changes the actual resource");
        check(data.get("nextOffset").getAsInt() == 1 && data.get("hasMore").getAsBoolean(), "terminal view pagination reports continuation");
        args.addProperty("offset", 1); data = AeDataAdapter.read(gui, container, args, generousStart());
        String secondId = data.getAsJsonArray("entries").get(0).getAsJsonObject().get("resourceId").getAsString();
        check(!firstId.equals(secondId), "identical icons/metadata with distinct NBT get distinct resource IDs");
        gui.repo.view.set(0, second); gui.repo.view.set(1, first);
        args.addProperty("offset", 1); data = AeDataAdapter.read(gui, container, args, generousStart());
        check(data.getAsJsonArray("entries").get(0).getAsJsonObject().get("resourceId").getAsString().equals(firstId), "resource ID survives live sorting by canonical type/NBT");
        List<Object> packets = new ArrayList<Object>(); final JsonObject take = action("resource_take"); take.addProperty("resourceId", firstId); take.addProperty("mode", "single");
        AeGuiFixtures.events.clear();
        AeControlAdapter.perform(gui, container, null, null, take, packet -> { AeGuiFixtures.events.add("action_sent"); packets.add(packet); });
        check(container.target == first && packets.size() == 1 && ((PacketMonitorableAction) packets.get(0)).action.name().equals("PICKUP_SINGLE"), "semantic take targets exact live AEStack rather than hover/index");
        check(container.targetPayload.getString("identity").contains("NBT A") && container.targetPayload.getLong("count") == 9007199254740993L, "target serialization carries original NBT identity and exact original long amount");
        check(AeGuiFixtures.events.equals(java.util.Arrays.asList("target_serialized", "action_constructed", "action_sent")), "native target serialization precedes action construction and sending");
        int beforeChanges = container.targetChanges; gui.repo.view.remove(first);
        expect("stale_resource", () -> AeControlAdapter.perform(gui, container, null, null, take, packets::add));
        check(packets.size() == 1 && container.targetChanges == beforeChanges, "stale identity cannot send or change the selected target");
        take.addProperty("resourceId", secondId);
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, icon, null, take, packets::add));
        check(packets.size() == 1, "occupied cursor cannot turn take into accidental deposit");
        JsonObject action = action("terminal_search"); action.addProperty("text", "@fixture nbt");
        AeControlAdapter.perform(gui, container, null, null, action, packets::add);
        check(gui.searchField.text.equals("@fixture nbt") && gui.searchField.edits == 1, "search uses actual native field change callback");
        action = action("terminal_type_filter"); action.addProperty("type", "fluid"); action.addProperty("enabled", false);
        AeControlAdapter.perform(gui, container, null, null, action, packets::add);
        PacketMonitorableTypeFilter filter = (PacketMonitorableTypeFilter) packets.get(packets.size() - 1);
        check(filter.windowId == 17 && !filter.filters.get(fluidType) && gui.repo.updates == 1, "typed filter is native window-bound packet with optimistic view update");
        args = readArgs(); data = AeDataAdapter.read(gui, container, args, generousStart());
        String fluidId = data.getAsJsonArray("entries").get(1).getAsJsonObject().get("resourceId").getAsString();
        action = action("resource_container"); action.addProperty("resourceId", fluidId); action.addProperty("mode", "fill_single");
        AeGuiFixtures.events.clear();
        AeControlAdapter.perform(gui, container, null, null, action, packet -> { AeGuiFixtures.events.add("action_sent"); packets.add(packet); });
        check(container.target == fluid && ((PacketMonitorableAction) packets.get(packets.size() - 1)).action.name().equals("FILL_SINGLE_CONTAINER"), "fluid remains typed resource during native container interaction");
        check(container.targetPayload.getString("StackType").equals("fluid") && container.targetPayload.getString("identity").equals("same icon, fluid type") && container.targetPayload.getLong("count") == 2000, "container interaction serialized actual typed fluid identity, never item icon identity");
        check(AeGuiFixtures.events.equals(java.util.Arrays.asList("target_serialized", "action_constructed", "action_sent")), "typed container interaction preserves native target/action packet order");
        action = action("terminal_setting"); action.addProperty("setting", "SORT_BY"); action.addProperty("value", "SIZE");
        AeControlAdapter.perform(gui, container, null, null, action, packets::add);
        check(((PacketValueConfig) packets.get(packets.size() - 1)).key.equals("SORT_BY"), "advertised terminal setting uses native config packet");
        String resource = secondId; final JsonObject wrong = action("resource_take"); wrong.addProperty("resourceId", resource);
        expect("stale_resource", () -> AeControlAdapter.perform(new GuiMEMonitorable(), container, null, null, wrong, packets::add));
        check(packets.size() == 4, "cross-screen identity never sends a packet");
    }
    private static void pattern() {
        GuiPatternTermEx gui = new GuiPatternTermEx(); ContainerPatternTermEx container = new ContainerPatternTermEx();
        AeGuiFixtures.Stack source = new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), icon(), "pattern original NBT", 16);
        container.inputsSync.inv.slots[3] = source; container.outputsSync.inv.slots[2] = source;
        JsonObject data = AeDataAdapter.read(gui, container, readArgs(), generousStart());
        check(data.getAsJsonArray("entries").size() == 7 && data.getAsJsonObject("state").getAsJsonObject("inputGeometry").get("pages").getAsInt() == 2, "all phantom storage slots include hidden page and empty slots");
        JsonObject hidden = data.getAsJsonArray("entries").get(3).getAsJsonObject();
        check(hidden.get("slot").getAsInt() == 3 && hidden.get("phantom").getAsBoolean() && hidden.getAsJsonObject("resource").get("amount").getAsString().equals("16"), "full storage indices preserve actual phantom quantity");
        String id = hidden.getAsJsonObject("resource").get("resourceId").getAsString(); List<Object> packets = new ArrayList<Object>();
        JsonObject set = action("pattern_set"); set.addProperty("side", "output"); set.addProperty("slot", 1); set.addProperty("resourceId", id); set.addProperty("amount", "9007199254740993");
        AeControlAdapter.perform(gui, container, null, null, set, packets::add);
        PacketPatternValueSet packet = (PacketPatternValueSet) packets.get(0);
        check(packet.onlyUpdate && packet.slot == 1 && packet.side.name().equals("CRAFTING_OUTPUT"), "pattern edit uses exact storage index without reopening/hover");
        check(((AeGuiFixtures.Stack) packet.stack).amount == 9007199254740993L && source.amount == 16 && packet.stack != source, "pattern amount uses exact long on a copy, retains original identity");
        JsonObject multiply = action("pattern_multiply"); multiply.addProperty("factor", -8);
        AeControlAdapter.perform(gui, container, null, null, multiply, packets::add);
        check(container.doubleAction.calls == 1 && container.doubleAction.payload.equals(3), "validated all-slot division sends original button-action bitfield");
        source.amount = 15;
        expect("invalid_state", () -> AeControlAdapter.perform(gui, container, null, null, multiply, packets::add));
        check(container.doubleAction.calls == 1, "fractional division refuses before mutation"); source.amount = 16;
        JsonObject mode = action("pattern_mode"); mode.addProperty("field", "inverted"); mode.addProperty("value", true);
        AeControlAdapter.perform(gui, container, null, null, mode, packets::add);
        check(container.invertedSync.value && gui.visibilityUpdates == 1, "inversion uses synced state and native visibility update");
        JsonObject page = action("pattern_page"); page.addProperty("page", 1);
        AeControlAdapter.perform(gui, container, null, null, page, packets::add);
        check(container.activePageSync.value == 1 && gui.processingScrollBar.value == 1, "native extended processing page is synchronized");
        JsonObject encode = action("pattern_encode"); encode.addProperty("move", true); encode.addProperty("wholeStack", true);
        AeControlAdapter.perform(gui, container, null, null, encode, packets::add);
        check(container.encodeAndMoveToInventoryAction.calls == 1 && Boolean.TRUE.equals(container.encodeAndMoveToInventoryAction.payload), "encode-and-move is one native action with explicit whole-stack meaning");
        AeControlAdapter.perform(gui, container, null, null, action("pattern_clear"), packets::add);
        check(container.clearAction.calls == 1, "pattern clear sends native action once");
        set.addProperty("clear", false); expect("invalid_args", () -> AeControlAdapter.perform(gui, container, null, null, set, packets::add));
        check(packets.size() == 1, "redundant invalid selector cannot produce a second pattern edit");
        JsonObject clear = action("pattern_set"); clear.addProperty("side", "input"); clear.addProperty("slot", 2); clear.addProperty("clear", true);
        AeControlAdapter.perform(gui, container, null, null, clear, packets::add);
        check(((PacketPatternValueSet) packets.get(1)).stack == null, "explicit clear sends null only to the selected native phantom slot");
    }
    private static void previewCpuInterfaces() {
        AeGuiFixtures.Stack stack = new AeGuiFixtures.Stack(new AeGuiFixtures.Type("item"), icon(), "job identity", Long.MAX_VALUE);
        GuiCraftConfirm preview = new GuiCraftConfirm(); ContainerCraftConfirm confirm = new ContainerCraftConfirm();
        preview.storage.add(stack); preview.pending.add(stack); preview.missing.add(stack); confirm.requested = stack; confirm.serializedItemToCraft = "synchronized fixture payload"; confirm.cpuTable.cpus.add(new CraftingCPUStatus()); preview.table = new AeGuiFixtures.CpuTableHolder(confirm.cpuTable);
        JsonObject data = AeDataAdapter.read(preview, confirm, readArgs(), generousStart());
        check(data.getAsJsonArray("entries").size() == 3 && data.getAsJsonArray("entries").get(2).getAsJsonObject().get("section").getAsString().equals("missing"), "preview exports storage/pending/missing without screenshot or filtered visual list");
        check(data.getAsJsonObject("state").get("bytesUsed").getAsString().equals("9007199254740993"), "preview bytes preserve exact long");
        List<Object> packets = new ArrayList<Object>(); AeControlAdapter.perform(preview, confirm, null, null, action("preview_start"), packets::add);
        check(((PacketValueConfig) packets.get(0)).key.equals("Terminal.Start"), "ready preview starts once through original packet");
        confirm.simulation = true; expect("invalid_state", () -> AeControlAdapter.perform(preview, confirm, null, null, action("preview_start"), packets::add));
        check(packets.size() == 1, "simulation preview cannot start");
        GuiCraftingStatus cpu = new GuiCraftingStatus(); ContainerCraftingCPU cpuContainer = new ContainerCraftingCPU(); ContainerCPUTable table = new ContainerCPUTable();
        CraftingCPUStatus status = new CraftingCPUStatus(); status.crafting = stack; table.cpus.add(status); cpu.table = new AeGuiFixtures.CpuTableHolder(table); cpu.visualState.entries.put("entry", new AeGuiFixtures.JobEntry(stack));
        data = AeDataAdapter.read(cpu, cpuContainer, readArgs(), generousStart());
        check(data.getAsJsonArray("entries").size() == 2 && data.getAsJsonArray("entries").get(0).getAsJsonObject().get("remainingItems").getAsString().equals("42"), "CPU summary and current selected task state both exported");
        check(data.getAsJsonArray("entries").get(1).getAsJsonObject().get("stored").getAsString().equals("9007199254740993"), "CPU item stored/active/pending use exact longs");
        JsonObject pause = action("job_pause"); pause.addProperty("expectedCpuSerial", 7); pause.addProperty("paused", true);
        AeControlAdapter.perform(cpu, cpuContainer, null, null, pause, packets::add);
        check(((PacketValueConfig) packets.get(1)).key.equals("TileCrafting.Suspend"), "matching selected busy CPU permits native suspend");
        cpuContainer.cachedSuspend = true; JsonObject unchanged = AeControlAdapter.perform(cpu, cpuContainer, null, null, pause, packets::add);
        check(unchanged.get("unchanged").getAsBoolean() && packets.size() == 2, "desired pause state already synchronized never toggles again");
        pause.addProperty("expectedCpuSerial", 8); expect("stale_job", () -> AeControlAdapter.perform(cpu, cpuContainer, null, null, pause, packets::add));
        check(packets.size() == 2, "stale CPU serial cannot cancel or toggle a different task");
        GuiInterfaceTerminal interfaces = new GuiInterfaceTerminal(); GuiInterfaceTerminal.InterfaceTerminalEntry entry = new GuiInterfaceTerminal.InterfaceTerminalEntry(9007199254740993L); entry.inv.items[0] = icon(); interfaces.masterList.list.put(entry.id, entry);
        data = AeDataAdapter.read(interfaces, null, readArgs(), generousStart());
        JsonObject row = data.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(row.get("interfaceId").getAsString().equals("9007199254740993") && row.get("priority").getAsInt() == 41 && row.get("x").getAsInt() == 3, "interface inventory maps actual long ID, location and priority");
        check(row.getAsJsonArray("supportedTypes").size() == 2 && row.getAsJsonArray("slots").get(0).getAsJsonObject().getAsJsonObject("item").get("nbt").getAsString().contains("actual NBT"), "interface supported resource types and real encoded-item NBT preserved");
        JsonObject search = action("terminal_search"); search.addProperty("field", "inputs"); search.addProperty("text", "@gtnh fixture");
        AeControlAdapter.perform(interfaces, null, null, null, search, packets::add); check(interfaces.searchFieldInputs.edits == 1, "interface input search uses exact native field, not text coordinates");
        check(data.toString().getBytes(StandardCharsets.UTF_8).length < AeDataAdapter.BYTE_LIMIT, "interface DTO respects response budget");
    }
    private static void validation() {
        JsonObject args = action("craft_request"); args.addProperty("amount", Long.toString(Long.MAX_VALUE)); args.addProperty("autoStart", true); args.addProperty("follow", true);
        List<Object> packets = new ArrayList<Object>(); AeControlAdapter.perform(new GuiCraftAmount(), new Object(), null, null, args, packets::add);
        PacketCraftRequest packet = (PacketCraftRequest) packets.get(0);
        check(packet.amount == Long.MAX_VALUE && !packet.shift && packet.ctrl, "native craft request exact amount and explicit auto-start/follow flags");
        args.addProperty("amount", "9223372036854775808"); expect("invalid_args", () -> AeControlAdapter.perform(new GuiCraftAmount(), new Object(), null, null, args, packets::add));
        args.addProperty("amount", 10); expect("invalid_args", () -> AeControlAdapter.perform(new GuiCraftAmount(), new Object(), null, null, args, packets::add));
        check(packets.size() == 1, "bad long encodings never dispatch requests");
        expect("invalid_state", () -> AeControlAdapter.validateFactor(Long.MAX_VALUE, 2));
        for (long amount : new long[] {0, 1, 2, 16, 100000, Long.MAX_VALUE / 8}) { AeControlAdapter.validateFactor(amount, 8); check(true, "exact integer multiplication bound"); }
        JsonObject extra = readArgs(); extra.addProperty("arbitraryClass", "private.reflection.Target"); expect("invalid_args", () -> AeDataAdapter.read(new GuiMEMonitorable(), null, extra, generousStart()));
    }
    private static ItemStack icon() { ItemStack icon = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 1, 7); icon.setTagCompound(new NBTTagCompound()); icon.getTagCompound().setString("fixture", "actual NBT"); return icon; }
    private static JsonObject readArgs() { JsonObject args = new JsonObject(); args.addProperty("screenId", "AE fixture"); return args; }
    private static JsonObject action(String name) { JsonObject args = readArgs(); args.addProperty("action", name); return args; }
    private static long generousStart() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
}
