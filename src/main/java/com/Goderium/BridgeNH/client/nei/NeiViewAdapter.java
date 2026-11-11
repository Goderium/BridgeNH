package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import com.Goderium.BridgeNH.common.structure.StructureAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.awt.Point;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Bounded current NEI model reads. Never navigates, renders, updates or changes modifiers. */
public final class NeiViewAdapter {
    private static final String P = "codechicken.nei.", GUI = P + "recipe.GuiRecipe";
    private static final String HANDLER = P + "recipe.IRecipeHandler", PANEL = P + "PanelWidget", GRID = P + "ItemsGrid";
    private static final int BYTE_LIMIT = 128 * 1024, CONTEXT_REFS = 256;
    private static Context context;

    public static JsonObject inspect(Minecraft client, GuiScreen screen, JsonObject args, String sessionId) {
        TooltipModifiers.bindClientThread();
        TooltipModifiers.probe();
        NeiPreviewResources.ensureTracking(client);
        return read(screen, client.thePlayer, args, sessionId, System.nanoTime());
    }

    /** An action must use a context captured from this exact current screen, session and view. */
    public static void validateContext(Object screen, JsonObject args, String sessionId) {
        String screenId = JsonData.string(args, "screenId"), token = JsonData.string(args, "contextToken");
        if (screenId.isEmpty() || token.isEmpty() || token.length() > 128)
            throw new BridgeException("invalid_args", "screenId and a 1 to 128 character contextToken are required.");
        Environment env = new Environment(screen);
        if (context == null || !context.matches(screen, sessionId, screenId, env.signature()) || !context.token.equals(token))
            throw new BridgeException("stale_context", "NEI view changed; read the current view before controlling its preview.");
    }

    public static void invalidateRendered() { context = null; NeiTextCapture.clear(); NeiHoverCapture.clear(); NeiNaturalRenderCapture.clear(); }

    /** Resolve only an actually read panel entry, with its full identity and current view intact. */
    public static ItemStack resolvePanelItem(Object screen, String id) {
        if (context == null || context.screen != screen || id.isEmpty())
            throw new BridgeException("stale_resource", "Read the current NEI panel before using its neiItemId.");
        Environment env = new Environment(screen);
        if (!context.signature.equals(env.signature())) throw new BridgeException("stale_resource", "NEI view changed; read its current panel before selecting an item.");
        for (PanelItem source : context.panelItems.values()) {
            if (!source.id.equals(id)) continue;
            ItemStack current = panelItem(env, env.panel(source.panel), source.index);
            if (!ItemStack.areItemStacksEqual(source.stack, current)) throw new BridgeException("stale_resource", "The observed NEI item or full NBT changed.");
            return current.copy();
        }
        throw new BridgeException("stale_resource", "The observed NEI item expired; read its current panel again.");
    }

    /** Native panel middle-click lifecycle, after an exact visible source and hit-test check. */
    static JsonObject pickGhost(Object screen, String id) {
        ItemStack expected = resolvePanelItem(screen, id);
        Environment env = new Environment(screen);
        PanelItem source = null;
        for (PanelItem candidate : context.panelItems.values()) if (candidate.id.equals(id)) source = candidate;
        if (source == null || !"item".equals(source.panel))
            throw new BridgeException("unsupported", "Native phantom pickup currently requires an observed visible item-panel entry; bookmarks use semantic ghost sources.");
        Panel panel = env.panel(source.panel);
        Object controls = NeiUiAccess.optional(env.loader, P + "LayoutManager", null, "controlWidgets");
        if (!layoutContains(controls, panel.object))
            throw new BridgeException("target_unverified", "The observed NEI item panel is not in the current native control layout.");
        int slotIndex = -1;
        for (Object slot : panel.mask) if (slot != null && Panel.itemIndex(slot, env.loader) == source.index
                && ItemStack.areItemStacksEqual(expected, (ItemStack) Panel.stack(slot, env.loader))) {
            if (slotIndex != -1) throw new BridgeException("target_unverified", "The visible NEI source is ambiguous.");
            slotIndex = Panel.slotIndex(slot, env.loader);
        }
        if (slotIndex < 0) throw new BridgeException("target_unverified", "The selected NEI item is not currently visible; read a visible panel page.");
        Object rect = call(env.loader, GRID, panel.grid, "getSlotRect", new Class<?>[] {int.class}, slotIndex);
        JsonObject bounds = rectangle(rect, env.loader).getAsJsonObject();
        int x = bounds.get("x").getAsInt() + bounds.get("w").getAsInt() / 2;
        int y = bounds.get("y").getAsInt() + bounds.get("h").getAsInt() / 2;
        Object hit = ExpandedGuiApi.call(panel.grid, "getSlotMouseOver", x, y);
        if (hit == null || Panel.itemIndex(hit, env.loader) != source.index
                || !ItemStack.areItemStacksEqual(expected, (ItemStack) Panel.stack(hit, env.loader)))
            throw new BridgeException("target_unverified", "The current native NEI hit-test differs from the selected source.");
        return NeiGhostDragAdapter.pick(panel.object, env.panel("bookmarks").object, x, y, expected);
    }

    private static boolean layoutContains(Object widgets, Object target) {
        if (!(widgets instanceof java.util.Collection<?>) || target == null) return false;
        // WidgetZOrder compares equal-z widgets as unequal, including the same object.
        // NEI dispatches by iteration too; TreeSet.contains cannot establish membership.
        int inspected = 0;
        for (Object widget : (java.util.Collection<?>) widgets) {
            if (++inspected > 4096) return false;
            if (widget == target) return true;
        }
        return false;
    }

    private static ItemStack panelItem(Environment env, Panel panel, int index) {
        Object value;
        if (index < 0) return null;
        if ("bookmarks".equals(panel.name)) {
            Object bookmark = call(env.loader, P + "bookmark.BookmarkGrid", panel.grid, "getBookmarkItem", new Class<?>[] {int.class}, index);
            value = bookmark == null ? null : optionalField(env.loader, P + "bookmark.BookmarkItem", bookmark, "itemStack");
        } else value = index < panel.items.size() ? panel.items.get(index) : null;
        return value instanceof ItemStack ? (ItemStack) value : null;
    }

    private static String rememberPanelItem(Environment env, Panel panel, int index, ItemStack stack) {
        // Unsized bookmarks keep a real template at count zero. Preserve that observed count;
        // existing take/ghost consumers set their requested quantity only on the resolved copy.
        if (stack == null || stack.getItem() == null || stack.stackSize < 0
                || (stack.stackSize == 0 && !"bookmarks".equals(panel.name))
                || !ItemStack.areItemStacksEqual(stack, panelItem(env, panel, index))) return "";
        String key = panel.name + ":" + index;
        PanelItem known = context.panelItems.get(key);
        if (known != null && ItemStack.areItemStacksEqual(known.stack, stack)) return known.id;
        if (context.panelItems.size() >= 256) context.panelItems.remove(context.panelItems.keySet().iterator().next());
        PanelItem source = new PanelItem(panel.name, index, stack); context.panelItems.put(key, source); return source.id;
    }

    /** The explicit finite capture action changes its generation, and returns the new paging context directly. */
    public static String refreshCaptureContext(Object screen, JsonObject args, String sessionId) {
        context = new Context(screen, sessionId, JsonData.string(args, "screenId"), new Environment(screen).signature());
        return context.token;
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object screen, EntityPlayer player, JsonObject args, String sessionId, long started) {
        Request request = new Request(args);
        Environment env = new Environment(screen);
        String signature = env.signature();
        String screenId = JsonData.string(args, "screenId");
        if (screenId.isEmpty()) throw new BridgeException("invalid_args", "screenId must be nonempty.");
        if (args.has("contextToken")) {
            String supplied = JsonData.string(args, "contextToken");
            if (supplied.isEmpty() || supplied.length() > 128) throw new BridgeException("invalid_args", "contextToken must contain 1 to 128 characters.");
            if (context == null || !context.matches(screen, sessionId, screenId, signature) || !context.token.equals(supplied))
                throw new BridgeException("stale_context", "NEI context changed or token expired. Read offset zero without a token to capture the current view.");
        } else if (request.offset > 0) throw new BridgeException("invalid_args", "offset > 0 requires contextToken.");
        if (context == null || !context.matches(screen, sessionId, screenId, signature))
            context = new Context(screen, sessionId, screenId, signature);
        boolean rendered = NeiRenderedAdapter.requested(args), hover = NeiHoverAdapter.requested(args);
        if ((rendered || hover) && !args.has("contextToken")) {
            context = new Context(screen, sessionId, screenId, signature);
            context.rendered = NeiTextCapture.latest(screen, NeiRenderCapture.marker(screen));
            context.hover = NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen));
        } else if (rendered && context.rendered == null)
            context.rendered = NeiTextCapture.latest(screen, NeiRenderCapture.marker(screen));
        else if (hover && context.hover == null) context.hover = NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen));
        String tooltipResource = tooltipResource(args);
        if (!tooltipResource.isEmpty()) {
            String tooltipSignature = TooltipModifiers.signature(args) + "/advanced=" + request.advanced;
            String previous = context.tooltipContexts.get(tooltipResource);
            if (previous != null && !previous.equals(tooltipSignature)) {
                if (args.has("contextToken")) throw new BridgeException("stale_context", "Tooltip modifiers or advanced mode changed; recapture offset zero.");
                context = new Context(screen, sessionId, screenId, signature);
            }
            if (context.tooltipContexts.size() >= 64 && !context.tooltipContexts.containsKey(tooltipResource)) context.tooltipContexts.clear();
            context.tooltipContexts.put(tooltipResource, tooltipSignature);
        }
        String resource = recipeResource(env, args, request), resourceSignature = resource.isEmpty() ? "" : recipeResourceSignature(env, args, request);
        if (!resource.isEmpty()) { String previous = context.resources.get(resource); if (previous != null && !previous.equals(resourceSignature)) { if (args.has("contextToken")) throw new BridgeException("stale_context", "Selected recipe metadata changed; recapture offset zero."); context = new Context(screen, sessionId, screenId, signature); } context.resources.put(resource, resourceSignature); }
        String token = context.token;
        Read result = new Read(request, started);
        result.rendered = context.rendered;
        result.hover = context.hover;
        switch (request.section) {
            case "summary": summary(env, player, result); break;
            case "handlers": handlers(env, result); break;
            case "recipes": case "recipe": recipes(env, player, args, result); break;
            case "panel": panel(env, player, result); break;
            case "settings": settings(env, result); break;
            case "tree": tree(env, player, result); break;
            case "tooltip": tooltip(env, player, result); break;
            case "preview": NeiDetailAdapter.mergePreview(env, result, false); break;
            case "rendered": NeiRenderedAdapter.read(args, result); break;
            case "detail": NeiDetailAdapter.read(env, player, args, result); break;
            case "coverage": result.page(coverage(env, request), "entries"); break;
            default: throw new BridgeException("invalid_args", "Unknown NEI section.");
        }
        if (!signature.equals(env.signature())) throw new BridgeException("stale_context", "NEI public view changed during the read; recapture offset zero.");
        if (!resource.isEmpty() && !resourceSignature.equals(recipeResourceSignature(env, args, request))) throw new BridgeException("stale_context", "Selected recipe metadata changed during the read.");
        JsonObject response = new JsonObject();
        response.addProperty("schemaVersion", 1);
        response.addProperty("screenId", screenId); response.addProperty("section", request.section);
        response.addProperty("contextToken", token); response.addProperty("status", result.status);
        response.addProperty("offset", request.offset); response.addProperty("limit", result.effectiveLimit);
        response.addProperty("total", result.total); response.addProperty("nextOffset", result.next);
        response.addProperty("hasMore", result.next < result.total);
        response.addProperty("complete", result.next >= result.total && !result.partial && !result.byteLimited);
        response.addProperty("byteLimited", result.byteLimited); response.addProperty("softBudgetPartial", result.timedOut);
        response.addProperty("nonAtomic", true); response.add("data", result.data);
        response.add("coverage", coverage(env, request)); response.add("limits", limits());
        response.add("budget", StructureAdapter.budget(started));
        if (size(response) > BYTE_LIMIT) throw new BridgeException("too_large", "NEI view exceeded the bounded result byte limit.");
        return response;
    }

    private static void summary(Environment env, EntityPlayer player, Read out) {
        out.data.addProperty("screenClass", env.screen.getClass().getName());
        out.data.addProperty("neiRecipeScreen", env.recipeScreen);
        out.data.addProperty("handlerCount", env.handlers.size());
        out.data.addProperty("visibleRecipeCount", env.refs.size());
        if (env.recipeScreen) {
            out.data.addProperty("handlerIndex", env.handlerIndex); out.data.addProperty("page", env.recipePage);
            out.data.add("handler", identity(env.handler, env.loader));
            JsonArray refs = new JsonArray();
            for (int i = 0; i < Math.min(env.refs.size(), CONTEXT_REFS); i++) refs.add(new JsonPrimitive((Number) env.refs.get(i)));
            out.data.add("visibleRecipeIndices", refs); out.data.addProperty("visibleRefsTruncated", env.refs.size() > CONTEXT_REFS);
            if (!env.refs.isEmpty() && env.refs.get(0) instanceof Number) {
                int ref = ((Number) env.refs.get(0)).intValue();
                out.data.addProperty("researchLinksRecipeIndex", ref);
                out.data.add("researchLinks", NeiRecipeSemantics.researchLinks(env.handler, ref, player, out.started));
            }
        }
        out.data.add("search", env.search); out.data.add("modifiers", NeiDataAdapter.modifiers());
        out.data.add("tooltipModifierHook", TooltipModifiers.diagnostics());
        JsonArray panels = new JsonArray();
        for (Panel panel : env.panels) panels.add(panel.summary());
        out.data.add("panels", panels);
        out.data.add("focusedWidget", widget(env.focused, env.loader));
        Object overlay = optionalField(env.loader, P + "LayoutManager", null, "overlayRenderer");
        out.data.addProperty("overlayRendererClass", overlay == null ? "" : overlay.getClass().getName());
        out.data.addProperty("currentTreeScreen", treeScreen(env.screen));
        out.single();
    }

    private static void handlers(Environment env, Read out) {
        if (!env.recipeScreen) { out.absent("Handlers are present only on an open GuiRecipe."); return; }
        out.total = env.handlers.size(); out.next = Math.min(out.request.offset, out.total);
        JsonArray entries = new JsonArray(); out.data.add("handlers", entries);
        while (out.next < out.total && entries.size() < out.request.limit && out.progress(entries.size())) {
            JsonObject entry = identity(env.handlers.get(out.next), env.loader);
            entry.addProperty("handlerIndex", out.next); entry.addProperty("selected", out.next == env.handlerIndex);
            if (!out.add(entries, entry)) break;
            out.next++;
        }
    }

    private static JsonObject identity(Object handler, ClassLoader loader) {
        JsonObject result = new JsonObject();
        if (handler == null) return result;
        result.addProperty("class", handler.getClass().getName());
        result.add("id", primitive(call(loader, HANDLER, handler, "getHandlerId")));
        result.add("name", primitive(call(loader, HANDLER, handler, "getRecipeName")));
        result.add("recipeCount", primitive(call(loader, HANDLER, handler, "numRecipes")));
        try {
            Object info = call(loader, P + "recipe.GuiRecipeTab", null, "getHandlerInfo", new Class<?>[] {type(loader, HANDLER)}, handler);
            JsonObject metadata = new JsonObject();
            for (String getter : new String[] {"getHandlerName", "getModName", "getModId", "getWidth", "getHeight", "getYShift", "isMultipleWidgetsAllowed", "getShowFavoritesButton", "getShowOverlayButton", "getShowBadge", "isAllowOverflowX", "isAllowOverflowY"})
                metadata.add(getter, primitive(call(loader, P + "recipe.HandlerInfo", info, getter)));
            Object icon = call(loader, P + "recipe.HandlerInfo", info, "getItemStack");
            metadata.add("icon", icon instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) icon, null, false, false) : JsonNull.INSTANCE);
            result.add("handlerInfo", metadata);
        } catch (BridgeException error) { result.addProperty("handlerInfoStatus", "unsupported"); }
        return result;
    }

    private static void recipes(Environment env, EntityPlayer player, JsonObject args, Read out) {
        if (!env.recipeScreen) { out.absent("Open GuiRecipe to read its current handlers."); return; }
        int index = out.request.handlerIndex < 0 ? env.handlerIndex : out.request.handlerIndex;
        if (index >= env.handlers.size()) throw new BridgeException("not_found", "handlerIndex is outside currenthandlers.");
        Object handler = env.handlers.get(index);
        int count = ((Number) call(env.loader, HANDLER, handler, "numRecipes")).intValue();
        if (count < 0) throw new BridgeException("adapter_error", "Handler returned negative recipe count.");
        List<?> refs;
        if ("recipe".equals(out.request.section)) {
            if (out.request.recipeIndex >= count) throw new BridgeException("not_found", "recipeIndex is outside the original handler's recipes.");
            refs = Collections.singletonList(out.request.recipeIndex);
        } else if ("handler".equals(out.request.scope)) {
            final int total = count;
            refs = new AbstractList<Integer>() { public Integer get(int i) { return i; } public int size() { return total; } };
        } else {
            if (index != env.handlerIndex) throw new BridgeException("invalid_args", "visible scope requires the current handlerIndex.");
            refs = env.refs;
        }
        JsonObject readArgs = new JsonObject();
        readArgs.addProperty("screenId", JsonData.string(args, "screenId")); readArgs.addProperty("offset", out.request.offset);
        readArgs.addProperty("limit", out.request.limit); readArgs.addProperty("includeTooltips", out.request.include);
        readArgs.addProperty("advanced", out.request.advanced);
        JsonObject data = NeiDataAdapter.read(env.screen, player, readArgs, out.started, handler, refs);
        out.total = refs.size(); out.next = data.get("nextOffset").getAsInt();
        out.byteLimited = data.get("byteLimited").getAsBoolean();
        out.data.add("handler", identity(handler, env.loader)); out.data.addProperty("handlerIndex", index);
        out.data.addProperty("scope", "recipe".equals(out.request.section) ? "original_index" : out.request.scope);
        JsonArray recipes = data.getAsJsonArray("recipes"); out.data.add("recipes", recipes);
        for (JsonElement element : recipes) {
            JsonObject recipe = element.getAsJsonObject(); int ref = recipe.get("recipeIndex").getAsInt();
            boolean visible = index == env.handlerIndex && env.refs.contains(ref);
            recipe.addProperty("visible", visible);
            recipe.add("semantics", NeiRecipeSemantics.read(handler, ref, 0, 4, out.started));
            recipe.add("diagram", NeiDiagramProvider.read(handler, ref, 0, 7, out.started));
            if (visible) {
                Object position = optionalCall(env.loader, GUI, env.screen, "getRecipePosition", new Class<?>[] {int.class}, ref);
                if (position instanceof Point) { JsonObject origin = new JsonObject(); origin.addProperty("x", ((Point) position).x); origin.addProperty("y", ((Point) position).y); origin.addProperty("source", "GuiRecipe.getRecipePosition(originalIndex), relative to GUI origin"); recipe.add("origin", origin); }
            }
            if (recipe.get("positionsTruncated").getAsBoolean() || recipe.get("softBudgetPartial").getAsBoolean()) out.partial = true;
        }
        if (out.partial || out.byteLimited) out.status = "partial";
        out.data.add("modifiers", NeiDataAdapter.modifiers());
    }

    private static void panel(Environment env, EntityPlayer player, Read out) {
        Panel panel = env.panel(out.request.panel);
        if (panel.object == null) { out.absent(panel.error); return; }
        if (!panel.error.isEmpty()) { out.unsupported(panel.error); return; }
        out.data.add("panel", panel.summary()); out.data.addProperty("scope", out.request.scope);
        boolean visible = "visible".equals(out.request.scope);
        if (visible && panel.maskPending) { out.partial("Existing display layout/cache is pending; no NEI refresh or generation was invoked."); return; }
        if (panel.maskPending && "craftables".equals(panel.name)) out.partial("Craftables display cache is pending; all scope reads only the existing realItems model.");
        out.total = visible ? panel.mask.size() : panel.total; out.next = Math.min(out.request.offset, out.total);
        JsonArray entries = new JsonArray(); out.data.add("entries", entries);
        while (out.next < out.total && entries.size() < out.request.limit && out.progress(entries.size())) {
            JsonObject entry = new JsonObject(); entry.addProperty("offset", out.next);
            Object bookmark = null;
            ItemStack stack = null;
            if (visible) {
                Object slot = panel.mask.get(out.next);
                if (slot == null) { entry.addProperty("empty", true); }
                else {
                    entry.addProperty("slotIndex", Panel.slotIndex(slot, env.loader));
                    entry.addProperty("itemIndex", Panel.itemIndex(slot, env.loader));
                    Object value = Panel.stack(slot, env.loader);
                    if (value instanceof ItemStack) stack = (ItemStack) value;
                    Object rect = optionalCall(env.loader, GRID, panel.grid, "getSlotRect", new Class<?>[] {int.class}, entry.get("slotIndex").getAsInt());
                    entry.add("bounds", rectangle(rect, env.loader));
                    if ("bookmarks".equals(panel.name)) bookmark = call(env.loader, P + "bookmark.BookmarksGridSlot", slot, "getBookmarkItem");
                    else if (!(slot instanceof DerivedSlot)) for (String name : new String[] {"groupIndex", "displayName", "groupSize", "extended"}) {
                        Object valueField = optionalField(env.loader, P + "ItemsPanelGrid$ItemsPanelGridSlot", slot, name);
                        if (valueField != null) entry.add(name, primitive(valueField));
                    }
                }
            } else if ("bookmarks".equals(panel.name)) {
                bookmark = call(env.loader, P + "bookmark.BookmarkGrid", panel.grid, "getBookmarkItem", new Class<?>[] {int.class}, out.next);
                Object value = optionalField(env.loader, P + "bookmark.BookmarkItem", bookmark, "itemStack");
                if (value instanceof ItemStack) stack = (ItemStack) value;
            } else {
                Object value = panel.items.get(out.next); if (value instanceof ItemStack) stack = (ItemStack) value;
            }
            entry.add("item", stack == null ? JsonNull.INSTANCE : NeiDataAdapter.stack(stack, player, out.request.include, out.request.advanced));
            String itemId = rememberPanelItem(env, panel, visible && entry.has("itemIndex") ? entry.get("itemIndex").getAsInt() : visible ? -1 : out.next, stack);
            if (!itemId.isEmpty()) {
                entry.addProperty("neiItemId", itemId);
                JsonObject ghostSource = new JsonObject(); ghostSource.addProperty("neiItemId", itemId); entry.add("ghostSource", ghostSource);
            }
            if (bookmark != null) entry.add("bookmark", bookmark(bookmark, env.loader, player, out.request));
            if (!out.add(entries, entry)) break;
            out.next++;
        }
    }

    static JsonObject bookmark(Object item, ClassLoader loader, EntityPlayer player, Request request) {
        JsonObject result = new JsonObject(); String owner = P + "bookmark.BookmarkItem";
        for (String name : new String[] {"groupId", "type", "multiplier", "factor", "chance", "fluidCellAmount"}) result.add(name, primitive(field(loader, owner, item, name)));
        for (String getter : new String[] {"getAmount", "getMultiplier", "getStackSize"}) result.add(getter, primitive(call(loader, owner, item, getter)));
        result.add("recipeId", recipeId(field(loader, owner, item, "recipeId"), loader));
        Object permutations = field(loader, owner, item, "permutations");
        if (permutations instanceof Map<?, ?>) {
            Map<?, ?> values = (Map<?, ?>) permutations; result.addProperty("permutationsTotal", values.size());
            result.addProperty("permutationsStatus", "not_requested; detail/bookmark_permutations continues the original collection");
        }
        return result;
    }

    static JsonElement recipeId(Object value, ClassLoader loader) {
        if (value == null) return JsonNull.INSTANCE;
        Object json = optionalCall(loader, P + "recipe.Recipe$RecipeId", value, "toJsonObject");
        if (json instanceof JsonElement && ((JsonElement) json).toString().length() <= 4096) return (JsonElement) json;
        JsonObject result = new JsonObject(); result.addProperty("class", value.getClass().getName()); result.addProperty("status", "unsupported"); return result;
    }

    private static void settings(Environment env, Read out) {
        JsonObject values = new JsonObject(); out.data.add("values", values);
        for (String source : new String[] {"global", "world"}) {
            JsonObject info = new JsonObject();
            try {
                Object config = NeiDetailAdapter.config(env, source);
                Object children = call(env.loader, "codechicken.lib.config.ConfigTagParent", config, "childTagMap");
                info.addProperty("childCount", children instanceof Map<?, ?> ? ((Map<?, ?>) children).size() : 0);
                info.addProperty("status", "readable"); info.addProperty("continuation", "detail/settings settingSource=" + source + ", configPath selects existing tag");
            } catch (BridgeException error) { info.addProperty("status", "not_present"); info.addProperty("reason", error.getMessage()); }
            values.add(source, info);
        }
        out.data.add("search", env.search); JsonObject widgets = new JsonObject(); out.data.add("widgets", widgets);
        for (String name : new String[] {"quantity", "options", "bookmarksButton", "dropDown", "more", "less", "delete", "gamemode", "rain", "magnet", "heal"})
            widgets.add(name, widget(optionalField(env.loader, P + "LayoutManager", null, name), env.loader));
        out.single(); out.data.addProperty("source", "Existing ConfigTagParent.childTagMap; no default-tag creation/load/save. Values use detail/settings; current controls use detail/widgets; subset items use detail/subset_items.");
    }

    private static void tree(Environment env, EntityPlayer player, Read out) {
        out.data.addProperty("currentTreeScreen", treeScreen(env.screen));
        out.data.addProperty("source", "Active bookmark grid's existing BookmarkGroup.crafting; no graph recalculation.");
        if (treeScreen(env.screen)) {
            String owner = P + "bookmark.tree.GuiCraftingTree";
            out.data.add("groupId", primitive(NeiUiAccess.get(env.loader, owner, env.screen, "groupId")));
            out.data.add("statsPanelVisible", primitive(NeiUiAccess.get(env.loader, owner, env.screen, "statsPanelVisible")));
            Object state = NeiUiAccess.get(env.loader, owner, env.screen, "uiState");
            JsonObject ui = new JsonObject();
            if (state != null) for (String name : new String[] {"statsPanelVisible", "useInventorySnapshot", "collapseMode"}) ui.add(name, primitive(NeiUiAccess.get(env.loader, P + "bookmark.tree.CraftingTreeState", state, name)));
            out.data.add("uiState", ui);
            Object math = NeiUiAccess.get(env.loader, owner, env.screen, "math");
            if (math != null) for (String name : new String[] {"rootSlots", "items", "parentOf", "childrenOf"}) { Object value = field(env.loader, P + "bookmark.tree.CraftingTreeMath", math, name); out.data.addProperty(name + "Count", value instanceof List<?> ? ((List<?>) value).size() : value instanceof Map<?, ?> ? ((Map<?, ?>) value).size() : 0); }
            Object canvas = NeiUiAccess.get(env.loader, owner, env.screen, "canvas");
            if (canvas != null) {
                Object camera = NeiUiAccess.get(env.loader, P + "bookmark.tree.CraftingTreeCanvas", canvas, "camera"); JsonObject view = new JsonObject();
                if (camera != null) for (String name : new String[] {"widgetX", "widgetY", "widgetW", "widgetH", "scrollX", "scrollY", "zoomLevel"}) view.add(name, primitive(field(env.loader, P + "bookmark.tree.Camera", camera, name)));
                out.data.add("camera", view);
            }
            out.data.addProperty("source", "Audited NEI2.8.145 live tree fields, existing computed model only. detail/tree_items,tree_roots,tree_edges,tree_stats continue collections.");
            out.single(); return;
        }
        Panel panel = env.panel("bookmarks");
        if (panel.grid == null) { out.absent("No public active bookmark grid."); return; }
        Object group = call(env.loader, P + "bookmark.BookmarkGrid", panel.grid, "getGroup", new Class<?>[] {int.class}, out.request.groupId);
        if (group == null) { out.absent("Bookmark group not present."); return; }
        out.data.addProperty("groupId", out.request.groupId);
        for (String name : new String[] {"viewMode", "collapsed"}) out.data.add(name, primitive(field(env.loader, P + "bookmark.BookmarkGroup", group, name)));
        Object crafting = field(env.loader, P + "bookmark.BookmarkGroup", group, "crafting");
        if (crafting == null) { out.absent("Bookmark group has no existing crafting chain."); return; }
        Object calculated = field(env.loader, P + "bookmark.RecipeChainDetails", crafting, "calculatedItems");
        if (!(calculated instanceof Map<?, ?>)) throw new BridgeException("adapter_error", "Invalid public crafting chain.");
        Map<?, ?> nodes = (Map<?, ?>) calculated; JsonArray entries = new JsonArray(); out.data.add("nodes", entries);
        out.total = nodes.size(); out.next = Math.min(out.request.offset, out.total); int position = 0;
        for (Map.Entry<?, ?> entry : nodes.entrySet()) {
            if (position++ < out.request.offset) continue;
            if (entries.size() >= out.request.limit || !out.progress(entries.size())) break;
            JsonObject node = new JsonObject(); node.add("itemIndex", primitive(entry.getKey())); Object value = entry.getValue();
            String owner = P + "bookmark.RecipeChainDetails$BookmarkChainItem";
            for (String getter : new String[] {"getRealMultiplier", "getCalculatedMultiplier", "getShiftAmount"}) node.add(getter, primitive(call(env.loader, owner, value, getter)));
            node.add("calculatedType", primitive(field(env.loader, owner, value, "calculatedType")));
            Object item = call(env.loader, owner, value, "getItem"); node.add("bookmark", bookmark(item, env.loader, player, out.request));
            if (!out.add(entries, node)) break; out.next++;
        }
        for (String name : new String[] {"itemToRecipe", "outputRecipes", "recipeInMiddle", "recipeRelations"}) {
            Object value = field(env.loader, P + "bookmark.RecipeChainDetails", crafting, name);
            out.data.addProperty(name + "Count", value instanceof Map<?, ?> ? ((Map<?, ?>) value).size() : value instanceof java.util.Set<?> ? ((java.util.Set<?>) value).size() : 0);
        }
        out.partial("Existing bookmark chain nodes are readable; active tree canvas, camera, stats and full graph relations are unavailable here.");
    }

    private static void tooltip(Environment env, EntityPlayer player, Read out) {
        Object manager = call(env.loader, P + "LayoutManager", null, "instance");
        Object target = call(env.loader, P + "LayoutManager", manager, "getWidgetUnderMouse", new Class<?>[] {int.class, int.class}, out.request.x, out.request.y);
        if (target == null && env.recipeScreen) {
            Object container = NeiUiAccess.get(env.loader, GUI, env.screen, "container");
            target = call(env.loader, P + "WidgetContainer", container, "getWidgetUnderMouse", new Class<?>[] {int.class, int.class}, out.request.x, out.request.y);
        }
        out.data.add("widget", widget(target, env.loader)); out.data.add("modifiers", NeiDataAdapter.modifiers());
        out.data.addProperty("x", out.request.x); out.data.addProperty("y", out.request.y);
        List<String> tooltip = new ArrayList<String>(); ItemStack stack = null;
        if (target != null) {
            Object item = call(env.loader, P + "Widget", target, "getStackMouseOver", new Class<?>[] {int.class, int.class}, out.request.x, out.request.y);
            if (item instanceof ItemStack) { stack = (ItemStack) item; tooltip.addAll(stack.getTooltip(player, out.request.advanced)); }
            Object updated = env.recipeScreen && env.handler.getClass().getName().contains("DiagramGroup") ? tooltip : stack == null
                    ? call(env.loader, P + "Widget", target, "handleTooltip", new Class<?>[] {int.class, int.class, List.class}, out.request.x, out.request.y, tooltip)
                    : call(env.loader, P + "Widget", target, "handleItemTooltip", new Class<?>[] {ItemStack.class, int.class, int.class, List.class}, stack, out.request.x, out.request.y, tooltip);
            if (updated instanceof List<?>) GuiDataAdapter.text(out.data, (List<?>) updated);
        }
        out.data.add("item", stack == null ? JsonNull.INSTANCE : NeiDataAdapter.stack(stack, player, false, out.request.advanced));
        out.data.addProperty("source", "Current public LayoutManager widget lookup and actual stack/widget tooltip callbacks.");
        out.single();
        if (target == null && env.recipeScreen) out.partial("No current recipe widget at coordinates. GuiRecipe.getSlotAtPosition mutates activeStack and is deliberately not used.");
        else if (target == null) out.absent("No NEI public widget at coordinates.");
    }

    private static JsonArray coverage(Environment env, Request request) {
        JsonArray result = new JsonArray();
        cover(result, "handlers", env.recipeScreen ? "readable" : "not_present", "GuiRecipe.currenthandlers/getHandler; IRecipeHandler; GuiRecipeTab.getHandlerInfo", "Current screen handlers only, no registered-handler search.");
        cover(result, "recipes", env.recipeScreen ? "partial" : "not_present", "IRecipeHandler stacks; PositionedStack; existing GTRecipeAdapter", "Original indices, bounded stacks/alternatives. Passive rendered_text complements drawing-only FontRenderer text; arbitrary geometry remains unknown.");
        cover(result, "itemTooltips", request.include || request.explicitTooltip || "tooltip".equals(request.section) ? "partial" : "not_requested", "Actual ItemStack/position/badge getters and selected native widget/recipe-handler callbacks", "Explicit actual Tooltip detail reads support query-scoped Shift/Control/Alt only when the runtime Keyboard.isKeyDown hook is observed; absent hook rejects virtual requests. advanced and Shift expansion are independent. Direct key buffers, third-party cached modifier state and universal renderer-only callback replay remain unsupported. Passive native hover and rendered text are separately observed data.");
        cover(result, "panels", "partial", "ItemPanels; PanelWidget; audited AbstractSubpanel.grid; ItemsGrid existing layout/cache", "Item/history slots can be derived from existing page/items/invalid-slot map without NEI writes. Craftables/bookmark absent caches remain explicit pending. Existing layout membership and geometry report visibility.");
        cover(result, "recipeSemantics", env.recipeScreen ? "partial" : "not_present", "GT raw metadata; audited AspectRecipeIndex cached recipe provider", "recipe.semantics describes plugin data; detail/metadata and metadata_value continue original ordered GT entries or visible Infusion aspects/instability/research. Hidden recipe/research rules are preserved.");
        cover(result, "recipeDiagram", env.recipeScreen ? "partial" : "not_present", "Audited existing NEICustomDiagram layout/interactable/model definitions", "recipe.diagram and detail/metadata_value expose known labels/lines/slots/components/tooltips with continuations. Dynamic callbacks and unknown drawings remain explicit unknown; no draw/refresh is invoked.");
        cover(result, "bookmarks", "partial", "BookmarkItem/BookmarkGroup/RecipeChainDetails public fields", "Long amount/multiplier/factor preserved; inactive namespaces/storage indexes are protected without a public panel accessor.");
        cover(result, "settings", "readable", "Existing ConfigTagParent tree; actual LayoutManager/GuiOptionList widgets; audited subset tag map", "detail/settings,widgets,subsets,subset_items continue current values. No default tags or menus created.");
        cover(result, "craftingTree", treeScreen(env.screen) ? "readable" : "not_present", "Audited GuiCraftingTree active math/canvas/uiState/statsPanel fields", "Current already-generated tree nodes/roots/edges/stats are paged; no math.refresh/rebuild.");
        cover(result, "catalysts", env.recipeScreen ? "readable" : "not_present", "GuiRecipe.getRecipeCatalystWidget + audited GuiRecipeCatalyst.items", "Current PositionedStack array via detail/catalysts; no registered list substituted.");
        cover(result, "structurePreview", "partial", "Existing blockrenderer6343 live scene provider", "preview and detail/preview_blocks read already-generated world/maps; other arbitrary custom rendering remains unsupported.");
        cover(result, "renderedText", NeiTextCapture.observerActive() ? "partial" : "not_present", "Passive FontRenderer.drawString requests before batched-font cancellation; independent renderString wrapped lines; bounded completed Forge draw frames", "rendered and detail/rendered_text/rendered_text_value include ordinary and wrapped text without rendering or duplicate private/shadow passes. Empty frames are partial with per-frame hook counters. Local arguments are unmapped; custom-font overrides, texture text, clipping, occlusion, bidi glyph order, and paths bypassing both observed methods remain unknown.");
        cover(result, "paginationContext", "partial", "Session/screen identity; handler/page/visible refs; bounded semantic panel items/slots/layout widget order/search/focus; selected recipe metadata", "Equivalent UI collection/cache materialization retains continuation. No atomic snapshot of opaque handler internals or off-screen changes beyond the bounded fingerprint is promised.");
        return result;
    }

    private static void cover(JsonArray result, String feature, String status, String source, String reason) {
        JsonObject item = new JsonObject(); item.addProperty("feature", feature); item.addProperty("status", status); item.addProperty("source", source); item.addProperty("reason", reason); result.add(item);
    }

    static JsonArray badges(Object value) {
        JsonArray result = new JsonArray(); if (!(value instanceof List<?>)) return result;
        for (Object badge : (List<?>) value) {
            if (result.size() >= 16) break;
            JsonObject entry = new JsonObject();
            if (badge != null) {
                entry.addProperty("class", badge.getClass().getName()); ClassLoader loader = badge.getClass().getClassLoader();
                for (String getter : new String[] {"getText", "getColor", "hasShadow", "getAlignment"}) entry.add(getter, primitive(optionalCall(loader, P + "recipe.Badge", badge, getter)));
                Object tooltip = optionalCall(loader, P + "recipe.Badge", badge, "getTooltip");
                entry.addProperty("tooltipTotal", tooltip instanceof List<?> ? ((List<?>) tooltip).size() : 0);
            }
            result.add(entry);
        }
        return result;
    }

    static void fluid(JsonObject result, ItemStack stack) {
        try {
            Object value = call(stack.getClass().getClassLoader(), P + "recipe.StackInfo", null, "getFluid", new Class<?>[] {ItemStack.class}, stack);
            if (!(value instanceof FluidStack)) return;
            FluidStack fluid = (FluidStack) value; if (fluid.getFluid() == null) return;
            JsonObject json = new JsonObject(); json.addProperty("name", fluid.getFluid().getName()); json.addProperty("amount", fluid.amount);
            if (fluid.tag != null) { String tag = fluid.tag.toString(); json.addProperty("nbt", JsonData.bounded(tag, 8192)); json.addProperty("nbtTruncated", tag.length() > 8192); }
            result.add("fluid", json);
        } catch (BridgeException | LinkageError error) { result.addProperty("fluidStatus", "unsupported"); }
    }

    static JsonElement widget(Object value, ClassLoader loader) {
        if (value == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject(); result.addProperty("class", value.getClass().getName());
        for (String name : new String[] {"x", "y", "w", "h", "z"}) { Object number = optionalField(loader, P + "Widget", value, name); if (number != null) result.add(name, primitive(number)); }
        Object focused = optionalCall(loader, P + "Widget", value, "isFocused"); if (focused != null) result.add("focused", primitive(focused));
        return result;
    }

    private static JsonElement rectangle(Object value, ClassLoader loader) {
        if (value == null) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        for (String name : new String[] {"x", "y", "w", "h"}) result.add(name, primitive(field(loader, "codechicken.lib.vec.Rectangle4i", value, name)));
        return result;
    }

    private static JsonObject limits() {
        JsonObject result = new JsonObject(); result.addProperty("recipes", 16); result.addProperty("panelEntries", 64); result.addProperty("handlerEntries", 64);
        result.addProperty("coverageEntries", 32); result.addProperty("positionsPerRecipe", 64); result.addProperty("alternativesPerPosition", 32);
        result.addProperty("contextVisibleRefs", CONTEXT_REFS); result.addProperty("nbtCharacters", 8192); result.addProperty("resultBytes", BYTE_LIMIT); result.addProperty("softBudgetNanos", StructureAdapter.SOFT_BUDGET_NANOS);
        result.addProperty("renderedFrameDraws", NeiTextCapture.DRAW_LIMIT); result.addProperty("renderedFrameCodepoints", NeiTextCapture.FRAME_CODEPOINT_LIMIT);
        result.addProperty("renderedDrawCodepoints", NeiTextCapture.DRAW_CODEPOINT_LIMIT); result.addProperty("renderObserverBudgetNanos", NeiTextCapture.OBSERVER_BUDGET_NANOS); return result;
    }

    static final class Environment {
        final Object screen; final ClassLoader loader; final boolean recipeScreen;
        final List<?> handlers, refs; final Object handler, focused; final int handlerIndex, recipePage;
        final JsonObject search = new JsonObject(); final List<Panel> panels = new ArrayList<Panel>();
        Environment(Object screen) {
            this.screen = screen; this.loader = screen.getClass().getClassLoader(); type(loader, P + "LayoutManager");
            recipeScreen = isType(screen, GUI);
            handler = recipeScreen ? call(loader, GUI, screen, "getHandler") : null;
            handlers = recipeScreen ? list(field(loader, GUI, screen, "currenthandlers")) : Collections.emptyList();
            refs = recipeScreen ? list(call(loader, GUI, screen, "getRecipeIndices")) : Collections.emptyList();
            handlerIndex = recipeScreen ? ((Number) field(loader, GUI, screen, "recipetype")).intValue() : -1;
            Object page = recipeScreen ? optionalField(loader, GUI, screen, "page") : null; recipePage = page instanceof Number ? ((Number) page).intValue() : -1;
            focused = optionalCall(loader, P + "LayoutManager", null, "getInputFocused");
            Object searchField = optionalField(loader, P + "LayoutManager", null, "searchField");
            search.add("widget", widget(searchField, loader));
            Object text = optionalCall(loader, P + "TextField", searchField, "text"); search.add("text", primitive(text));
            Object item = optionalField(loader, P + "ItemPanels", null, "itemPanel");
            panels.add(new Panel("item", item, loader));
            panels.add(new Panel("history", optionalField(loader, P + "ItemPanel", item, "historyPanel"), loader));
            panels.add(new Panel("craftables", optionalField(loader, P + "ItemPanel", item, "craftablesPanel"), loader));
            panels.add(new Panel("bookmarks", optionalField(loader, P + "ItemPanels", null, "bookmarkPanel"), loader));
        }
        Panel panel(String name) { for (Panel panel : panels) if (panel.name.equals(name)) return panel; throw new BridgeException("invalid_args", "Unknown panel."); }
        String signature() {
            StringBuilder result = new StringBuilder(); result.append(System.identityHashCode(screen)).append(':');
            if (screen instanceof GuiScreen) result.append(((GuiScreen) screen).width).append('x').append(((GuiScreen) screen).height).append(':');
            if (recipeScreen) {
                Object selected = call(loader, GUI, screen, "getHandler");
                result.append(System.identityHashCode(selected)).append(':').append(field(loader, GUI, screen, "recipetype")).append(':').append(optionalField(loader, GUI, screen, "page"));
                List<?> current = list(field(loader, GUI, screen, "currenthandlers")); result.append(':').append(current.size());
                for (int i = 0; i < Math.min(current.size(), CONTEXT_REFS); i++) result.append(':').append(System.identityHashCode(current.get(i))).append('/').append(call(loader, HANDLER, current.get(i), "numRecipes"));
                List<?> indices = list(call(loader, GUI, screen, "getRecipeIndices")); result.append(':').append(indices.size());
                for (int i = 0; i < Math.min(indices.size(), CONTEXT_REFS); i++) result.append(':').append(indices.get(i));
            }
            Object currentSearch = optionalField(loader, P + "LayoutManager", null, "searchField");
            result.append(':').append(optionalCall(loader, P + "TextField", currentSearch, "text"));
            result.append(':').append(widget(optionalCall(loader, P + "LayoutManager", null, "getInputFocused"), loader));
            for (Panel panel : panels) result.append('|').append(panel.signature(loader));
            result.append('|').append(NeiDetailAdapter.context(this)).append('|').append(NeiPreviewProvider.context(screen)).append('|').append(NeiPreviewResources.context()).append('|').append(LogicalGuiPointer.context()).append('|').append(NeiNaturalRenderCapture.context(screen));
            return result.toString();
        }
    }

    static final class Panel {
        final String name; final Object object, grid; final List<?> mask, items; final int total; final String error, maskSource; final boolean maskPending;
        Panel(String name, Object object, ClassLoader loader) {
            this.name = name; this.object = object;
            Object foundGrid = null; List<?> foundMask = Collections.emptyList(), foundItems = Collections.emptyList(); int count = 0; String failure = "";
            String source = "not_present";
            try {
                if (object != null) { foundGrid = grid(object, loader); foundItems = list(call(loader, GRID, foundGrid, "getItems")); foundMask = mask(name, foundGrid, foundItems, loader); source = NeiUiAccess.mask(foundGrid, loader) == null ? foundMask == null ? "pending_existing_cache" : "derived_existing_grid" : "existing_mask"; count = ((Number) call(loader, GRID, foundGrid, "size")).intValue(); }
                else failure = "Public NEI panel is not initialized.";
            } catch (BridgeException exception) { failure = exception.getMessage(); }
            this.grid = foundGrid; this.items = foundItems; this.maskPending = foundMask == null; this.mask = foundMask == null ? Collections.emptyList() : foundMask; this.maskSource = source; this.total = count; this.error = failure;
        }
        JsonObject summary() {
            JsonObject result = new JsonObject(); result.addProperty("name", name); result.addProperty("status", object == null ? "not_present" : error.isEmpty() ? "readable" : "unsupported");
            result.addProperty("totalItems", total); result.add("visibleSlots", maskPending ? JsonNull.INSTANCE : new JsonPrimitive(mask.size()));
            result.addProperty("visibleSlotsStatus", maskPending ? "pending" : "readable"); result.addProperty("visibleSlotsSource", maskSource);
            if (!error.isEmpty()) result.addProperty("reason", error);
            if (object != null) {
                ClassLoader loader = object.getClass().getClassLoader(); result.add("widget", widget(object, loader));
                for (String getter : new String[] {"getPage", "getNumPages", "getRows", "getColumns", "getPerPage"}) result.add(getter, primitive(optionalCall(loader, GRID, grid, getter)));
                if (!NeiUiAccess.is(object, P + "AbstractSubpanel")) result.add("label", primitive(optionalCall(loader, PANEL, object, "getLabelText")));
                if ("craftables".equals(name)) { Object available = NeiUiAccess.optional(loader, P + "ItemCraftablesPanel", object, "availableRecipes"); if (available instanceof Map<?, ?>) result.addProperty("availableRecipesTotal", ((Map<?, ?>) available).size()); }
                Object drawn = NeiUiAccess.optional(loader, P + "LayoutManager", null, "drawWidgets");
                Object controlled = NeiUiAccess.optional(loader, P + "LayoutManager", null, "controlWidgets");
                if (drawn instanceof java.util.Collection<?>) result.addProperty("inDrawWidgets", layoutContains(drawn, object));
                if (controlled instanceof java.util.Collection<?>) result.addProperty("inControlWidgets", layoutContains(controlled, object));
                result.addProperty("visibilityStatus", "existing_layout_membership_and_geometry");
            }
            return result;
        }
        String signature(ClassLoader loader) {
            if (grid == null || !error.isEmpty()) return name + ":unavailable:" + error;
            Object currentGrid = grid(object, loader); List<?> currentItems = list(call(loader, GRID, currentGrid, "getItems"));
            List<?> currentMask = mask(name, currentGrid, currentItems, loader);
            StringBuilder value = new StringBuilder(name).append(':').append(currentItems.size());
            for (int i = 0; i < Math.min(currentItems.size(), CONTEXT_REFS); i++) value.append(':').append(stackSignature(currentItems.get(i)));
            for (String getter : new String[] {"getPage", "getNumPages", "getRows", "getColumns", "getPerPage"}) value.append(':').append(optionalCall(loader, GRID, currentGrid, getter));
            value.append(':').append(widget(object, loader));
            if (currentMask == null) return value.append(":mask_pending").toString();
            value.append(':').append(currentMask.size());
            for (int i = 0; i < Math.min(currentMask.size(), CONTEXT_REFS); i++) {
                Object slot = currentMask.get(i); if (slot == null) { value.append(":null"); continue; }
                value.append(':').append(slotIndex(slot, loader)).append('/').append(itemIndex(slot, loader)).append('/').append(stackSignature(stack(slot, loader)));
            }
            return value.toString();
        }
        private static Object grid(Object panel, ClassLoader loader) { return NeiUiAccess.is(panel, P + "AbstractSubpanel") ? NeiUiAccess.get(loader, P + "AbstractSubpanel", panel, "grid") : call(loader, PANEL, panel, "getGrid"); }
        private static List<?> mask(String name, Object grid, List<?> items, ClassLoader loader) {
            List<?> cached = NeiUiAccess.mask(grid, loader); if (cached != null) return cached;
            if (!("item".equals(name) || "history".equals(name))) return null;
            int rows = ((Number) call(loader, GRID, grid, "getRows")).intValue(), columns = ((Number) call(loader, GRID, grid, "getColumns")).intValue();
            long slots = (long) rows * columns; Object invalid = NeiUiAccess.optional(loader, GRID, grid, "invalidSlotMap");
            if (rows <= 0 || columns <= 0 || slots > 16384 || !(invalid instanceof boolean[]) || ((boolean[]) invalid).length < slots) return null;
            int page = ((Number) call(loader, GRID, grid, "getPage")).intValue(), perPage = ((Number) call(loader, GRID, grid, "getPerPage")).intValue();
            long first = "history".equals(name) ? 0 : (long) (page - 1) * perPage;
            if (first < 0 || first > items.size()) return null;
            List<DerivedSlot> derived = new ArrayList<DerivedSlot>(); int itemIndex = (int) first;
            for (int slotIndex = 0; slotIndex < slots && itemIndex < items.size(); slotIndex++) if (!((boolean[]) invalid)[slotIndex]) derived.add(new DerivedSlot(slotIndex, itemIndex, items.get(itemIndex++)));
            return derived;
        }
        static int slotIndex(Object slot, ClassLoader loader) { return slot instanceof DerivedSlot ? ((DerivedSlot) slot).slotIndex : ((Number) field(loader, GRID + "$ItemsGridSlot", slot, "slotIndex")).intValue(); }
        static int itemIndex(Object slot, ClassLoader loader) { return slot instanceof DerivedSlot ? ((DerivedSlot) slot).itemIndex : ((Number) field(loader, GRID + "$ItemsGridSlot", slot, "itemIndex")).intValue(); }
        static Object stack(Object slot, ClassLoader loader) { return slot instanceof DerivedSlot ? ((DerivedSlot) slot).item : call(loader, GRID + "$ItemsGridSlot", slot, "getItemStack"); }
    }

    private static final class DerivedSlot { final int slotIndex, itemIndex; final Object item; DerivedSlot(int slotIndex, int itemIndex, Object item) { this.slotIndex = slotIndex; this.itemIndex = itemIndex; this.item = item; } }
    static String stackSignature(Object item) { if (!(item instanceof ItemStack)) return item == null ? "null" : item.getClass().getName(); ItemStack stack = (ItemStack) item; return System.identityHashCode(stack.getItem()) + "/" + stack.getMetadata() + "/" + stack.stackSize + "/" + (stack.hasTagCompound() ? stack.getTagCompound().hashCode() : 0); }

    private static final class Context {
        final Object screen; final String session, screenId, signature, token = UUID.randomUUID().toString();
        final Map<String, String> resources = new HashMap<String, String>();
        final Map<String, String> tooltipContexts = new HashMap<String, String>();
        final Map<String, PanelItem> panelItems = new java.util.LinkedHashMap<String, PanelItem>();
        NeiTextCapture.Snapshot rendered;
        NeiHoverCapture.Snapshot hover;
        Context(Object screen, String session, String screenId, String signature) { this.screen = screen; this.session = session; this.screenId = screenId; this.signature = signature; }
        boolean matches(Object screen, String session, String screenId, String signature) { return this.screen == screen && this.session.equals(session) && this.screenId.equals(screenId) && this.signature.equals(signature); }
    }

    private static final class PanelItem {
        final String panel, id = "nei-item:" + UUID.randomUUID().toString(); final int index; final ItemStack stack;
        PanelItem(String panel, int index, ItemStack stack) { this.panel = panel; this.index = index; this.stack = stack.copy(); }
    }

    private static String tooltipResource(JsonObject args) {
        if (!args.has("target")) return "";
        String target = JsonData.string(args, "target");
        if (!Arrays.asList("tooltip_lines", "tooltip_text", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text").contains(target)) return "";
        StringBuilder key = new StringBuilder(target.startsWith("preview_") ? "preview" : "ordinary");
        for (String selector : new String[] {"stackSource", "handlerIndex", "recipeIndex", "positionKind", "positionIndex", "alternativeIndex", "itemIndex", "panel", "tooltipSource", "badgeIndex", "previewBlockSource", "previewPosition", "groupIndex"})
            key.append('/').append(selector).append('=').append(args.has(selector) ? args.get(selector) : "default");
        return key.toString();
    }

    private static String recipeResource(Environment env, JsonObject args, Request request) { if (!env.recipeScreen || !("recipe".equals(request.section) || "detail".equals(request.section) && Arrays.asList("metadata", "metadata_value").contains(JsonData.string(args, "target")))) return ""; int handlerIndex = request.handlerIndex < 0 ? env.handlerIndex : request.handlerIndex; return handlerIndex + "/" + JsonData.integer(args, "recipeIndex", 0, Integer.MAX_VALUE); }
    private static String recipeResourceSignature(Environment env, JsonObject args, Request request) { int handlerIndex = request.handlerIndex < 0 ? env.handlerIndex : request.handlerIndex; if (handlerIndex >= env.handlers.size()) throw new BridgeException("not_found", "handlerIndex is absent."); Object handler = env.handlers.get(handlerIndex); int recipeIndex = JsonData.integer(args, "recipeIndex", 0, Integer.MAX_VALUE); return NeiDetailAdapter.metadataContext(env, handler, recipeIndex) + "/" + NeiRecipeSemantics.context(handler, recipeIndex) + "/" + NeiDiagramProvider.context(handler, recipeIndex); }

    static final class Request {
        final String section, panel, scope; final int offset, limit, handlerIndex, recipeIndex, groupId, x, y; final boolean include, advanced, explicitTooltip;
        Request(JsonObject args) {
            section = args.has("section") ? JsonData.string(args, "section") : "summary";
            if (!Arrays.asList("summary", "handlers", "recipes", "recipe", "panel", "settings", "tree", "tooltip", "coverage", "detail", "preview", "rendered").contains(section)) throw new BridgeException("invalid_args", "Unknown NEI section.");
            if (NeiRenderedAdapter.requested(args)) {
                List<String> accepted = new ArrayList<String>(Arrays.asList("screenId", "section", "contextToken", "offset", "limit"));
                if ("detail".equals(section)) { accepted.add("target"); if ("rendered_text_value".equals(JsonData.string(args, "target"))) accepted.add("itemIndex"); }
                for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!accepted.contains(entry.getKey()))
                    throw new BridgeException("invalid_args", entry.getKey() + " is not valid for rendered text reads.");
            }
            if (NeiHoverAdapter.requested(args)) {
                List<String> accepted = new ArrayList<String>(Arrays.asList("screenId", "section", "contextToken", "offset", "limit", "target"));
                if ("hover_tooltip_text".equals(JsonData.string(args, "target"))) { accepted.add("lineIndex"); JsonData.integer(args, "lineIndex", 0, Integer.MAX_VALUE); }
                for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!accepted.contains(entry.getKey()))
                    throw new BridgeException("invalid_args", entry.getKey() + " is not valid for passive completed hover reads.");
            }
            if ("detail".equals(section) && (NeiPreviewDetailProvider.requested(args) || NeiNativeRenderProvider.requested(args) || "preview_candidate_visuals".equals(JsonData.string(args, "target")))) {
                String target = JsonData.string(args, "target");
                List<String> accepted = new ArrayList<String>(Arrays.asList("screenId", "section", "contextToken", "offset", "limit", "target"));
                boolean block = NeiNativeRenderProvider.requested(args) || Arrays.asList("preview_block", "preview_hint_text", "preview_textures").contains(target);
                boolean candidate = !"preview_candidate_groups".equals(target) && !block;
                boolean tooltipQuery = Arrays.asList("preview_candidate_tooltip_lines", "preview_candidate_tooltip_text").contains(target);
                if (block) { accepted.add("previewBlockSource"); accepted.add("previewPosition"); }
                if ("preview_textures".equals(target)) accepted.add("textureSide");
                if (candidate) { accepted.add("groupIndex"); JsonData.integer(args, "groupIndex", 0, Integer.MAX_VALUE); }
                if (candidate && !"preview_candidate_items".equals(target)) { accepted.add("alternativeIndex"); JsonData.integer(args, "alternativeIndex", 0, Integer.MAX_VALUE); }
                if (tooltipQuery) { accepted.add("advanced"); accepted.add("modifiers"); }
                if ("preview_candidate_tooltip_text".equals(target)) { accepted.add("lineIndex"); JsonData.integer(args, "lineIndex", 0, Integer.MAX_VALUE); }
                for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!accepted.contains(entry.getKey()))
                    throw new BridgeException("invalid_args", entry.getKey() + " is not valid for this preview detail target.");
                if (args.has("textureSide")) JsonData.integer(args, "textureSide", 0, 5);
                if (args.has("previewBlockSource") && !Arrays.asList("selected", "hover", "position").contains(JsonData.string(args, "previewBlockSource"))) throw new BridgeException("invalid_args", "Unknown previewBlockSource.");
                if (args.has("previewBlockSource") && "position".equals(JsonData.string(args, "previewBlockSource"))) {
                    if (!args.has("previewPosition") || !args.get("previewPosition").isJsonObject()) throw new BridgeException("invalid_args", "previewPosition requires integer XYZ.");
                    JsonObject position = args.getAsJsonObject("previewPosition");
                    for (Map.Entry<String, JsonElement> entry : position.entrySet()) if (!Arrays.asList("x", "y", "z").contains(entry.getKey())) throw new BridgeException("invalid_args", "Unknown previewPosition field.");
                    JsonData.integer(position, "x", -33554432, 33554431); JsonData.integer(position, "y", -2048, 2047); JsonData.integer(position, "z", -33554432, 33554431);
                } else if (args.has("previewPosition")) throw new BridgeException("invalid_args", "previewPosition requires position source.");
            }
            explicitTooltip = "detail".equals(section) && Arrays.asList("tooltip_lines", "tooltip_text", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text").contains(JsonData.string(args, "target"));
            TooltipModifiers.validate(args, explicitTooltip);
            boolean detail = "detail".equals(section), recipe = "recipe".equals(section) || "recipes".equals(section), items = "panel".equals(section), tooltip = "tooltip".equals(section), tree = "tree".equals(section);
            allowed(args, "handlerIndex", recipe || detail); allowed(args, "recipeIndex", "recipe".equals(section) || detail); allowed(args, "panel", items || detail); allowed(args, "scope", items || "recipes".equals(section));
            allowed(args, "groupId", tree); allowed(args, "x", tooltip); allowed(args, "y", tooltip); allowed(args, "includeTooltips", recipe || items || tooltip || detail); allowed(args, "advanced", recipe || items || tooltip || detail);
            for (String key : NeiDetailAdapter.PARAMETERS) allowed(args, key, detail);
            offset = args.has("offset") ? JsonData.integer(args, "offset", 0, Integer.MAX_VALUE) : 0;
            boolean paging = Arrays.asList("handlers", "recipes", "panel", "coverage", "detail").contains(section);
            if (!paging && offset != 0) throw new BridgeException("invalid_args", "This section requires offset zero.");
            boolean text = detail && Arrays.asList("nbt", "tooltip_text", "metadata_value", "rendered_text_value", "hover_tooltip_text", "preview_hint_text", "preview_candidate_tooltip_text", "preview_candidate_nbt").contains(JsonData.string(args, "target"));
            int cap = text ? 8192 : detail && "preview_block".equals(JsonData.string(args, "target")) ? 1 : "recipes".equals(section) ? 16 : "coverage".equals(section) ? 32 : paging ? 64 : 1;
            limit = args.has("limit") ? JsonData.integer(args, "limit", 1, cap) : text ? 2048 : Math.min(cap, 16);
            if (detail && "preview_block".equals(JsonData.string(args, "target")) && offset != 0) throw new BridgeException("invalid_args", "preview_block requires offset zero.");
            handlerIndex = args.has("handlerIndex") ? JsonData.integer(args, "handlerIndex", 0, Integer.MAX_VALUE) : -1;
            recipeIndex = "recipe".equals(section) ? JsonData.integer(args, "recipeIndex", 0, Integer.MAX_VALUE) : -1;
            groupId = args.has("groupId") ? JsonData.integer(args, "groupId", 0, Integer.MAX_VALUE) : 0;
            x = tooltip ? JsonData.integer(args, "x", 0, 32767) : 0; y = tooltip ? JsonData.integer(args, "y", 0, 32767) : 0;
            panel = args.has("panel") ? JsonData.string(args, "panel") : "item";
            if (!Arrays.asList("item", "history", "craftables", "bookmarks").contains(panel)) throw new BridgeException("invalid_args", "Unknown NEI panel.");
            scope = args.has("scope") ? JsonData.string(args, "scope") : "visible";
            if (!("visible".equals(scope) || (items ? "all" : "handler").equals(scope))) throw new BridgeException("invalid_args", "Invalid scope for section.");
            include = flag(args, "includeTooltips"); advanced = flag(args, "advanced");
        }
    }

    static final class Read {
        final Request request; final long started; final JsonObject data = new JsonObject(); String status = "readable";
        NeiTextCapture.Snapshot rendered;
        NeiHoverCapture.Snapshot hover;
        int total, next, bytes = 16384, effectiveLimit; boolean partial, byteLimited, timedOut;
        Read(Request request, long started) { this.request = request; this.started = started; this.effectiveLimit = request.limit; }
        boolean expired() { return System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS; }
        boolean progress(int emitted) { if (emitted > 0 && expired()) { partial = true; timedOut = true; status = "partial"; return false; } return true; }
        boolean add(JsonArray entries, JsonElement value) { int length = size(value); if (bytes + length > BYTE_LIMIT - 8192) { byteLimited = true; partial = true; status = "partial"; return false; } entries.add(value); bytes += length; return true; }
        void single() { total = 1; next = 1; }
        void partial(String reason) { partial = true; status = "partial"; data.addProperty("reason", reason); }
        void absent(String reason) { status = "not_present"; data.addProperty("reason", reason); total = next = 0; }
        void unsupported(String reason) { status = "unsupported"; partial = true; data.addProperty("reason", reason); total = next = 0; }
        void page(JsonArray source, String key) { total = source.size(); next = Math.min(request.offset, total); JsonArray values = new JsonArray(); data.add(key, values); while (next < total && values.size() < request.limit && progress(values.size())) { if (!add(values, source.get(next))) break; next++; } }
    }

    private static boolean flag(JsonObject args, String key) { if (!args.has(key)) return false; JsonElement value = args.get(key); if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args", key + " must be boolean."); return value.getAsBoolean(); }
    private static void allowed(JsonObject args, String key, boolean allowed) { if (!allowed && args.has(key)) throw new BridgeException("invalid_args", key + " is not valid for this section."); }
    private static boolean treeScreen(Object object) { return isType(object, P + "bookmark.tree.GuiCraftingTree"); }
    private static boolean isType(Object object, String name) { if (object == null) return false; for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) if (type.getName().equals(name)) return true; return false; }
    private static Class<?> type(ClassLoader loader, String name) { try { return Class.forName(name, false, loader); } catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "Public NEI type unavailable: " + name); } }
    private static Object field(ClassLoader loader, String owner, Object target, String name) { return NeiUiAccess.publicField(loader, owner, target, name); }
    private static Object optionalField(ClassLoader loader, String owner, Object target, String name) { try { return field(loader, owner, target, name); } catch (BridgeException error) { return null; } }
    private static Object call(ClassLoader loader, String owner, Object target, String name) { return call(loader, owner, target, name, new Class<?>[0]); }
    private static Object call(ClassLoader loader, String owner, Object target, String name, Class<?>[] types, Object... args) { return NeiUiAccess.publicCall(loader, owner, target, name, types, args); }
    private static Object optionalCall(ClassLoader loader, String owner, Object target, String name) { return optionalCall(loader, owner, target, name, new Class<?>[0]); }
    private static Object optionalCall(ClassLoader loader, String owner, Object target, String name, Class<?>[] types, Object... args) { try { return call(loader, owner, target, name, types, args); } catch (BridgeException error) { return null; } }
    private static List<?> list(Object value) { if (!(value instanceof List<?>)) throw new BridgeException("adapter_error", "Public NEI list getter returned invalid data."); return (List<?>) value; }
    private static JsonElement primitive(Object value) { if (value == null) return JsonNull.INSTANCE; if (value instanceof Number) return new JsonPrimitive((Number) value); if (value instanceof Boolean) return new JsonPrimitive((Boolean) value); return new JsonPrimitive(JsonData.bounded(String.valueOf(value), 1024)); }
    private static int size(JsonElement value) { return value.toString().getBytes(StandardCharsets.UTF_8).length; }
}
