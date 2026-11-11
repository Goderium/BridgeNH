package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.inventory.GhostItemSource;
import com.Goderium.BridgeNH.common.inventory.InventoryCapacity;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.client.gui.implementations.GuiCellWorkbench;
import codechicken.nei.ItemPanel;
import codechicken.nei.ItemPanels;
import codechicken.nei.ItemHistoryPanel;
import codechicken.nei.ItemCraftablesPanel;
import codechicken.nei.PanelWidget;
import codechicken.nei.LayoutManager;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.BookmarkPanel;
import codechicken.nei.bookmark.BookmarkItem;
import codechicken.nei.bookmark.BookmarksGridSlot;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Native API fixtures, never evidence of a real GUI, server acquisition or persisted configuration. */
public final class NeiItemControlSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        ItemPanel previousPanel = ItemPanels.itemPanel;
        PanelWidget previousBookmarks = ItemPanels.bookmarkPanel;
        String previousSearch = LayoutManager.searchField.value;
        try { sources(); acquisition(); zeroCountBookmarks(); batchCapacity(); ghostPickup(); release(); }
        finally { ItemPanels.itemPanel = previousPanel; ItemPanels.bookmarkPanel = previousBookmarks; LayoutManager.searchField.value = previousSearch; NEIClientConfig.allowed = true; NeiViewAdapter.invalidateRendered(); }
        return checks;
    }
    private static ItemStack item() {
        Item type = new StructuredInfoSelfTest.FakeItem();
        Item.itemRegistry.addObject(32321, "bridge_nei_fixture:item", type);
        ItemStack stack = new ItemStack(type, 3, 7); stack.setTagCompound(new NBTTagCompound());
        stack.getTagCompound().setString("fixture", "full NEI source NBT"); return stack;
    }
    private static JsonObject base(String action) {
        JsonObject args = new JsonObject(); args.addProperty("screenId", "nei-item-fixture"); args.addProperty("action", action); return args;
    }
    private static JsonObject read(Object screen, int offset, String token) {
        JsonObject args = new JsonObject(); args.addProperty("screenId", "nei-item-fixture"); args.addProperty("section", "panel");
        args.addProperty("scope", "all"); args.addProperty("offset", offset); args.addProperty("limit", 1);
        if (token != null) args.addProperty("contextToken", token);
        return NeiViewAdapter.read(screen, null, args, "nei-item-session", System.nanoTime() + 60000000000L);
    }
    private static String id(JsonObject page) { return page.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("neiItemId").getAsString(); }
    private static void sources() {
        Object screen = new GuiCellWorkbench(); ItemStack stack = item();
        ItemPanels.itemPanel = new ItemPanel(); ItemPanels.itemPanel.grid.items.add(stack);
        JsonObject page = read(screen, 0, null); String id = id(page);
        check(page.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("ghostSource").get("neiItemId").getAsString().equals(id),
                "the same observed identity is exposed as a strictly bindable ghost source");
        JsonObject source = new JsonObject(); source.addProperty("neiItemId", id);
        ItemStack resolved = GhostItemSource.resolve(screen, source, null);
        check(resolved != stack && resolved.stackSize == 1 && resolved.getTagCompound().equals(stack.getTagCompound()), "NEI ghost identity uses full NBT on an independent unit copy, with no owned inventory");
        resolved.getTagCompound().setString("fixture", "changed copy");
        check(NeiViewAdapter.resolvePanelItem(screen, id).getTagCompound().getString("fixture").equals("full NEI source NBT"), "copy mutation cannot alter live NEI or remembered identity");
        check(id(read(screen, 0, null)).equals(id), "unchanged panel reads reuse the same bounded item reference");
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(new GuiCellWorkbench(), id));
        source.addProperty("nbt", "{}"); expect("invalid_args", () -> GhostItemSource.resolve(screen, source, null)); source.remove("nbt");
        source.addProperty("registryName", "minecraft:stone"); expect("invalid_args", () -> GhostItemSource.resolve(screen, source, null));
        for (int i = 1; i <= 300; i++) ItemPanels.itemPanel.grid.items.add(stack.copy());
        page = read(screen, 0, null); String token = page.get("contextToken").getAsString();
        String far = id(read(screen, 300, token));
        ItemPanels.itemPanel.grid.items.get(300).getTagCompound().setString("fixture", "changed beyond signature prefix");
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, far));
        page = read(screen, 0, null); token = page.get("contextToken").getAsString(); String first = id(page);
        for (int i = 1; i <= 256; i++) read(screen, i, token);
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, first));
        String current = id(read(screen, 256, token));
        JsonObject search = base("search"); search.addProperty("text", "different search"); int edits = LayoutManager.searchField.edits;
        JsonObject receipt = NeiItemControlAdapter.perform(screen, search, false, false, false, null, null);
        check(LayoutManager.searchField.edits == edits + 1 && receipt.get("filterCompletion").getAsString().startsWith("pending"), "search uses native setter once, without requiring creative or a visible search box");
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, current));
        NeiViewAdapter.invalidateRendered(); expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, current));
    }
    private static void acquisition() {
        Object screen = new GuiCellWorkbench(); ItemStack stack = item();
        ItemPanels.itemPanel = new ItemPanel(); ItemPanels.itemPanel.grid.items.add(stack);
        JsonObject args = base("take"); args.addProperty("neiItemId", id(read(screen, 0, null)));
        ItemStack[] inventory = new ItemStack[36]; inventory[5] = stack.copy(); int acquisitions = NEIClientUtils.acquisitions;
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, true, false, true, null, inventory));
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, false, true, true, null, inventory));
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, true, true, false, null, inventory));
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, stack, inventory));
        GuiContainerCreative catalog = new GuiContainerCreative(); catalog.select(CreativeTabs.tabBlock);
        expect("invalid_state", () -> NeiItemControlAdapter.perform(catalog, args, true, true, true, null, inventory));
        NEIClientConfig.allowed = false;
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory)); NEIClientConfig.allowed = true;
        check(NEIClientUtils.acquisitions == acquisitions, "all permission, container, cursor and native NEI refusals occur before acquisition");
        JsonObject receipt = NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory);
        check(NEIClientUtils.acquisitions == acquisitions + 1 && NEIClientUtils.button == 0 && NEIClientUtils.mode == 0, "one finite normal NEI left-click branch, no infinite handler or retries");
        check(NEIClientUtils.requested.stackSize == 1 && NEIClientUtils.requested.getTagCompound().equals(stack.getTagCompound()) && stack.stackSize == 3, "acquisition keeps complete observed identity without modifying its source");
        check(receipt.get("inventoryCountBefore").getAsString().equals("3") && receipt.get("expectedInventoryDelta").getAsInt() == 1 && !receipt.get("serverConfirmed").getAsBoolean() && !receipt.get("acquisitionVerified").getAsBoolean(), "native submission retains exact inventory baseline and cannot claim real pickup");
        for (int i = 0; i < inventory.length; i++) { inventory[i] = stack.copy(); inventory[i].stackSize = 64; }
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory));
        check(NEIClientUtils.acquisitions == acquisitions + 1, "full player inventory cannot redirect acquisition into a machine");
        ItemStack[] extended = new ItemStack[37]; extended[36] = stack.copy();
        receipt = NeiItemControlAdapter.perform(screen, args, true, true, true, null, extended);
        check(receipt.get("observedMainInventorySlots").getAsInt() == 37 && receipt.get("capacitySlotsChecked").getAsInt() == 36
                && receipt.get("inventoryCountBefore").getAsString().equals("3"), "Backhand's actual offhand contents are counted without rejecting its legitimate expanded array");
        for (int i = 0; i < 36; i++) { extended[i] = stack.copy(); extended[i].stackSize = 64; }
        extended[36] = null;
        int before = NEIClientUtils.acquisitions;
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, extended));
        check(NEIClientUtils.acquisitions == before, "an empty offhand is never counted as ordinary NEI insertion capacity");
        expect("target_unverified", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, new ItemStack[35]));
        expect("target_unverified", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, new ItemStack[4097]));
        catalog.select(CreativeTabs.tabInventory);
        JsonObject creativeArgs = base("take"); creativeArgs.addProperty("neiItemId", id(read(catalog, 0, null)));
        NeiItemControlAdapter.perform(catalog, creativeArgs, true, true, true, null, new ItemStack[36]);
        check(NEIClientUtils.acquisitions == before + 1, "creative player-inventory tab retains normal finite acquisition");
    }
    private static void batchCapacity() {
        ItemStack first = item(); first.stackSize = 64;
        ItemStack second = first.copy(); second.getTagCompound().setString("fixture", "different NBT");
        ItemStack[] full = new ItemStack[37];
        for (int i = 0; i < 35; i++) full[i] = first.copy();
        check(!InventoryCapacity.fits(full, new ItemStack[] {first, second}, 64),
                "two identities cannot both reserve the last ordinary slot; empty offhand excluded");
        check(full[35] == null && full[0].stackSize == 64, "capacity preflight never changes actual inventory");
        full[0].stackSize = 63; ItemStack one = first.copy(); one.stackSize = 1;
        check(InventoryCapacity.fits(full, new ItemStack[] {one, second}, 64),
                "merge exact full NBT first, leaving the last slot for the distinct type");
        check(full[0].stackSize == 63 && full[35] == null, "successful reservation also leaves inventory untouched");
        full[35] = first.copy();
        check(!InventoryCapacity.fits(full, new ItemStack[] {second}, 64), "different NBT cannot use a matching item's free count");
        check(!InventoryCapacity.fits(new ItemStack[36], new ItemStack[] {first}, 1), "native inventory limit participates in reservation");
    }
    private static JsonObject bookmarkRead(Object screen, String scope) {
        JsonObject args = new JsonObject(); args.addProperty("screenId", "nei-item-fixture"); args.addProperty("section", "panel");
        args.addProperty("panel", "bookmarks"); args.addProperty("scope", scope); args.addProperty("limit", 1);
        return NeiViewAdapter.read(screen, null, args, "nei-item-session", System.nanoTime() + 60000000000L);
    }
    private static void zeroCountBookmarks() {
        Object screen = new GuiCellWorkbench(); ItemStack stack = item(); stack.stackSize = 0;
        BookmarkPanel panel = new BookmarkPanel(); ItemPanels.bookmarkPanel = panel;
        BookmarkItem bookmark = new BookmarkItem(); bookmark.itemStack = stack; bookmark.multiplier = 0; bookmark.type = "RESULT";
        panel.bookmarks.saved.add(bookmark);
        panel.bookmarks.fixtureMask(java.util.Collections.singletonList(new BookmarksGridSlot(2, 0, bookmark)));
        JsonObject page = bookmarkRead(screen, "visible"); String itemId = id(page);
        JsonObject entry = page.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject();
        check(entry.getAsJsonObject("item").get("count").getAsInt() == 0
                && entry.getAsJsonObject("ghostSource").get("neiItemId").getAsString().equals(itemId),
                "visible unsized bookmark keeps its real zero count while exposing the existing operation identity");
        check(itemId.equals(id(bookmarkRead(screen, "all"))), "visible and all bookmark reads reuse the same exact template reference");
        ItemStack raw = NeiViewAdapter.resolvePanelItem(screen, itemId);
        check(raw != stack && raw.stackSize == 0 && ItemStack.areItemStackTagsEqual(raw, stack), "resolved source preserves raw quantity and full NBT on a copy");
        JsonObject source = new JsonObject(); source.addProperty("neiItemId", itemId);
        ItemStack ghost = GhostItemSource.resolve(screen, source, null);
        check(ghost.stackSize == 1 && ItemStack.areItemStackTagsEqual(ghost, stack) && stack.stackSize == 0,
                "existing semantic ghost source creates a unit copy without changing bookmark storage");
        ghost.getTagCompound().setString("fixture", "changed independent ghost");
        check(!ghost.getTagCompound().equals(stack.getTagCompound()), "NBT copies cannot rewrite bookmark templates");
        JsonObject args = base("take"); args.addProperty("neiItemId", itemId); int before = NEIClientUtils.acquisitions;
        ItemStack[] inventory = new ItemStack[36];
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, true, false, true, null, inventory));
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, false, true, true, null, inventory));
        NEIClientConfig.allowed = false;
        expect("permission_denied", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory)); NEIClientConfig.allowed = true;
        check(NEIClientUtils.acquisitions == before, "bookmark eligibility does not bypass creative, profile or native NEI permissions");
        JsonObject receipt = NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory);
        check(NEIClientUtils.acquisitions == before + 1 && NEIClientUtils.requested.stackSize == 1
                && ItemStack.areItemStackTagsEqual(NEIClientUtils.requested, stack) && stack.stackSize == 0,
                "zero-count bookmark uses exactly one existing finite NEI acquisition with complete NBT");
        check(!receipt.get("serverConfirmed").getAsBoolean() && !receipt.get("acquisitionVerified").getAsBoolean(),
                "template acceptance does not falsely confirm actual inventory receipt");
        args.addProperty("count", 4); NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory);
        check(NEIClientUtils.requested.stackSize == 4 && stack.stackSize == 0, "explicit requested quantity changes only the independent acquisition copy");
        args.addProperty("count", 0); expect("invalid_args", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory));
        args.remove("count");
        for (int i = 0; i < inventory.length; i++) { inventory[i] = stack.copy(); inventory[i].stackSize = 64; }
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, true, true, true, null, inventory));
        check(NEIClientUtils.acquisitions == before + 2, "full inventory and invalid quantity never submit another acquisition");
        stack.getTagCompound().setString("fixture", "edited bookmark");
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, itemId));
        final String oldCountId = id(bookmarkRead(screen, "all")); stack.stackSize = 1;
        expect("stale_resource", () -> NeiViewAdapter.resolvePanelItem(screen, oldCountId));
        stack.stackSize = -1;
        check(!bookmarkRead(screen, "all").getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().has("neiItemId"),
                "negative bookmark quantities remain ineligible");
        stack.stackSize = 0; ItemPanels.itemPanel = new ItemPanel(); ItemPanels.itemPanel.grid.items.add(stack);
        check(!read(screen, 0, null).getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().has("neiItemId"),
                "zero-count ordinary item-panel entries are still rejected");
        JsonObject player = new JsonObject(); player.addProperty("playerSlot", 0); inventory[0] = stack;
        expect("not_found", () -> GhostItemSource.resolve(screen, player, inventory));
        ItemPanels.bookmarkPanel = new PanelWidget(); NeiViewAdapter.invalidateRendered();
    }
    private static void ghostPickup() {
        Object screen = new GuiCellWorkbench(); ItemStack stack = item();
        ItemPanel panel = new ItemPanel(); ItemPanels.itemPanel = panel; ItemPanels.bookmarkPanel = new PanelWidget();
        panel.grid.items.add(stack); panel.grid.fixtureVisible(stack); LayoutManager.fixtureWidgets(panel);
        check(!LayoutManager.fixtureControlContains(panel), "matching NEI comparator makes TreeSet.contains miss an actual native widget");
        JsonObject page = read(screen, 0, null), summary = page.getAsJsonObject("data").getAsJsonObject("panel");
        check(summary.get("inDrawWidgets").getAsBoolean() && summary.get("inControlWidgets").getAsBoolean(),
                "layout diagnostics recognize actual object identity without relying on the NEI comparator");
        JsonObject args = base("ghost_pick"); args.addProperty("neiItemId", id(page));
        int acquisitions = NEIClientUtils.acquisitions;
        JsonObject receipt = NeiItemControlAdapter.perform(screen, args, false, false, false, null, null).getAsJsonObject("ghostPickup");
        check(receipt.get("sourceIdentityMatches").getAsBoolean() && receipt.get("ghostPresentAfter").getAsBoolean()
                && !receipt.getAsJsonObject("afterPress").get("ghostItem").isJsonNull()
                && !receipt.getAsJsonObject("afterRelease").get("ghostItem").isJsonNull(), "native item-panel middle press and release preserve the actual nonempty phantom with phase receipts");
        check(panel.presses == 1 && panel.releases == 1 && NEIClientUtils.acquisitions == acquisitions && stack.stackSize == 3,
                "phantom gesture never invokes creative acquisition, real inventory writes or an OS event");
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, false, false, false, null, null));
        check(NeiGhostDragAdapter.release(screen).get("released").getAsBoolean() && panel.draggedStack == null,
                "a nonempty native-fixture phantom is actually released without replaying the marking action");
        expect("invalid_state", () -> NeiItemControlAdapter.perform(screen, args, false, false, false, stack, null));
        LayoutManager.fixtureWidgets(new ItemPanel());
        args.addProperty("neiItemId", id(read(screen, 0, null)));
        expect("target_unverified", () -> NeiItemControlAdapter.perform(screen, args, false, false, false, null, null));
        check(panel.presses == 1 && panel.draggedStack == null, "a different same-class same-z widget cannot authorize the observed panel");
        panel.mouseDownSlot=3;
        expect("input_busy", () -> NeiGhostDragAdapter.pick(panel, ItemPanels.bookmarkPanel, 63, 9, stack));
        check(panel.presses==1, "an unfinished native press is refused without acquiring or changing a phantom");
        ItemPanel redirected=new ItemPanel() { @Override public boolean handleClick(int x,int y,int button) { presses++;mouseDownSlot=3;return true; } };
        receipt=NeiGhostDragAdapter.pick(redirected,ItemPanels.bookmarkPanel,63,9,stack);
        check(redirected.releases==0 && receipt.has("releaseSkipped") && !receipt.get("ghostPresentAfter").getAsBoolean(),
                "a redirected press cannot turn diagnostic release into a real NEI acquisition");
    }
    private static void release() {
        ItemPanel panel = new ItemPanel(); PanelWidget bookmarks = new PanelWidget();
        panel.historyPanel = new ItemHistoryPanel(); panel.craftablesPanel = new ItemCraftablesPanel();
        ItemPanels.itemPanel = panel; ItemPanels.bookmarkPanel = bookmarks;
        ItemStack real = item(); ItemStack[] inventory = new ItemStack[36]; inventory[5] = real;
        NBTTagCompound before = (NBTTagCompound) real.getTagCompound().copy(); int acquisitions = NEIClientUtils.acquisitions;
        // Same object identity as a real stack deliberately catches any in-place "fake" destruction.
        panel.draggedStack = real; bookmarks.draggedStack = real;
        panel.mouseDownSlot = 2; bookmarks.mouseDownSlot = 3;
        panel.historyPanel.fixturePending(4); panel.craftablesPanel.fixturePending(5);
        Object screen = new GuiCellWorkbench(); JsonObject args = base("release");
        JsonObject receipt = NeiItemControlAdapter.perform(screen, args, false, false, false, real, inventory).getAsJsonObject("ghostRelease");
        check(receipt.get("released").getAsBoolean() && !receipt.get("ghostPresentAfter").getAsBoolean()
                && panel.draggedStack == null && bookmarks.draggedStack == null, "release cancels both search and bookmark phantom cursors in survival without acquisition permission");
        check(panel.mouseDownSlot == -1 && bookmarks.mouseDownSlot == -1 && panel.historyPanel.fixturePending() == -1 && panel.craftablesPanel.fixturePending() == -1,
                "all four source panels lose their pending presses so the fake cannot reappear from the old gesture");
        check(inventory[5] == real && real.stackSize == 3 && real.getTagCompound().equals(before)
                && NEIClientUtils.acquisitions == acquisitions && receipt.get("realCursorPreserved").getAsBoolean(), "real stack identity, count, NBT and inventory survive; release never acquires, drops or cheats an item");
        receipt = NeiItemControlAdapter.perform(new net.minecraft.client.gui.GuiScreen(), args, false, true, false, real, inventory).getAsJsonObject("ghostRelease");
        check(!receipt.get("hadGhost").getAsBoolean() && !receipt.get("released").getAsBoolean() && !receipt.get("ghostPresentAfter").getAsBoolean(),
                "no fake is a safe no-op even on a different non-AE GUI with a real cursor");
        panel.draggedStack = real;
        JsonObject invalid = base("release"); invalid.addProperty("text", "untrusted");
        expect("invalid_args", () -> NeiItemControlAdapter.perform(screen, invalid, false, false, false, real, inventory));
        check(panel.draggedStack == real, "invalid release arguments do not cancel a gesture");
        panel.historyPanel = new ItemHistoryPanel() { @Override public Object getSlotMouseOver(int x, int y) { return fixtureGrid(); } };
        expect("target_unverified", () -> NeiGhostDragAdapter.release(screen));
        check(panel.draggedStack == real && panel.historyPanel.releases == 0, "all cancellation positions are checked before changing a panel or calling a native callback");
        receipt = NeiGhostDragAdapter.afterMark(screen);
        check(!receipt.get("available").getAsBoolean() && receipt.get("recovery").getAsString().contains("never replay") && panel.draggedStack == real,
                "post-mark cleanup failure is a separate unconfirmed receipt, never a reason to replay marking");
        ItemPanels.itemPanel = null; ItemPanels.bookmarkPanel = bookmarks; bookmarks.draggedStack = real;
        check(NeiGhostDragAdapter.release(screen).get("released").getAsBoolean() && bookmarks.draggedStack == null, "bookmark-only initialization is supported");
        ItemPanels.bookmarkPanel = null;
        check(!NeiGhostDragAdapter.release(screen).get("panelsInitialized").getAsBoolean(), "uninitialized panels need no fake cleanup");
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "refusal " + code + ", got " + error.code); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
