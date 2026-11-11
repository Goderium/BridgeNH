package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.nei.NeiOverlayAdapter;
import com.Goderium.BridgeNH.client.nei.NeiUiAccess;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.awt.Point;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;

/** Current native layout only. Never selects a hover slot, rebuilds a layout or draws. */
@com.google.common.annotations.VisibleForTesting
public final class GuiPointerLocator {
    private static final String GUI = "codechicken.nei.recipe.GuiRecipe";
    static Target locate(GuiScreen screen, JsonObject args) {
        String source = args.has("source") ? JsonData.string(args, "source") : "coordinates";
        if ("coordinates".equals(source)) return new Target(JsonData.integer(args, "x", 0, screen.width - 1), JsonData.integer(args, "y", 0, screen.height - 1), "explicit_scaled_gui_coordinates", () -> true);
        if ("slot".equals(source)) {
            if (!(screen instanceof GuiContainer)) throw new BridgeException("unsupported", "Current screen has no ordinary container slots.");
            int wanted = JsonData.integer(args, "slot", 0, 4095); GuiContainer gui = (GuiContainer) screen;
            if (gui.inventorySlots == null || gui.inventorySlots.inventorySlots == null || gui.inventorySlots.inventorySlots.size() > 4096) throw new BridgeException("unsupported", "Bounded actual container slots unavailable.");
            net.minecraft.inventory.Container actualContainer = gui.inventorySlots;
            for (Object value : gui.inventorySlots.inventorySlots) if (value instanceof Slot && ((Slot) value).slotNumber == wanted) {
                Slot slot = (Slot) value; int left = origin(gui, true), top = origin(gui, false);
                int px = left + slot.xDisplayPosition + 8, py = top + slot.yDisplayPosition + 8;
                Target target = new Target(px, py, "actual_container_slot_and_fixed_native_gui_origin", () -> gui.inventorySlots == actualContainer && actualContainer.inventorySlots.contains(slot) && slot.slotNumber == wanted && origin(gui, true) + slot.xDisplayPosition + 8 == px && origin(gui, false) + slot.yDisplayPosition + 8 == py);
                target.data.addProperty("slot", wanted); rectangle(target.data, left + slot.xDisplayPosition, top + slot.yDisplayPosition, 16, 16); return target;
            }
            throw new BridgeException("not_found", "Actual slotNumber is absent.");
        }
        if (!"recipe".equals(source)) throw new BridgeException("invalid_args", "source must be coordinates, recipe or slot.");
        return recipe(screen, args);
    }
    @com.google.common.annotations.VisibleForTesting
    public static Target recipe(Object screen, JsonObject args) {
        if (!NeiUiAccess.is(screen, GUI)) throw new BridgeException("unsupported", "Recipe pointer requires the current native NEI recipe page.");
        ClassLoader loader = screen.getClass().getClassLoader(); int recipe = JsonData.integer(args, "recipeIndex", 0, Integer.MAX_VALUE), positionIndex = args.has("positionIndex") ? JsonData.integer(args, "positionIndex", 0, Integer.MAX_VALUE) : 0;
        String kind = args.has("positionKind") ? JsonData.string(args, "positionKind") : "inputs";
        if (!Arrays.asList("inputs", "outputs", "other").contains(kind)) throw new BridgeException("invalid_args", "Unknown positionKind.");
        Object container = NeiUiAccess.get(loader, GUI, screen, "container"), raw = call(loader, "codechicken.nei.WidgetContainer", container, "getWidgets");
        if (!(raw instanceof List<?>) || ((List<?>) raw).size() > 64) throw new BridgeException("unsupported", "Bounded current native recipe widgets unavailable.");
        Target selected = null;
        for (Object widget : (List<?>) raw) {
            String WIDGET = NeiOverlayAdapter.widgetOwner(widget);
            if (WIDGET == null) continue;
            Object ref = call(loader, WIDGET, widget, "getRecipeHandlerRef");
            if (number(loader, "codechicken.nei.recipe.RecipeHandlerRef", ref, "recipeIndex") != recipe) continue;
            Object handler = NeiUiAccess.publicField(loader, "codechicken.nei.recipe.RecipeHandlerRef", ref, "handler");
            if (handler != call(loader, GUI, screen, "getHandler")) continue;
            Object selectedPositions;
            if (WIDGET.equals("codechicken.nei.recipe.widget.RecipeWidget")) {
                Object group = NeiUiAccess.get(loader, WIDGET, widget, "group");
                int member = ((Number) NeiUiAccess.get(loader, WIDGET, widget, "activeMember")).intValue();
                // Public group access returns the actual rendered member cache, not a newly generated handler layout.
                selectedPositions = NeiUiAccess.publicCall(loader, "codechicken.nei.recipe.widget.RecipeGroup", group,
                        "inputs".equals(kind) ? "getInputs" : "getOutputs", new Class<?>[] {int.class}, member);
                if ("other".equals(kind) && call(loader, "codechicken.nei.recipe.IRecipeHandler", handler, "getResultStack", recipe) != null)
                    throw new BridgeException("unsupported", "Auxiliary stack layout is not exposed by this widget's public group API.");
            } else {
                Object positions = "inputs".equals(kind) ? call(loader, "codechicken.nei.recipe.IRecipeHandler", handler, "getIngredientStacks", recipe) : "other".equals(kind) ? call(loader, "codechicken.nei.recipe.IRecipeHandler", handler, "getOtherStacks", recipe) : call(loader, "codechicken.nei.recipe.IRecipeHandler", handler, "getResultStack", recipe);
                selectedPositions = "outputs".equals(kind) ? positions == null ? call(loader, "codechicken.nei.recipe.IRecipeHandler", handler, "getOtherStacks", recipe) : Collections.singletonList(positions) : positions;
            }
            if (!(selectedPositions instanceof List<?>) || ((List<?>) selectedPositions).size() > 4096) throw new BridgeException("unsupported", "Bounded native position collection is unavailable.");
            List<?> stacks = (List<?>) selectedPositions;
            if (positionIndex >= stacks.size()) throw new BridgeException("not_found", "positionIndex is absent from the visible native recipe.");
            Object position = stacks.get(positionIndex), info = call(loader, WIDGET, widget, "getHandlerInfo");
            if (!NeiUiAccess.is(position, "codechicken.nei.PositionedStack") || info == null) throw new BridgeException("unsupported", "Native positioned stack or handler layout is unavailable.");
            int x = number(loader, "codechicken.nei.Widget", widget, "x") + number(loader, "codechicken.nei.PositionedStack", position, "relx"), y = number(loader, "codechicken.nei.Widget", widget, "y") + ((Number) call(loader, "codechicken.nei.recipe.HandlerInfo", info, "getYShift")).intValue() + number(loader, "codechicken.nei.PositionedStack", position, "rely");
            int width = number(loader, "codechicken.nei.PositionedStack", position, "width"), height = number(loader, "codechicken.nei.PositionedStack", position, "height");
            if (width < 1 || height < 1 || width > 32767 || height > 32767) throw new BridgeException("unsupported", "Native position rectangle is invalid.");
            int px = x + width / 2, py = y + height / 2;
            if (call(loader, "codechicken.nei.WidgetContainer", container, "getWidgetUnderMouse", px, py) != widget || call(loader, WIDGET, widget, "getPositionedStackMouseOver", px, py) != position) throw new BridgeException("target_unverified", "Native layout hit-test does not select the requested position; use explicit coordinates if desired.");
            if (selected != null) throw new BridgeException("target_unverified", "Visible original recipe index matches more than one native widget.");
            Target target = new Target(px, py, "native_NEIRecipeWidget_origin_HandlerInfo_yShift_PositionedStack_hit_test", () -> {
                Object current = call(loader, "codechicken.nei.WidgetContainer", container, "getWidgets");
                Object liveRef = call(loader, WIDGET, widget, "getRecipeHandlerRef");
                return NeiUiAccess.get(loader, GUI, screen, "container") == container && current instanceof List<?> && ((List<?>) current).size() <= 64 && ((List<?>) current).contains(widget)
                        && number(loader, "codechicken.nei.recipe.RecipeHandlerRef", liveRef, "recipeIndex") == recipe && NeiUiAccess.publicField(loader, "codechicken.nei.recipe.RecipeHandlerRef", liveRef, "handler") == handler
                        && call(loader, "codechicken.nei.WidgetContainer", container, "getWidgetUnderMouse", px, py) == widget && call(loader, WIDGET, widget, "getPositionedStackMouseOver", px, py) == position
                        && number(loader, "codechicken.nei.Widget", widget, "x") + number(loader, "codechicken.nei.PositionedStack", position, "relx") == x
                        && number(loader, "codechicken.nei.Widget", widget, "y") + ((Number) call(loader, "codechicken.nei.recipe.HandlerInfo", info, "getYShift")).intValue() + number(loader, "codechicken.nei.PositionedStack", position, "rely") == y
                        && number(loader, "codechicken.nei.PositionedStack", position, "width") == width && number(loader, "codechicken.nei.PositionedStack", position, "height") == height;
            });
            target.data.addProperty("recipeIndex", recipe); target.data.addProperty("positionKind", kind); target.data.addProperty("positionIndex", positionIndex); rectangle(target.data, x, y, width, height); selected = target;
        }
        if (selected == null) throw new BridgeException("not_found", "Original recipeIndex has no currently visible native recipe widget; no navigation or guessed origin was used.");
        return selected;
    }
    private static void rectangle(JsonObject out, int x, int y, int w, int h) { JsonObject rect = new JsonObject(); rect.addProperty("x", x); rect.addProperty("y", y); rect.addProperty("width", w); rect.addProperty("height", h); out.add("observedRectangle", rect); }
    private static int number(ClassLoader loader, String owner, Object object, String name) { return ((Number) NeiUiAccess.publicField(loader, owner, object, name)).intValue(); }
    private static int origin(GuiContainer gui, boolean left) {
        for (String name : left ? new String[] {"guiLeft", "field_147003_i"} : new String[] {"guiTop", "field_147009_r"}) {
            try { return ((Number) NeiUiAccess.get(gui.getClass().getClassLoader(), "net.minecraft.client.gui.inventory.GuiContainer", gui, name)).intValue(); }
            catch (BridgeException unavailable) { }
        }
        throw new BridgeException("unsupported", "Actual fixed GuiContainer origin fields are unavailable.");
    }
    private static Object call(ClassLoader loader, String owner, Object object, String name, int... values) { Class<?>[] types = new Class<?>[values.length]; Object[] args = new Object[values.length]; for (int i = 0; i < values.length; i++) { types[i] = int.class; args[i] = values[i]; } return NeiUiAccess.publicCall(loader, owner, object, name, types, args); }
    @com.google.common.annotations.VisibleForTesting
    public static final class Target {
        final Point point; final JsonObject data = new JsonObject(); @com.google.common.annotations.VisibleForTesting
        public final java.util.function.BooleanSupplier valid;
        Target(int x, int y, String source, java.util.function.BooleanSupplier valid) { point = new Point(x, y); this.valid = valid; data.addProperty("source", source); data.addProperty("x", x); data.addProperty("y", y); data.addProperty("selectionMutation", false); data.addProperty("itemIdentity", "Native later completed-frame item is authoritative; this receipt resolves a position only."); }
    }
}
