package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.nei.NeiViewAdapter;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.client.gui.implementations.GuiCellWorkbench;
import appeng.client.gui.slots.VirtualMEPhantomSlot;
import appeng.container.implementations.ContainerCellWorkbench;
import appeng.util.item.AEItemStackType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Actual adapter tests; fixtures cannot prove server acceptance, GUI timings or player interaction. */
public final class CellWorkbenchSelfTest {
    private static int checks, packets;
    public static void main(String[] args) {
        System.out.println("CellWorkbench directed fixtures passed: " + run()
            + " assertions; native networking, rendering and server acceptance are mocked.");
        System.out.println("Existing AE semantic fixtures passed: " + AeSemanticsSelfTest.run() + " assertions.");
    }
    public static int run() {
        checks = 0; packets = 0;
        paginationAndEmpty(); resourceIdentity(); realItemConversion(); ghostConfiguration(); rejectedActions(); clearOne(); immediateFlush();
        AeDataAdapter.invalidate(); return checks;
    }
    private static void paginationAndEmpty() {
        Fixture fixture = new Fixture(63, new CellWorkbenchFixtures.Type("fluid"));
        fixture.container.haveCell = false; fixture.container.acceptedType = null;
        JsonObject all = read(fixture, 0, 64);
        check(all.get("scope").getAsString().equals("cell_config") && !all.getAsJsonObject("state").get("haveCell").getAsBoolean(), "auto scope reads an empty workbench");
        check(all.getAsJsonObject("state").get("acceptedType").isJsonNull() && all.getAsJsonArray("entries").size() == 63, "null accepted type and all empty slots remain observable");
        for (int index = 0; index < 63; index++) {
            JsonObject entry = all.getAsJsonArray("entries").get(index).getAsJsonObject();
            check(entry.get("slot").getAsInt() == index && entry.get("resource").isJsonNull(), "empty slot identity " + index);
        }
        fixture.container.haveCell = true; fixture.container.acceptedType = fixture.type;
        fixture.container.configSync.inventory.slots[62] = stack(fixture.type, "last slot original NBT", 64000);
        fixture.slot(62).hidden = true;
        boolean[] seen = new boolean[63]; int offset = 0, pages = 0;
        JsonObject page;
        do {
            page = read(fixture, offset, 7); pages++;
            for (Object value : page.getAsJsonArray("entries")) {
                int index = ((com.google.gson.JsonElement) value).getAsJsonObject().get("slot").getAsInt();
                check(!seen[index], "paged slot appears exactly once " + index); seen[index] = true;
            }
            offset = page.get("nextOffset").getAsInt();
        } while (page.get("hasMore").getAsBoolean());
        check(pages == 9 && offset == 63 && seen[62], "all 63 config slots paginate without omission");
        JsonObject geometry = page.getAsJsonObject("state").getAsJsonArray("virtualSlots").get(62).getAsJsonObject();
        check(geometry.get("hidden").getAsBoolean() && geometry.get("slotIndex").getAsInt() == 62 && geometry.get("phantom").getAsBoolean(), "hidden slot remains readable with native geometry");
        check(page.getAsJsonArray("entries").get(6).getAsJsonObject().getAsJsonObject("resource").get("amount").getAsString().equals("64000"), "typed resource quantity is not flattened to an item count");
        check(read(fixture, 63, 64).getAsJsonArray("entries").size() == 0 && fixture.container.configSync.writes == 0, "past-end read and all inspection do not write");
    }
    private static void resourceIdentity() {
        Fixture fixture = new Fixture(63, new CellWorkbenchFixtures.Type("item"));
        char[] prefix = new char[4500]; Arrays.fill(prefix, 'x');
        String fullIdentity = new String(prefix) + " full NBT A";
        AeGuiFixtures.Stack source = stack(fixture.type, fullIdentity, 32);
        fixture.container.configSync.inventory.slots[0] = source;
        JsonObject original = read(fixture, 0, 64).getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("resource");
        check(original.get("identityTruncated").getAsBoolean(), "display truncation cannot shorten the resolver's full NBT identity");
        String resourceId = original.get("resourceId").getAsString();
        JsonObject action = action("cell_config_set", 1); action.addProperty("resourceId", resourceId);
        JsonObject receipt = perform(fixture, null, null, action);
        AeGuiFixtures.Stack result = (AeGuiFixtures.Stack) fixture.container.configSync.inventory.slots[1];
        check(fixture.container.configSync.writes == 1 && fixture.container.configSync.last.slotIndex == 1 && fixture.container.syncManager.flushes == 1 && fixture.container.syncManager.sentDeltas == 1 && packets == 0, "one public synchronized delta and one native flush, no separate action packet");
        check(result != source && result.amount == 1 && source.amount == 32 && result.nbtIdentity.equals(source.nbtIdentity), "resource source copied as a unit identity, original quantity and NBT retained");
        check(receipt.get("serverAcceptance").getAsString().startsWith("pending") && receipt.get("submitted").getAsBoolean() && receipt.get("syncFlushInvoked").getAsBoolean() && !receipt.get("serverConfirmed").getAsBoolean(), "native flush receipt never claims server confirmation");
        fixture.container.configSync.inventory.slots[0] = stack(fixture.type, new String(prefix) + " full NBT B", 32);
        fixture.container.configSync.inventory.slots[1] = null;
        expectNoWrite(fixture, "stale_resource", null, null, action);
        fixture.container.configSync.inventory.slots[0] = source;
        read(fixture, 0, 64);
        JsonObject staleScreen = action("cell_config_set", 1); staleScreen.addProperty("resourceId", resourceId); staleScreen.addProperty("screenId", "different screen ID");
        expectNoWrite(fixture, "stale_resource", null, null, staleScreen);
        read(fixture, 0, 64);
        String freshId = read(fixture, 0, 64).getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("resource").get("resourceId").getAsString();
        fixture.container.acceptedType = new CellWorkbenchFixtures.Type("item");
        JsonObject sameNameWrongType = action("cell_config_set", 1); sameNameWrongType.addProperty("resourceId", freshId);
        expectNoWrite(fixture, "invalid_state", null, null, sameNameWrongType);
    }
    private static void realItemConversion() {
        ItemStack source = item(); source.stackSize = 16;
        NBTTagCompound originalNbt = (NBTTagCompound) source.getTagCompound().copy();
        Fixture itemFixture = new Fixture(63, AEItemStackType.ITEM_STACK_TYPE);
        ItemStack[] inventory = new ItemStack[36]; inventory[35] = source;
        JsonObject put = action("cell_config_set", 62); put.addProperty("playerSlot", 35);
        perform(itemFixture, null, inventory, put);
        AeGuiFixtures.Stack itemResult = (AeGuiFixtures.Stack) itemFixture.container.configSync.inventory.slots[62];
        check(itemResult.amount == 1 && itemResult.nbtIdentity.contains("actual source NBT"), "native item factory fallback receives actual count-one source and full NBT");
        check(source.stackSize == 16 && source.getTagCompound().equals(originalNbt) && inventory[35] == source, "player inventory reference, count and NBT unchanged");
        CellWorkbenchFixtures.Type fluid = new CellWorkbenchFixtures.Type("fluid"); fluid.containerConversion = true; fluid.mutateHand = true;
        Fixture fluidFixture = new Fixture(63, fluid); JsonObject cursor = action("cell_config_set", 1); cursor.addProperty("cursor", true);
        perform(fluidFixture, source, null, cursor);
        AeGuiFixtures.Stack fluidResult = (AeGuiFixtures.Stack) fluidFixture.container.configSync.inventory.slots[1];
        check(fluidResult.amount == 64000 && fluidResult.type == fluid, "native real-container quantity remains 64000, not coerced to one unit");
        check(source.stackSize == 16 && source.getTagCompound().equals(originalNbt), "even converting callback only receives a copy of the live cursor source");
        CellWorkbenchFixtures.Type display = new CellWorkbenchFixtures.Type("fluid"); display.directConversion = true;
        Fixture displayFixture = new Fixture(63, display);
        perform(displayFixture, source, null, cursor);
        check(((AeGuiFixtures.Stack) displayFixture.container.configSync.inventory.slots[1]).amount == 1, "native display-stack conversion stays a unit configuration identity");
    }
    private static void rejectedActions() {
        Fixture fixture = new Fixture(63, new CellWorkbenchFixtures.Type("fluid"));
        ItemStack source = item(); JsonObject cursor = action("cell_config_set", 0); cursor.addProperty("cursor", true);
        fixture.container.haveCell = false; expectNoWrite(fixture, "invalid_state", source, null, cursor);
        fixture.container.haveCell = true; fixture.container.acceptedType = null; expectNoWrite(fixture, "invalid_state", source, null, cursor);
        fixture.container.acceptedType = fixture.type; fixture.slot(0).hidden = true; expectNoWrite(fixture, "invalid_state", source, null, cursor);
        fixture.slot(0).hidden = false; expectNoWrite(fixture, "invalid_state", source, null, cursor);
        fixture.type.containerConversion = true;
        fixture.gui.virtualSlots.add(new VirtualMEPhantomSlot(fixture.container.configSync.inventory, 0)); expectNoWrite(fixture, "unsupported", source, null, cursor); fixture.gui.virtualSlots.remove(63);
        Object originalSlot = fixture.gui.virtualSlots.remove(0); expectNoWrite(fixture, "unsupported", source, null, cursor); fixture.gui.virtualSlots.add(0, originalSlot);
        expectNoWrite(fixture, "not_found", null, null, cursor);
        ItemStack empty = item(); empty.stackSize = 0; expectNoWrite(fixture, "not_found", empty, null, cursor);
        JsonObject falseCursor = action("cell_config_set", 0); falseCursor.addProperty("cursor", false); expectNoWrite(fixture, "invalid_args", source, null, falseCursor);
        JsonObject conflict = action("cell_config_set", 0); conflict.addProperty("cursor", true); conflict.addProperty("playerSlot", 0); expectNoWrite(fixture, "invalid_args", source, new ItemStack[]{source}, conflict);
        JsonObject noSource = action("cell_config_set", 0); expectNoWrite(fixture, "invalid_args", source, null, noSource);
        JsonObject amount = action("cell_config_set", 0); amount.addProperty("cursor", true); amount.addProperty("amount", "1"); expectNoWrite(fixture, "invalid_args", source, null, amount);
        JsonObject outside = action("cell_config_set", 63); outside.addProperty("cursor", true); expectNoWrite(fixture, "invalid_args", source, null, outside);
        JsonObject noInventory = action("cell_config_set", 0); noInventory.addProperty("playerSlot", 35); expectNoWrite(fixture, "not_found", null, new ItemStack[35], noInventory);
        Fixture tooLarge = new Fixture(65, fixture.type); expectNoWrite(tooLarge, "unsupported", source, null, cursor);
    }
    private static void ghostConfiguration() {
        Fixture fixture = new Fixture(63, AEItemStackType.ITEM_STACK_TYPE);
        ItemStack template = item();
        net.minecraft.item.Item.itemRegistry.addObject(32322, "bridge_cell_fixture:item", template.getItem());
        JsonObject action = action("cell_config_set", 45), source = new JsonObject();
        source.addProperty("registryName", "bridge_cell_fixture:item"); source.addProperty("metadata", 7); action.add("source", source);
        AeGuiFixtures.Stack existing = stack(fixture.type, "preserved old marker", 1);
        fixture.container.configSync.inventory.slots[44] = existing;
        JsonObject receipt = perform(fixture, null, null, action);
        check(fixture.container.syncManager.serverInventory.slots[45] != null && fixture.container.configSync.inventory.slots[44] == existing,
                "registry ghost marks slot46 without give, real cursor or inventory; adjacent markers remain intact");
        check(receipt.get("configurationOnly").getAsBoolean() && !receipt.get("inventoryConsumed").getAsBoolean()
                && !receipt.get("serverConfirmed").getAsBoolean() && fixture.container.syncManager.sentDeltas == 1,
                "ghost submits one flushed native confirmation, not proof of server acceptance");
        JsonObject conflict = action("cell_config_set", 0); conflict.add("source", source); conflict.addProperty("cursor", true);
        expectNoWrite(fixture, "invalid_args", template, null, conflict);
        JsonObject malformed = action("cell_config_set", 0); malformed.addProperty("source", "not an object");
        expectNoWrite(fixture, "invalid_args", template, null, malformed);
        net.minecraftforge.fluids.Fluid fluid = new net.minecraftforge.fluids.Fluid("bridge_cell_ghost_fluid");
        net.minecraftforge.fluids.FluidRegistry.registerFluid(fluid);
        JsonObject fluidSource = new JsonObject(); fluidSource.addProperty("fluidName", fluid.getName()); fluidSource.addProperty("amount", 64000);
        CellWorkbenchFixtures.Type fluidType = new CellWorkbenchFixtures.Type("fluid"); fluidType.directConversion = true;
        Fixture fluidFixture = new Fixture(63, fluidType); JsonObject fluidAction = action("cell_config_set", 0); fluidAction.add("source", fluidSource);
        perform(fluidFixture, null, null, fluidAction);
        check(((AeGuiFixtures.Stack) fluidFixture.container.configSync.inventory.slots[0]).nbtIdentity.contains("bridge_cell_ghost_fluid"),
                "native fake-fluid template marks typed fluid configuration without an owned container");
        codechicken.nei.ItemPanel previous = codechicken.nei.ItemPanels.itemPanel;
        try {
            codechicken.nei.ItemPanels.itemPanel = new codechicken.nei.ItemPanel();
            codechicken.nei.ItemPanels.itemPanel.grid.items.add(template);
            JsonObject read = base(); read.addProperty("section", "panel"); read.addProperty("scope", "all"); read.addProperty("limit", 1);
            JsonObject panel = NeiViewAdapter.read(fixture.gui, null, read, "cell-ghost-session", System.nanoTime() + 60000000000L);
            source = new JsonObject(); source.addProperty("neiItemId", panel.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("neiItemId").getAsString());
            action.add("source", source);
            codechicken.nei.ItemPanels.itemPanel.draggedStack = template;
            JsonObject marked = perform(fixture, template, new ItemStack[]{template}, action);
            AeGuiFixtures.Stack result = (AeGuiFixtures.Stack) fixture.container.configSync.inventory.slots[45];
            check(result.nbtIdentity.contains("actual source NBT") && template.getTagCompound().getString("fixture").equals("actual source NBT"),
                    "actual NEI source preserves full marker NBT without entering player inventory");
            check(marked.getAsJsonObject("ghostRelease").get("released").getAsBoolean() && codechicken.nei.ItemPanels.itemPanel.draggedStack == null
                    && fixture.container.configSync.inventory.slots[44] == existing && template.stackSize == 1,
                    "successful source marking automatically releases the NEI fake while preserving real source and existing config");
            codechicken.nei.ItemPanels.itemPanel.draggedStack = template;
            int writes = fixture.container.configSync.writes;
            JsonObject invalidSlot = action("cell_config_set", 63); invalidSlot.add("source", source);
            expectNoWrite(fixture, "invalid_args", template, null, invalidSlot);
            check(codechicken.nei.ItemPanels.itemPanel.draggedStack == template && fixture.container.configSync.writes == writes,
                    "rejected marking leaves the user's current fake available for correction");
            codechicken.nei.ItemPanels.itemPanel.historyPanel = new codechicken.nei.ItemHistoryPanel() {
                @Override public Object getSlotMouseOver(int x, int y) { return fixtureGrid(); }
            };
            expectNoWrite(fixture, "stale_resource", template, null, action);
            JsonObject cleanupSource = new JsonObject(); cleanupSource.addProperty("registryName", "bridge_cell_fixture:item"); cleanupSource.addProperty("metadata", 7);
            action.add("source", cleanupSource);
            marked = perform(fixture, null, null, action);
            check(marked.get("submitted").getAsBoolean() && !marked.getAsJsonObject("ghostRelease").get("available").getAsBoolean()
                    && fixture.container.configSync.writes == writes + 1 && fixture.container.syncManager.sentDeltas == writes + 1,
                    "cleanup incompatibility cannot turn the already flushed edit into failure or replay its native delta");
        } finally { codechicken.nei.ItemPanels.itemPanel = previous; NeiViewAdapter.invalidateRendered(); }
    }
    private static void clearOne() {
        Fixture fixture = new Fixture(63, new CellWorkbenchFixtures.Type("item"));
        AeGuiFixtures.Stack first = stack(fixture.type, "first", 1), second = stack(fixture.type, "second", 1);
        fixture.container.configSync.inventory.slots[0] = first; fixture.container.configSync.inventory.slots[1] = second;
        JsonObject clear = action("cell_config_clear", 0);
        perform(fixture, item(), null, clear);
        check(fixture.container.configSync.inventory.slots[0] == null && fixture.container.configSync.inventory.slots[1] == second, "single-slot clear leaves all other config identities untouched");
        check(fixture.container.configSync.writes == 1 && fixture.container.clearActions == 0 && packets == 0, "clear queues exactly one null delta, not native full clear or an additional packet");
        JsonObject invalid = action("cell_config_clear", 1); invalid.addProperty("cursor", true); expectNoWrite(fixture, "invalid_args", item(), null, invalid);
        fixture.slot(1).hidden = true; expectNoWrite(fixture, "invalid_state", item(), null, action("cell_config_clear", 1));
    }
    private static JsonObject perform(Fixture fixture, ItemStack cursor, ItemStack[] inventory, JsonObject action) {
        return AeControlAdapter.perform(fixture.gui, fixture.container, cursor, inventory, action, packet -> packets++);
    }
    private static void immediateFlush() {
        Fixture fixture = new Fixture(63, AEItemStackType.ITEM_STACK_TYPE);
        JsonObject set = action("cell_config_set", 0); set.addProperty("cursor", true);
        JsonObject receipt = perform(fixture, item(), null, set);
        check(fixture.container.configSync.pending.isEmpty() && fixture.container.syncManager.serverInventory.slots[0] != null, "normal flush drains the delta before the next sequence action can take the cell");
        check(receipt.get("syncFlushInvoked").getAsBoolean() && !receipt.get("serverConfirmed").getAsBoolean(), "even a successful mocked flush is not reported as real server acceptance");
        fixture.container.syncManager.flushSync();
        check(fixture.container.syncManager.sentDeltas == 1, "following normal GUI tick does not submit the same cleared delta twice");
        perform(fixture, null, null, action("cell_config_clear", 0));
        check(fixture.container.syncManager.serverInventory.slots[0] == null && fixture.container.syncManager.sentDeltas == 2 && fixture.container.configSync.pending.isEmpty(), "single-slot clear is also flushed before returning");
        Fixture failed = new Fixture(63, AEItemStackType.ITEM_STACK_TYPE); failed.container.syncManager.failFlush = true;
        try { perform(failed, item(), null, set); throw new AssertionError("Expected native flush failure"); }
        catch (BridgeException error) { check(error.code.equals("adapter_error"), "real native flush exception remains a failure"); }
        check(failed.container.configSync.writes == 1 && failed.container.syncManager.flushes == 1 && failed.container.configSync.pending.size() == 1, "failed flush is not silently retried or mistaken for a second set");
    }
    private static void expectNoWrite(Fixture fixture, String code, ItemStack cursor, ItemStack[] inventory, JsonObject action) {
        int before = fixture.container.configSync.writes, packetBefore = packets, flushBefore = fixture.container.syncManager.flushes;
        Object[] slots = Arrays.copyOf(fixture.container.configSync.inventory.slots, fixture.container.configSync.inventory.slots.length);
        int sourceCount = cursor == null ? 0 : cursor.stackSize;
        NBTTagCompound sourceNbt = cursor == null || !cursor.hasTagCompound() ? null : (NBTTagCompound) cursor.getTagCompound().copy();
        try { perform(fixture, cursor, inventory, action); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "refusal code " + code); }
        check(fixture.container.configSync.writes == before && packets == packetBefore && fixture.container.syncManager.flushes == flushBefore && Arrays.equals(slots, fixture.container.configSync.inventory.slots), "rejected action never queues a delta, flush, packet or config write");
        if (cursor != null) check(cursor.stackSize == sourceCount && (sourceNbt == null ? !cursor.hasTagCompound() : sourceNbt.equals(cursor.getTagCompound())), "rejected action preserves source cursor count/NBT");
    }
    private static JsonObject read(Fixture fixture, int offset, int limit) {
        JsonObject args = base(); args.addProperty("offset", offset); args.addProperty("limit", limit);
        return AeDataAdapter.read(fixture.gui, fixture.container, args, System.nanoTime() + 60000000000L);
    }
    private static JsonObject base() { JsonObject args = new JsonObject(); args.addProperty("screenId", "cell fixture"); return args; }
    private static JsonObject action(String name, int slot) { JsonObject args = base(); args.addProperty("action", name); args.addProperty("slot", slot); return args; }
    private static ItemStack item() {
        ItemStack item = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 1, 7);
        item.setTagCompound(new NBTTagCompound()); item.getTagCompound().setString("fixture", "actual source NBT"); return item;
    }
    private static AeGuiFixtures.Stack stack(AeGuiFixtures.Type type, String identity, long count) { return new AeGuiFixtures.Stack(type, item(), identity, count); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static class Fixture {
        final GuiCellWorkbench gui = new GuiCellWorkbench(); final ContainerCellWorkbench container; final CellWorkbenchFixtures.Type type;
        Fixture(int size, CellWorkbenchFixtures.Type type) {
            this.type = type; container = new ContainerCellWorkbench(size); container.acceptedType = type;
            for (int slot = 0; slot < Math.min(size, 64); slot++) gui.virtualSlots.add(new VirtualMEPhantomSlot(container.configSync.inventory, slot));
        }
        VirtualMEPhantomSlot slot(int index) { return (VirtualMEPhantomSlot) gui.virtualSlots.get(index); }
    }
}
