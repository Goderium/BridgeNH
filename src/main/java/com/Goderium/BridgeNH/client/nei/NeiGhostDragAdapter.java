package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import net.minecraft.item.ItemStack;

/** Cancels NEI's independent phantom cursor, never a player stack or a container click. */
public final class NeiGhostDragAdapter {
    static JsonObject pick(Object panel, Object bookmarks, int x, int y, ItemStack expected) {
        try {
            PanelState state = new PanelState(panel), bookmarkState = bookmarks == null ? null : new PanelState(bookmarks);
            if (state.ghost() || bookmarkState != null && bookmarkState.ghost())
                throw new BridgeException("invalid_state", "An NEI phantom cursor already exists; release it before creating a different gesture.");
            if (state.mouseDown.getInt(panel) >= 0 || bookmarkState != null && bookmarkState.mouseDown.getInt(bookmarks) >= 0)
                throw new BridgeException("input_busy", "A native NEI press is pending; finish or release it before creating a phantom gesture.");
            panel.getClass().getMethod("handleClick", int.class, int.class, int.class);
            panel.getClass().getMethod("mouseUp", int.class, int.class, int.class);
            JsonObject result = new JsonObject(); result.addProperty("nativeAction", "nei_item_panel_middle_press_release");
            result.addProperty("acceptedPress", Boolean.TRUE.equals(ExpandedGuiApi.call(panel, "handleClick", x, y, 2)));
            result.add("afterPress", state.snapshot());
            // Release is the public ItemPanel path, never BookmarkPanel.mouseUp (which can reorder).
            if (state.ghost() || state.mouseDown.getInt(panel) < 0) ExpandedGuiApi.call(panel, "mouseUp", x, y, 2);
            else result.addProperty("releaseSkipped", "native_press_became_a_real_acquisition_candidate; inspect before recovery");
            result.add("afterRelease", state.snapshot());
            ItemStack actual = (ItemStack) state.dragged.get(panel);
            result.addProperty("ghostPresentAfter", actual != null);
            result.addProperty("sourceIdentityMatches", actual != null && actual.getItem() == expected.getItem()
                    && actual.getMetadata() == expected.getMetadata() && ItemStack.areItemStackTagsEqual(actual, expected));
            result.addProperty("inventoryConsumed", false); result.addProperty("realCursorPreserved", true);
            result.addProperty("completion", "Actual native panel state after press and release; no phantom field was created by the bridge. A false or mismatching result is not success and must not be blindly replayed.");
            return result;
        } catch (ReflectiveOperationException | SecurityException error) {
            throw new BridgeException("unsupported", "The current NEI item-panel gesture API is unavailable.");
        }
    }
    static JsonObject release(Object screen) {
        Class<?> panels = ExpandedGuiApi.load(screen, "codechicken.nei.ItemPanels");
        try {
            Object items = panels.getField("itemPanel").get(null);
            Object bookmarks = panels.getField("bookmarkPanel").get(null);
            PanelState itemState = items == null ? null : new PanelState(items);
            PanelState bookmarkState = bookmarks == null ? null : new PanelState(bookmarks);
            Object history = items == null ? null : items.getClass().getField("historyPanel").get(items);
            Object craftables = items == null ? null : items.getClass().getField("craftablesPanel").get(items);
            // Preflight both public subpanel release callbacks at a verified non-slot position.
            // BookmarkPanel.mouseUp can commit/reorder bookmarks even outside its grid: never call it.
            preflight(history); preflight(craftables);
            boolean hadGhost = itemState != null && itemState.ghost() || bookmarkState != null && bookmarkState.ghost();
            releaseSubpanel(history); releaseSubpanel(craftables);
            if (itemState != null) itemState.clear();
            if (bookmarkState != null) bookmarkState.clear();
            boolean remaining = itemState != null && itemState.ghost() || bookmarkState != null && bookmarkState.ghost();
            JsonObject result = new JsonObject();
            result.addProperty("available", true);
            result.addProperty("panelsInitialized", items != null || bookmarks != null);
            result.addProperty("hadGhost", hadGhost);
            result.addProperty("released", hadGhost && !remaining);
            result.addProperty("ghostPresentAfter", remaining);
            result.addProperty("pendingDragsCancelled", true);
            result.addProperty("realCursorPreserved", true);
            result.addProperty("inventoryConsumed", false);
            result.addProperty("nativeAction", "nei_phantom_drag_state_cancel");
            return result;
        } catch (ReflectiveOperationException | SecurityException error) {
            throw new BridgeException("unsupported", "The current NEI phantom drag API is unavailable; no real cursor or inventory is cleared.");
        }
    }

    public static JsonObject afterMark(Object screen) {
        try { return release(screen); }
        catch (RuntimeException | LinkageError error) {
            // The native marking action has already been submitted. Cleanup failure must not invite a replay.
            JsonObject result = new JsonObject(); result.addProperty("available", false);
            result.addProperty("released", false); result.addProperty("realCursorPreserved", true);
            result.addProperty("reason", error instanceof BridgeException ? ((BridgeException) error).code : error.getClass().getSimpleName());
            result.addProperty("recovery", "Marking was submitted; inspect configuration before any further edit. Ghost cleanup is unconfirmed; never replay marking to release it.");
            return result;
        }
    }

    private static void preflight(Object subpanel) throws NoSuchMethodException {
        if (subpanel == null) return;
        subpanel.getClass().getMethod("mouseUp", int.class, int.class, int.class);
        if (ExpandedGuiApi.call(subpanel, "getSlotMouseOver", -1, -1) != null)
            throw new BridgeException("target_unverified", "NEI subpanel cancellation position unexpectedly resolves to a slot.");
    }

    private static void releaseSubpanel(Object subpanel) {
        if (subpanel != null) ExpandedGuiApi.call(subpanel, "mouseUp", -1, -1, 0);
    }

    private static final class PanelState {
        final Object panel;
        final Field dragged, mouseDown;
        PanelState(Object panel) throws ReflectiveOperationException {
            this.panel = panel;
            dragged = panel.getClass().getField("draggedStack");
            mouseDown = panel.getClass().getField("mouseDownSlot");
            if (dragged.getType() != ItemStack.class || mouseDown.getType() != int.class)
                throw new BridgeException("unsupported", "NEI phantom panel field types changed.");
        }
        boolean ghost() throws IllegalAccessException { return dragged.get(panel) != null; }
        JsonObject snapshot() throws IllegalAccessException {
            JsonObject result = new JsonObject(); result.addProperty("mouseDownSlot", mouseDown.getInt(panel));
            result.add("ghostItem", JsonData.item((ItemStack) dragged.get(panel))); return result;
        }
        void clear() throws IllegalAccessException { mouseDown.setInt(panel, -1); dragged.set(panel, null); }
    }
}
