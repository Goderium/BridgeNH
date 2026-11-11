package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.util.StatCollector;

/** Actual creative tabs and inventory-tab player slots; no item catalog construction or GUI drawing. */
public final class CreativeGuiAdapter {
    private static final int LIMIT = 4096;
    private static final String SLOT = "net.minecraft.client.gui.inventory.GuiContainerCreative$CreativeSlot";

    static State capture(Object screen, InventoryPlayer inventory) {
        if (!(screen instanceof GuiContainerCreative)) return null;
        GuiContainerCreative creative = (GuiContainerCreative) screen;
        int page = value(creative, GuiContainerCreative.class, "getTabPage", "tabPage");
        if (page < 0) throw new BridgeException("unsupported", "Creative GUI page state is unavailable.");
        return new State(creative, inventory, CreativeTabs.creativeTabArray, creative.func_147056_g(), page,
                value(creative, GuiContainer.class, "getGuiLeft", "guiLeft", "field_147003_i"),
                value(creative, GuiContainer.class, "getGuiTop", "guiTop", "field_147009_r"),
                value(creative, GuiContainer.class, "getXSize", "xSize", "field_146999_f"),
                value(creative, GuiContainer.class, "getYSize", "ySize", "field_147000_g"));
    }

    public static void selectPlayerInventory(GuiScreen screen, InventoryPlayer inventory, BooleanSupplier stillCurrent) {
        State state = capture(screen, inventory);
        if (state == null || state.inventorySelected()) return;
        GuiControlAdapter.neutralInput();
        activate(screen, CreativeTabs.tabInventory, state, stillCurrent);
        if (!stillCurrent.getAsBoolean() || !capture(screen, inventory).inventorySelected())
            throw new BridgeException("invalid_state", "The native creative player-inventory tab was not selected; no item acquisition was submitted.");
    }

    private static int value(Object screen, Class<?> owner, String getter, String... fields) {
        // Prefer an actual public getter. A getter failure must not fall back to an unrelated field.
        try { screen.getClass().getMethod(getter); }
        catch (NoSuchMethodException absent) {
            for (String field : fields) {
                try { return ((Number) ExpandedGuiApi.field(screen, owner, field)).intValue(); }
                catch (BridgeException unavailable) { /* Fixed development/production mapping only. */ }
            }
            throw new BridgeException("unsupported", "Fixed creative GUI state is unavailable: " + getter);
        }
        return ((Number) ExpandedGuiApi.call(screen, getter)).intValue();
    }

    static JsonObject describe(CreativeTabs tab, State state) {
        JsonObject result = new JsonObject(); Rectangle bounds = state.tabBounds(tab);
        boolean visible = state.visible(tab), valid = state.registered(tab);
        result.addProperty("kind", "creative_tab"); result.addProperty("name", JsonData.bounded(tab.getTabLabel(), 256));
        result.addProperty("text", JsonData.bounded(StatCollector.translateToLocal(tab.getTranslatedTabLabel()), 512));
        result.addProperty("nativeTabIndex", tab.getTabIndex()); result.addProperty("tabPage", tab.getTabPage());
        result.addProperty("column", tab.getTabColumn()); result.addProperty("firstRow", tab.isTabInFirstRow());
        result.addProperty("selected", tab.getTabIndex() == state.selected); result.addProperty("playerInventory", tab == CreativeTabs.tabInventory);
        result.addProperty("search", tab == CreativeTabs.tabAllSearch); result.addProperty("hasSearchBar", tab.hasSearchBar());
        result.addProperty("visible", visible); result.addProperty("valid", valid);
        result.addProperty("enabled", valid && visible && state.nativeScreen() && GuiCallbacks.supportsClick() && state.uniqueTabPoint(tab));
        bounds(result, bounds); return result;
    }

    static JsonObject describe(Slot slot, State state) {
        JsonObject result = new JsonObject(); boolean valid = state.playerSlot(slot);
        result.addProperty("kind", "creative_player_slot"); result.addProperty("name", "player_slot:" + slot.getSlotIndex());
        result.addProperty("slot", slot.slotNumber); result.addProperty("nativeWrapperIndex", slot.getSlotIndex());
        if (valid) {
            try {
                Slot backing = GuiCallbacks.creativeBacking(state.screen, slot, state.inventory);
                result.addProperty("playerInventoryIndex", backing.getSlotIndex());
                result.addProperty("nativeContainerSlotId", backing.slotNumber);
                result.addProperty("inventoryBinding", "public_isHere_unique_actual_player_container_slot");
            } catch (BridgeException error) { result.addProperty("inventoryBinding", "unavailable"); result.addProperty("inventoryBindingReason", error.code); }
        }
        result.addProperty("inventorySource", "current_player_inventory"); result.add("item", JsonData.item(slot.getStack()));
        result.addProperty("visible", state.slotVisible(slot)); result.addProperty("valid", valid);
        result.addProperty("enabled", valid && state.nativeScreen() && GuiCallbacks.supportsCreativePickup() && state.uniqueSlotPoint(slot));
        result.addProperty("nativeClickButton", 0); result.addProperty("nativeClickType", 0);
        result.addProperty("slotIndexCoverage", "The creative wrapper index is not an InventoryPlayer index; public isHere resolves the unique actual player-container slot before native pickup.");
        bounds(result, state.slotBounds(slot)); return result;
    }

    static JsonObject activate(GuiScreen screen, Object target, State previous, BooleanSupplier stillCurrent) {
        State current = capture(screen, previous.inventory);
        if (current == null || !current.nativeScreen() || !stillCurrent.getAsBoolean())
            throw new BridgeException("stale_screen", "The native creative screen changed before activation.");
        if (target instanceof CreativeTabs) {
            CreativeTabs tab = (CreativeTabs) target;
            if (!current.registered(tab)) throw new BridgeException("stale_widget", "The actual creative tab is no longer registered.");
            if (!current.visible(tab) || !current.uniqueTabPoint(tab)) throw new BridgeException("target_unverified", "This tab has no unique visible native click position on the current page.");
            Rectangle box = current.tabBounds(tab);
            GuiCallbacks.click(screen, box.x + box.width / 2, box.y + box.height / 2, 0, stillCurrent);
            return null;
        }
        if (!(target instanceof Slot) || !current.playerSlot((Slot) target))
            throw new BridgeException("stale_widget", "The exact inventory-tab player slot is no longer current.");
        if (current.inventory != previous.inventory || !current.uniqueSlotPoint((Slot) target))
            throw new BridgeException("target_unverified", "The current player slot binding is unavailable or ambiguous.");
        return GuiCallbacks.creativePickup(screen, (Slot) target, current.inventory);
    }

    static JsonObject summary(State state) {
        JsonObject result = new JsonObject();
        result.addProperty("available", true); result.addProperty("selectedTabIndex", state.selected);
        result.addProperty("page", state.page); result.addProperty("tabsTotal", state.tabsTotal);
        result.addProperty("tabsTruncated", state.tabsTotal > state.tabs.length);
        result.addProperty("playerSlotsAvailable", state.inventory != null && state.inventorySelected());
        result.addProperty("playerSlots", state.slots().size());
        result.addProperty("playerSlotsTruncated", state.slotsTruncated);
        if (state.inventory != null) result.add("cursorItem", JsonData.item(state.inventory.getItemStack()));
        result.addProperty("source", "Public CreativeTabs registry/labels/layout and selected-tab API; current native page/container geometry through fixed read-only compatibility fields when public getters are absent.");
        result.addProperty("actions", "Visible native tabs and inventory-tab actual player slots support activate. Player slots use one fixed left pickup callback; right click, number keys, Shift, drag, trash and catalog slots are unavailable.");
        result.addProperty("completion", "Native submission only; inspect the current selected tab, slots and carried stack afterward. Server inventory synchronization is not acknowledged here.");
        return result;
    }

    private static void bounds(JsonObject result, Rectangle box) {
        result.addProperty("x", box.x); result.addProperty("y", box.y); result.addProperty("width", box.width); result.addProperty("height", box.height);
        result.addProperty("coordinateSpace", "screen");
    }

    static final class State {
        final GuiContainerCreative screen;
        final InventoryPlayer inventory;
        final CreativeTabs[] registry, tabs;
        final int selected, page, left, top, width, height, tabsTotal;
        final List<Slot> allSlots = new ArrayList<Slot>(), playerSlots = new ArrayList<Slot>();
        final IdentityHashMap<Slot, Boolean> members = new IdentityHashMap<Slot, Boolean>();
        final boolean slotsTruncated;
        State(GuiContainerCreative screen, InventoryPlayer inventory, CreativeTabs[] registry, int selected, int page, int left, int top, int width, int height) {
            this.screen = screen; this.inventory = inventory; this.registry = registry;
            this.selected = selected; this.page = page; this.left = left; this.top = top; this.width = width; this.height = height;
            tabsTotal = registry.length; tabs = new CreativeTabs[Math.min(LIMIT, registry.length)]; System.arraycopy(registry, 0, tabs, 0, tabs.length);
            if (width <= 0 || height <= 0) throw new BridgeException("unsupported", "Native creative container geometry is unavailable.");
            slotsTruncated = screen.inventorySlots.inventorySlots.size() > LIMIT;
            int count = 0;
            for (Object object : screen.inventorySlots.inventorySlots) {
                if (++count > LIMIT) break;
                if (object instanceof Slot) { Slot slot = (Slot) object; allSlots.add(slot); members.put(slot, true); }
            }
            for (Slot slot : allSlots) if (playerSlot(slot)) playerSlots.add(slot);
        }
        boolean nativeScreen() { return screen.getClass() == GuiContainerCreative.class; }
        boolean registered(CreativeTabs tab) { int index = tab.getTabIndex(); return index >= 0 && index < tabs.length && tabs[index] == tab && CreativeTabs.creativeTabArray == registry && registry[index] == tab; }
        boolean visible(CreativeTabs tab) { return registered(tab) && nativeVisible(tab); }
        private boolean nativeVisible(CreativeTabs tab) { return tab.getTabPage() == page || tab == CreativeTabs.tabInventory || tab == CreativeTabs.tabAllSearch; }
        Rectangle tabBounds(CreativeTabs tab) {
            int column = tab.getTabColumn();
            int x = column == 5 ? width - 28 + 2 : column * 28 + (column > 0 ? column : 0);
            return new Rectangle(left + x, top + (tab.isTabInFirstRow() ? -32 : height), 28, 32);
        }
        boolean uniqueTabPoint(CreativeTabs tab) {
            if (tabsTotal > tabs.length) return false;
            Rectangle box = tabBounds(tab); int x = box.x + 14, y = box.y + 16;
            if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return false;
            // Native dispatch iterates every registry entry, including malformed index/object bindings.
            for (CreativeTabs other : tabs) if (other != null && other != tab && nativeVisible(other) && nativeTabContains(other, x, y)) return false;
            return true;
        }
        private boolean nativeTabContains(CreativeTabs tab, int x, int y) { Rectangle box = tabBounds(tab); return x >= box.x && x <= box.x + 28 && y >= box.y && y <= box.y + 32; }
        boolean inventorySelected() { return registered(CreativeTabs.tabInventory) && selected == CreativeTabs.tabInventory.getTabIndex(); }
        List<Slot> slots() { return playerSlots; }
        boolean playerSlot(Slot slot) {
            return inventory != null && inventorySelected() && slot.inventory == inventory && SLOT.equals(slot.getClass().getName()) && slotVisible(slot) && members.containsKey(slot);
        }
        boolean slotVisible(Slot slot) { return slot.xDisplayPosition >= 0 && slot.yDisplayPosition >= 0 && slot.xDisplayPosition + 16 <= width && slot.yDisplayPosition + 16 <= height; }
        Rectangle slotBounds(Slot slot) { return new Rectangle(left + slot.xDisplayPosition, top + slot.yDisplayPosition, 16, 16); }
        boolean uniqueSlotPoint(Slot slot) {
            if (slotsTruncated) return false;
            Rectangle box = slotBounds(slot); int x = box.x + 8, y = box.y + 8;
            if (x < 0 || y < 0 || x >= screen.width || y >= screen.height) return false;
            for (Slot other : allSlots) {
                if (other != slot) {
                    Rectangle otherBox = slotBounds(other);
                    if (x >= otherBox.x - 1 && x < otherBox.x + 17 && y >= otherBox.y - 1 && y < otherBox.y + 17) return false;
                }
            }
            return true;
        }
    }
}
