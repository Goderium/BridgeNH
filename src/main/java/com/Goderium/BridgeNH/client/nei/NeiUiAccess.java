package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Audited NEI 2.8.145 fields only. Read-only fallback, with cached accessors and no object graph traversal. */
public final class NeiUiAccess {
    private static final Map<String, List<String>> ALLOWED = new HashMap<String, List<String>>();
    private static final Map<Class<?>, Map<String, Field>> CACHE = new HashMap<Class<?>, Map<String, Field>>();
    private static final Map<Class<?>, Map<String, Field>> PUBLIC_FIELDS = new HashMap<Class<?>, Map<String, Field>>();
    private static final Map<Class<?>, Map<String, Method>> PUBLIC_METHODS = new HashMap<Class<?>, Map<String, Method>>();
    static {
        allow("codechicken.nei.ItemsPanelGrid", "gridMask");
        allow("codechicken.nei.ItemsGrid", "invalidSlotMap");
        allow("codechicken.nei.AbstractSubpanel", "grid");
        allow("codechicken.nei.ItemCraftablesPanel", "availableRecipes");
        allow("codechicken.nei.ItemHistoryPanel$1", "gridMask");
        allow("codechicken.nei.ItemCraftablesPanel$2", "gridMask");
        allow("codechicken.nei.bookmark.BookmarkGrid", "gridGenerator", "bookmarkItems", "groups");
        allow("codechicken.nei.bookmark.BookmarkGridGenerator", "gridMask");
        allow("codechicken.nei.recipe.GuiRecipe", "container", "recipeTabs", "searchField", "handlerPages");
        allow("codechicken.nei.recipe.widget.RecipeWidget", "group", "activeMember");
        allow("net.minecraft.client.gui.inventory.GuiContainer", "guiLeft", "field_147003_i", "guiTop", "field_147009_r");
        allow("codechicken.nei.recipe.GuiRecipeCatalyst", "items");
        allow("codechicken.nei.LayoutManager", "drawWidgets", "controlWidgets");
        allow("codechicken.nei.SubsetWidget", "tags", "root", "hoverStack", "hoverTag", "hiddenItems");
        allow("codechicken.nei.SubsetWidget$SubsetTag", "selectedChild", "visible", "fullname", "path", "parentPath", "state", "items", "children");
        allow("codechicken.nei.bookmark.tree.GuiCraftingTree", "grid", "groupId", "math", "collapsedRecipes", "statsPanel", "canvas", "statsPanelVisible", "searchField", "uiState", "toolbar", "hoveredNode");
        allow("codechicken.nei.bookmark.tree.CraftingTreeCanvas", "camera", "graph", "pointByNode", "rows", "treeWidth", "treeHeight");
        allow("codechicken.nei.bookmark.tree.CraftingTreeState", "statsPanelVisible", "visibleStatsSections", "useInventorySnapshot", "collapseMode", "collapsedItems", "replaceRecipeNode");
        allow("codechicken.nei.bookmark.tree.ItemTreeSlot", "ingrItem", "prefItem", "ingeritRequestedAmount", "currentRequestedAmount", "requestedAmount", "containerAmount", "producedAmount", "leftAmount", "itemStack", "isFluidDisplay", "isContainerItem", "multiplier");
        allow("codechicken.nei.bookmark.tree.StatsPanel$SectionLabel", "text");
        allow("codechicken.nei.bookmark.tree.StatsPanel$ItemStatsSlot", "stack", "stackSize", "recipeId", "multiplier", "highlight");
        allow("codechicken.nei.bookmark.tree.StatsPanel$HandlerStatsSlot", "handlerName", "stats", "highlight");
        allow("codechicken.nei.bookmark.tree.StatsPanel$HandlerStats", "iterations", "recipeCount");
        allow("codechicken.nei.config.GuiOptionList", "optionList", "world", "slot");
        allow("gregtech.api.recipe.RecipeMetadataKey", "identifier", "clazz");
    }
    private static void allow(String owner, String... fields) { ALLOWED.put(owner, Arrays.asList(fields)); }
    public static Object get(ClassLoader loader, String owner, Object target, String name) {
        List<String> names = ALLOWED.get(owner);
        if (names == null || !names.contains(name)) throw new BridgeException("unsupported", "NEI fallback is not in the fixed field allowlist.");
        try {
            Class<?> api = Class.forName(owner, false, loader); Field field;
            synchronized (CACHE) {
                Map<String, Field> fields = CACHE.get(api);
                if (fields == null) { fields = new HashMap<String, Field>(); CACHE.put(api, fields); }
                field = fields.get(name);
                if (field == null) { field = api.getDeclaredField(name); field.setAccessible(true); fields.put(name, field); }
            }
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            throw new BridgeException("unsupported", "Audited NEI 2.8.145 field unavailable: " + owner + "." + name);
        }
    }
    static Object optional(ClassLoader loader, String owner, Object target, String name) { try { return get(loader, owner, target, name); } catch (BridgeException error) { return null; } }
    public static Object publicField(ClassLoader loader, String owner, Object target, String name) {
        try {
            Class<?> api = Class.forName(owner, false, loader); Field field;
            synchronized (PUBLIC_FIELDS) {
                Map<String, Field> fields = PUBLIC_FIELDS.get(api); if (fields == null) { fields = new HashMap<String, Field>(); PUBLIC_FIELDS.put(api, fields); }
                field = fields.get(name); if (field == null) { field = api.getField(name); fields.put(name, field); }
            }
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { throw new BridgeException("unsupported", "Public model field unavailable: " + owner + "." + name); }
    }
    public static Object publicCall(ClassLoader loader, String owner, Object target, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> api = Class.forName(owner, false, loader); Method method; String key = name + Arrays.toString(types);
            synchronized (PUBLIC_METHODS) {
                Map<String, Method> methods = PUBLIC_METHODS.get(api); if (methods == null) { methods = new HashMap<String, Method>(); PUBLIC_METHODS.put(api, methods); }
                method = methods.get(key); if (method == null) { method = api.getMethod(name, types); methods.put(key, method); }
            }
            return method.invoke(target, args);
        } catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Model getter " + name + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { throw new BridgeException("unsupported", "Public model method unavailable: " + owner + "." + name); }
    }
    static List<?> mask(Object grid, ClassLoader loader) {
        if (grid == null) return null;
        Object value = null;
        if (is(grid, "codechicken.nei.bookmark.BookmarkGrid")) {
            Object generator = get(loader, "codechicken.nei.bookmark.BookmarkGrid", grid, "gridGenerator");
            value = get(loader, "codechicken.nei.bookmark.BookmarkGridGenerator", generator, "gridMask");
        } else for (String owner : new String[] {"codechicken.nei.ItemsPanelGrid", "codechicken.nei.ItemHistoryPanel$1", "codechicken.nei.ItemCraftablesPanel$2"}) {
            if (is(grid, owner)) { value = get(loader, owner, grid, "gridMask"); break; }
        }
        // Null means NEI has not generated its display cache. Do not trigger getMask/update/generate.
        return value instanceof List<?> ? (List<?>) value : null;
    }
    public static boolean is(Object object, String owner) { if (object == null) return false; for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) if (type.getName().equals(owner)) return true; return false; }
}
