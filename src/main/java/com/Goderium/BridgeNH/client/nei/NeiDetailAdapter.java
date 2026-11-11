package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.recipe.GTRecipeAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter.Environment;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter.Panel;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter.Read;

/** Continuation pages over existing model collections. Fixed APIs/fields; no draw or update calls. */
final class NeiDetailAdapter {
    private static final String P = "codechicken.nei.", GUI = P + "recipe.GuiRecipe", HANDLER = P + "recipe.IRecipeHandler";
    static final String[] PARAMETERS = {"target", "stackSource", "positionKind", "positionIndex", "alternativeIndex", "itemIndex", "tooltipSource", "lineIndex", "metadataIndex", "valuePath", "widgetSource", "badgeIndex", "settingSource", "configPath", "subsetPath", "modifiers", "previewBlockSource", "previewPosition", "groupIndex", "textureSide"};
    private static final List<String> TARGETS = Arrays.asList("positions", "alternatives", "badges", "tooltip_lines", "tooltip_text", "nbt", "item_visuals", "metadata", "metadata_value", "catalysts", "widgets", "subsets", "tree_items", "tree_roots", "tree_edges", "tree_stats", "bookmark_permutations", "preview_blocks", "preview_controls", "settings", "subset_items");

    static void read(Environment env, EntityPlayer player, JsonObject args, Read out) {
        if (NeiRenderedAdapter.requested(args)) { NeiRenderedAdapter.read(args, out); return; }
        if (NeiHoverAdapter.requested(args)) { NeiHoverAdapter.read(args, out); return; }
        if (NeiNativeRenderProvider.requested(args)) {
            mergePreviewResult(NeiNativeRenderProvider.read(env.screen, args, out.request.offset, out.request.limit, out.started), out, true); return;
        }
        if ("preview_candidate_visuals".equals(JsonData.string(args, "target"))) {
            mergePreviewResult(NeiItemVisualProvider.readCandidate(env.screen, args, out.request.offset, out.request.limit, out.started), out, true); return;
        }
        if (NeiPreviewDetailProvider.requested(args)) {
            mergePreviewResult(NeiPreviewDetailProvider.read(env.screen, player, args, out.request.offset, out.request.limit, out.started, out.request.advanced), out, true);
            return;
        }
        for (String key : new String[] {"previewBlockSource", "previewPosition", "groupIndex", "textureSide"})
            if (args.has(key)) throw new BridgeException("invalid_args", key + " is only valid for dedicated preview detail targets.");
        Selection selection = new Selection(args);
        out.data.addProperty("target", selection.target); out.data.addProperty("unit", "entries");
        out.data.addProperty("source", "Current NEI 2.8.145 model; audited fixed fields where public getters are absent");
        if ("item_visuals".equals(selection.target)) {
            mergePreviewResult(NeiItemVisualProvider.readItem(stack(env, args, selection), args, out.request.offset, out.request.limit, out.started), out, true); return;
        }
        if ("positions".equals(selection.target)) {
            Object handler = handler(env, args); List<?> positions = positions(env, handler, index(args, "recipeIndex"), selection.kind);
            page(positions, out, (entry, i) -> position(entry, env, player, out)); return;
        }
        if ("alternatives".equals(selection.target) || "badges".equals(selection.target)) {
            Object position = selectedPosition(env, args, selection);
            if ("alternatives".equals(selection.target)) page(array(field(env, P + "PositionedStack", position, "items")), out, (entry, i) -> entry instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) entry, player, out.request.include, out.request.advanced) : JsonNull.INSTANCE);
            else page(list(call(env, P + "PositionedStack", position, "getBadges")), out, (entry, i) -> badge(entry, env));
            return;
        }
        if ("tooltip_lines".equals(selection.target) || "tooltip_text".equals(selection.target) || "nbt".equals(selection.target)) {
            ItemStack stack = stack(env, args, selection);
            if ("nbt".equals(selection.target)) {
                text(stack == null || !stack.hasTagCompound() ? "" : stack.getTagCompound().toString(), out); return;
            }
            List<?> lines;
            try (TooltipModifiers.Scope scope = TooltipModifiers.enter(args)) {
                lines = tooltips(env, player, args, selection, stack, out);
                out.data.add("modifierContext", scope.data);
            }
            out.data.addProperty("tooltipScope", "Exact ItemStack/position/badge getter and selected recipe-handler callback; global native Tooltip additions are captured by hover_tooltip_lines.");
            if ("tooltip_text".equals(selection.target)) { int line = index(args, "lineIndex"); if (line >= lines.size()) throw new BridgeException("not_found", "lineIndex exceeds the current tooltip."); text(String.valueOf(lines.get(line)), out); }
            else page(lines, out, (entry, i) -> { String text = String.valueOf(entry); JsonObject json = new JsonObject(); int characters = text.codePointCount(0, text.length()); json.addProperty("lineIndex", i); json.addProperty("text", text.substring(0, text.offsetByCodePoints(0, Math.min(characters, 512)))); json.addProperty("characters", characters); json.addProperty("textTruncated", characters > 512); return json; });
            out.data.add("modifiers", NeiDataAdapter.modifiers()); return;
        }
        if ("metadata".equals(selection.target) || "metadata_value".equals(selection.target)) {
            metadata(env, args, selection, out); return;
        }
        if ("catalysts".equals(selection.target)) { page(catalysts(env), out, (entry, i) -> position(entry, env, player, out)); return; }
        if ("widgets".equals(selection.target)) { page(widgets(env, selection.widgetSource), out, (entry, i) -> widget(entry, env, player, out)); return; }
        if ("subsets".equals(selection.target)) {
            Object tags = NeiUiAccess.get(env.loader, P + "SubsetWidget", null, "tags");
            if (!(tags instanceof Map<?, ?>)) { out.unsupported("Current subset tag map unavailable."); return; }
            page(mapValues((Map<?, ?>) tags), out, (entry, i) -> subset(entry, env)); return;
        }
        if ("subset_items".equals(selection.target)) {
            Object tags = NeiUiAccess.get(env.loader, P + "SubsetWidget", null, "tags");
            Object tag = tags instanceof Map<?, ?> ? ((Map<?, ?>) tags).get(JsonData.string(args, "subsetPath")) : null;
            if (tag == null) throw new BridgeException("not_found", "subsetPath is absent from current tags.");
            page(list(NeiUiAccess.get(env.loader, P + "SubsetWidget$SubsetTag", tag, "items")), out, (entry, i) -> entry instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) entry, player, out.request.include, out.request.advanced) : JsonNull.INSTANCE); return;
        }
        if ("settings".equals(selection.target)) {
            Object config = config(env, args.has("settingSource") ? JsonData.string(args, "settingSource") : "global");
            String path = args.has("configPath") ? JsonData.string(args, "configPath") : "";
            Object parent = path.isEmpty() ? config : call(env, "codechicken.lib.config.ConfigTagParent", config, "getTag", new Class<?>[] {String.class, boolean.class}, path, false);
            if (parent == null) throw new BridgeException("not_found", "configPath is absent; no tag was created.");
            Object children = call(env, "codechicken.lib.config.ConfigTagParent", parent, "childTagMap");
            if (!(children instanceof Map<?, ?>)) throw new BridgeException("adapter_error", "Existing config children unavailable.");
            page(mapValues((Map<?, ?>) children), out, (entry, i) -> configTag(entry, env)); return;
        }
        if ("tree_items".equals(selection.target) || "tree_roots".equals(selection.target) || "tree_edges".equals(selection.target) || "tree_stats".equals(selection.target)) {
            if (!NeiUiAccess.is(env.screen, P + "bookmark.tree.GuiCraftingTree")) { out.absent("Current screen is not GuiCraftingTree."); return; }
            if ("tree_stats".equals(selection.target)) { page(widgets(env, "tree_stats"), out, (entry, i) -> widget(entry, env, player, out)); return; }
            Object math = NeiUiAccess.get(env.loader, P + "bookmark.tree.GuiCraftingTree", env.screen, "math");
            if (math == null) { out.absent("Current tree math has not been generated."); return; }
            if ("tree_edges".equals(selection.target)) {
                Object value = field(env, P + "bookmark.tree.CraftingTreeMath", math, "parentOf");
                if (!(value instanceof Map<?, ?>)) throw new BridgeException("adapter_error", "Invalid tree parent map.");
                page(mapEntries((Map<?, ?>) value), out, (entry, i) -> { Map.Entry<?, ?> edge = (Map.Entry<?, ?>) entry; JsonObject json = new JsonObject(); json.addProperty("child", nodeId(edge.getKey())); json.addProperty("parent", nodeId(edge.getValue())); return json; });
            } else {
                List<?> nodes = list(field(env, P + "bookmark.tree.CraftingTreeMath", math, "tree_roots".equals(selection.target) ? "rootSlots" : "items"));
                page(nodes, out, (entry, i) -> treeNode(entry, env, player, out));
            }
            return;
        }
        if ("bookmark_permutations".equals(selection.target)) {
            Object item = bookmarkItem(env, index(args, "itemIndex"));
            Object permutations = field(env, P + "bookmark.BookmarkItem", item, "permutations");
            if (!(permutations instanceof Map<?, ?>)) { out.absent("Bookmark has no existing permutations."); return; }
            page(mapEntries((Map<?, ?>) permutations), out, (entry, i) -> { Map.Entry<?, ?> value = (Map.Entry<?, ?>) entry; JsonObject json = new JsonObject(); json.addProperty("guid", JsonData.bounded(String.valueOf(value.getKey()), 8192)); json.add("item", value.getValue() instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) value.getValue(), player, out.request.include, out.request.advanced) : JsonNull.INSTANCE); return json; });
            return;
        }
        if ("preview_blocks".equals(selection.target)) { mergePreview(env, out, true); return; }
        if ("preview_controls".equals(selection.target)) { mergePreviewResult(NeiPreviewProvider.controls(env.screen, out.request.offset, out.request.limit, out.started), out, true); return; }
        throw new BridgeException("invalid_args", "Unknown detail target.");
    }

    static void mergePreview(Environment env, Read out, boolean blocks) {
        JsonObject preview = NeiPreviewProvider.inspect(env.screen, out.request.offset, out.request.limit, out.started, blocks);
        mergePreviewResult(preview, out, blocks);
    }
    private static void mergePreviewResult(JsonObject preview, Read out, boolean blocks) {
        String status = preview.get("status").getAsString();
        out.status = "ok".equals(status) ? "readable" : "not_built".equals(status) ? "not_present" : status;
        if (preview.has("data")) for (Map.Entry<String, JsonElement> value : preview.getAsJsonObject("data").entrySet()) out.data.add(value.getKey(), value.getValue());
        for (String name : new String[] {"source", "reason"}) if (preview.has(name)) out.data.add(name, preview.get(name));
        out.total = preview.get("total").getAsInt(); out.next = preview.get("nextOffset").getAsInt();
        out.byteLimited = preview.get("byteLimited").getAsBoolean(); out.partial = "unsupported".equals(out.status) || (!preview.get("complete").getAsBoolean() && out.next >= out.total);
        out.timedOut = preview.get("softBudgetPartial").getAsBoolean();
        if (!blocks && "readable".equals(out.status)) out.single();
    }

    private static List<?> tooltips(Environment env, EntityPlayer player, JsonObject args, Selection selection, ItemStack stack, Read out) {
        if ("badge".equals(selection.tooltipSource)) {
            List<?> badges = list(call(env, P + "PositionedStack", selectedPosition(env, args, selection), "getBadges")); int index = index(args, "badgeIndex");
            if (index >= badges.size()) throw new BridgeException("not_found", "badgeIndex is absent."); return list(call(env, P + "recipe.Badge", badges.get(index), "getTooltip"));
        }
        if ("position".equals(selection.tooltipSource)) return list(call(env, P + "PositionedStack", selectedPosition(env, args, selection), "getTooltip"));
        List<String> lines = new ArrayList<String>();
        if (stack != null && "item".equals(selection.tooltipSource)) {
            try { lines.addAll(stack.getTooltip(player, out.request.advanced)); }
            catch (RuntimeException | LinkageError error) { out.partial("Actual item tooltip failed: " + error.getClass().getSimpleName()); }
        }
        if ("recipe".equals(selection.stackSource)) {
            Object handler = handler(env, args);
            // NCD's public handleTooltip draws directly. Never execute it as a structured getter.
            if (handler.getClass().getName().contains("DiagramGroup")) {
                out.partial("Diagram tooltip callback performs rendering; structured diagram provider required."); return lines;
            }
            Class<?> gui = type(env, GUI); int index = index(args, "recipeIndex");
            Object updated = "handler".equals(selection.tooltipSource)
                    ? call(env, HANDLER, handler, "handleTooltip", new Class<?>[] {gui, List.class, int.class}, env.screen, lines, index)
                    : call(env, HANDLER, handler, "handleItemTooltip", new Class<?>[] {gui, ItemStack.class, List.class, int.class}, env.screen, stack, lines, index);
            return list(updated);
        }
        return lines;
    }

    private static void metadata(Environment env, JsonObject args, Selection selection, Read out) {
        Object handler = handler(env, args);
        int recipeIndex = index(args, "recipeIndex");
        final List<?> entries = gtMetadataEntries(env, handler, recipeIndex);
        final List<JsonObject> semantics = NeiRecipeSemantics.metadataEntries(handler, recipeIndex);
        final List<JsonObject> diagrams = NeiDiagramProvider.metadataEntries(handler, recipeIndex);
        if (entries.isEmpty() && semantics.isEmpty() && diagrams.isEmpty() && !NeiUiAccess.is(handler, "gregtech.nei.GTNEIDefaultHandler")) { out.unsupported("No audited typed metadata provider for this handler."); return; }
        if ("metadata".equals(selection.target)) {
            final int gtSize = entries.size(), total = gtSize + semantics.size() + diagrams.size();
            List<Integer> indices = new java.util.AbstractList<Integer>() { public Integer get(int i) { return i; } public int size() { return total; } };
            page(indices, out, (entry, i) -> {
                JsonObject json = new JsonObject(); json.addProperty("metadataIndex", i);
                if (i >= gtSize) { int base = i < gtSize + semantics.size() ? gtSize : gtSize + semantics.size(); JsonObject descriptor = i < gtSize + semantics.size() ? semantics.get(i - base) : diagrams.get(i - base); for (Map.Entry<String, JsonElement> property : descriptor.entrySet()) json.add(property.getKey(), property.getValue()); json.addProperty("metadataIndex", i); metadataContinuations(json, base, 0); return json; }
                Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entries.get(i); Object key = pair.getKey(); json.addProperty("provider", "gregtech"); json.addProperty("source", "GTRecipe.getMetadataStorage().getEntries() encounter order"); json.addProperty("keyClass", key.getClass().getName()); json.addProperty("keyIdentity", Integer.toHexString(System.identityHashCode(key))); json.add("identifier", primitive(NeiUiAccess.get(env.loader, "gregtech.api.recipe.RecipeMetadataKey", key, "identifier"))); json.add("value", value(pair.getValue(), env, out)); json.addProperty("valueSize", collectionSize(pair.getValue())); return json;
            });
            return;
        }
        int metadataIndex = index(args, "metadataIndex"); if (metadataIndex >= entries.size() + semantics.size() + diagrams.size()) throw new BridgeException("not_found", "metadataIndex is absent.");
        if (metadataIndex >= entries.size()) {
            int[] path = new int[args.has("valuePath") ? args.getAsJsonArray("valuePath").size() : 0]; for (int i = 0; i < path.length; i++) path[i] = args.getAsJsonArray("valuePath").get(i).getAsInt();
            int providerBase = metadataIndex < entries.size() + semantics.size() ? entries.size() : entries.size() + semantics.size();
            JsonObject result = metadataIndex < entries.size() + semantics.size() ? NeiRecipeSemantics.metadataValue(handler, recipeIndex, metadataIndex - providerBase, path, out.request.offset, out.request.limit, out.started) : NeiDiagramProvider.metadataValue(handler, recipeIndex, metadataIndex - providerBase, path, out.request.offset, out.request.limit, out.started);
            out.status = result.get("status").getAsString(); if ("ok".equals(out.status)) out.status = "readable"; out.total = result.get("total").getAsInt(); out.next = result.get("nextOffset").getAsInt();
            if (result.has("limit")) out.effectiveLimit = result.get("limit").getAsInt();
            if (result.has("byteLimited")) out.byteLimited = result.get("byteLimited").getAsBoolean(); if (result.has("softBudgetPartial")) out.timedOut = result.get("softBudgetPartial").getAsBoolean();
            out.partial = "partial".equals(out.status) || "unsupported".equals(out.status) || !result.get("complete").getAsBoolean() && out.next >= out.total;
            if (result.has("data")) for (Map.Entry<String, JsonElement> property : result.getAsJsonObject("data").entrySet()) out.data.add(property.getKey(), property.getValue());
            for (String key : new String[] {"source", "provider", "reason", "availability", "knownVisibility", "contextCoverage", "budgetSource"}) if (result.has(key)) out.data.add(key, result.get(key));
            if (result.has("coverage")) out.data.add("providerCoverage", result.get("coverage"));
            if (result.has("data")) for (Map.Entry<String, JsonElement> property : result.getAsJsonObject("data").entrySet()) metadataContinuations(property.getValue(), providerBase, 0);
            out.data.addProperty("metadataIndex", metadataIndex);
            return;
        }
        Object selected = ((Map.Entry<?, ?>) entries.get(metadataIndex)).getValue();
        if (args.has("valuePath")) for (JsonElement selector : args.getAsJsonArray("valuePath")) { int child = selector.getAsInt(); List<?> list = collection(selected); if (child >= list.size()) throw new BridgeException("not_found", "valuePath index is absent."); selected = list.get(child); }
        if (selected instanceof String) text((String) selected, out);
        else if (selected != null && (selected.getClass().isArray() || selected instanceof List<?>)) { out.effectiveLimit = Math.min(out.request.limit, 64); page(collection(selected), out, (entry, i) -> value(entry, env, out)); }
        else { out.data.add("value", value(selected, env, out)); out.single(); }
    }

    private static void metadataContinuations(JsonElement value, int base, int depth) { if (depth > 12 || value == null) return; if (value.isJsonArray()) { for (JsonElement child : value.getAsJsonArray()) metadataContinuations(child, base, depth + 1); } else if (value.isJsonObject()) { JsonObject object = value.getAsJsonObject(); if (object.has("target") && "metadata_value".equals(object.get("target").getAsString())) { String key = object.has("semanticMetadataIndex") ? "semanticMetadataIndex" : object.has("diagramMetadataIndex") ? "diagramMetadataIndex" : object.has("metadataIndex") ? "metadataIndex" : ""; if (!key.isEmpty()) { int local = object.get(key).getAsInt(); object.remove(key); object.addProperty("metadataIndex", base + local); } } for (Map.Entry<String, JsonElement> child : object.entrySet()) metadataContinuations(child.getValue(), base, depth + 1); } }

    private static List<?> gtMetadataEntries(Environment env, Object handler, int recipeIndex) {
        if (!NeiUiAccess.is(handler, "gregtech.nei.GTNEIDefaultHandler")) return Collections.emptyList();
        List<?> cached = list(field(env, P + "recipe.TemplateRecipeHandler", handler, "arecipes"));
        if (recipeIndex >= cached.size()) throw new BridgeException("not_found", "Cached GT recipeIndex is absent.");
        Object recipe = field(env, "gregtech.nei.GTNEIDefaultHandler$CachedDefaultRecipe", cached.get(recipeIndex), "mRecipe");
        Object storage = call(env, "gregtech.api.util.GTRecipe", recipe, "getMetadataStorage");
        Object values = call(env, "gregtech.api.recipe.metadata.IRecipeMetadataStorage", storage, "getEntries");
        if (!(values instanceof java.util.Set<?>)) throw new BridgeException("adapter_error", "Invalid GT metadata entries.");
        return iterable((java.util.Set<?>) values);
    }

    static String metadataContext(Environment env, Object handler, int recipeIndex) { List<?> entries = gtMetadataEntries(env, handler, recipeIndex); StringBuilder result = new StringBuilder().append(entries.size()); int[] visited = {0}; for (int i = 0; i < Math.min(entries.size(), 256) && visited[0] < 512; i++) { Map.Entry<?, ?> pair = (Map.Entry<?, ?>) entries.get(i); result.append('/').append(System.identityHashCode(pair.getKey())).append(':'); metadataValueContext(result, pair.getValue(), 0, visited); } return result.toString(); }
    private static void metadataValueContext(StringBuilder result, Object value, int depth, int[] visited) { if (visited[0]++ >= 512) return; if (value instanceof String) { String text = (String) value; result.append(text.length()).append('/').append(text.substring(0, Math.min(text.length(), 4096)).hashCode()); } else if (value == null || value instanceof Number || value instanceof Boolean) result.append(value); else if (value instanceof Enum<?>) result.append(((Enum<?>) value).name()); else if (value instanceof ItemStack) result.append(NeiViewAdapter.stackSignature(value)); else if (value.getClass().isArray() || value instanceof List<?>) { List<?> children = collection(value); result.append(children.size()); if (depth < 3) for (int i = 0; i < Math.min(children.size(), 256) && visited[0] < 512; i++) { result.append('/'); metadataValueContext(result, children.get(i), depth + 1, visited); } } else result.append(value.getClass().getName()).append('/').append(System.identityHashCode(value)); }

    private static JsonElement value(Object value, Environment env, Read out) {
        if (value instanceof ItemStack) return NeiDataAdapter.stack((ItemStack) value, null, false, false);
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String || value instanceof Enum<?>) return primitive(value);
        if (value.getClass().isArray() || value instanceof List<?>) { JsonObject summary = new JsonObject(); summary.addProperty("class", value.getClass().getName()); summary.addProperty("total", collectionSize(value)); summary.addProperty("continuation", "metadata_value with valuePath"); return summary; }
        JsonElement described = GTRecipeAdapter.value(value, 0);
        if (described.isJsonObject() && described.getAsJsonObject().has("available") && !described.getAsJsonObject().get("available").getAsBoolean()) out.partial("Unknown typed metadata class is explicit; no arbitrary serializer is executed.");
        return described;
    }

    private static ItemStack stack(Environment env, JsonObject args, Selection selection) {
        Object item;
        switch (selection.stackSource) {
            case "recipe": {
                Object position = selectedPosition(env, args, selection);
                if (args.has("alternativeIndex")) { List<?> alternatives = array(field(env, P + "PositionedStack", position, "items")); int index = index(args, "alternativeIndex"); if (index >= alternatives.size()) throw new BridgeException("not_found", "alternativeIndex is absent."); item = alternatives.get(index); }
                else item = field(env, P + "PositionedStack", position, "item"); break;
            }
            case "panel": {
                Panel panel = env.panel(args.has("panel") ? JsonData.string(args, "panel") : "item"); int index = index(args, "itemIndex");
                if ("bookmarks".equals(panel.name)) item = field(env, P + "bookmark.BookmarkItem", bookmarkItem(env, index), "itemStack");
                else { if (index >= panel.items.size()) throw new BridgeException("not_found", "itemIndex is outside current filtered panel."); item = panel.items.get(index); }
                break;
            }
            case "catalyst": {
                List<?> positions = catalysts(env); int index = index(args, "itemIndex"); if (index >= positions.size()) throw new BridgeException("not_found", "catalyst itemIndex is absent."); item = field(env, P + "PositionedStack", positions.get(index), "item"); break;
            }
            case "tree_item": {
                Object math = NeiUiAccess.get(env.loader, P + "bookmark.tree.GuiCraftingTree", env.screen, "math"); List<?> items = list(field(env, P + "bookmark.tree.CraftingTreeMath", math, "items")); int index = index(args, "itemIndex"); if (index >= items.size()) throw new BridgeException("not_found", "tree itemIndex is absent."); item = NeiUiAccess.get(env.loader, P + "bookmark.tree.ItemTreeSlot", items.get(index), "itemStack"); break;
            }
            case "tree_stat": {
                List<?> widgets = widgets(env, "tree_stats"); int index = index(args, "itemIndex"); if (index >= widgets.size()) throw new BridgeException("not_found", "tree stats itemIndex is absent."); item = NeiUiAccess.get(env.loader, P + "bookmark.tree.StatsPanel$ItemStatsSlot", widgets.get(index), "stack"); break;
            }
            default: throw new BridgeException("invalid_args", "Unknown stackSource.");
        }
        return item instanceof ItemStack ? (ItemStack) item : null;
    }

    static List<?> widgets(Environment env, String source) {
        if ("layout".equals(source)) { Object values = NeiUiAccess.get(env.loader, P + "LayoutManager", null, "drawWidgets"); return values instanceof java.util.Collection<?> ? iterable((java.util.Collection<?>) values) : Collections.emptyList(); }
        Object container;
        if ("recipe".equals(source)) { if (!env.recipeScreen) return Collections.emptyList(); container = NeiUiAccess.get(env.loader, GUI, env.screen, "container"); }
        else if ("tree_stats".equals(source)) { if (!NeiUiAccess.is(env.screen, P + "bookmark.tree.GuiCraftingTree")) return Collections.emptyList(); container = NeiUiAccess.get(env.loader, P + "bookmark.tree.GuiCraftingTree", env.screen, "statsPanel"); }
        else if ("tree".equals(source)) { if (!NeiUiAccess.is(env.screen, P + "bookmark.tree.GuiCraftingTree")) return Collections.emptyList(); container = NeiUiAccess.get(env.loader, P + "bookmark.tree.GuiCraftingTree", env.screen, "toolbar"); }
        else if ("options".equals(source)) {
            if (!NeiUiAccess.is(env.screen, P + "config.GuiOptionList")) return Collections.emptyList();
            container = NeiUiAccess.get(env.loader, P + "config.GuiOptionList", env.screen, "slot"); return list(field(env, P + "config.GuiOptionList$OptionScrollSlot", container, "options"));
        } else throw new BridgeException("invalid_args", "Unknown widgetSource.");
        return container == null ? Collections.emptyList() : list(call(env, P + "WidgetContainer", container, "getWidgets"));
    }

    private static JsonElement widget(Object entry, Environment env, EntityPlayer player, Read out) {
        if (entry == null) return JsonNull.INSTANCE;
        JsonObject result = NeiViewAdapter.widget(entry, env.loader).getAsJsonObject();
        String owner = entry.getClass().getName();
        if (NeiUiAccess.is(entry, P + "bookmark.tree.StatsPanel$ItemStatsSlot")) {
            String api = P + "bookmark.tree.StatsPanel$ItemStatsSlot";
            for (String name : new String[] {"stackSize", "multiplier", "highlight"}) result.add(name, primitive(NeiUiAccess.get(env.loader, api, entry, name)));
            Object stack = NeiUiAccess.get(env.loader, api, entry, "stack"); result.add("item", stack instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) stack, player, out.request.include, out.request.advanced) : JsonNull.INSTANCE);
            result.add("recipeId", NeiViewAdapter.recipeId(NeiUiAccess.get(env.loader, api, entry, "recipeId"), env.loader));
        } else if (NeiUiAccess.is(entry, P + "bookmark.tree.StatsPanel$HandlerStatsSlot")) {
            Object stats = NeiUiAccess.get(env.loader, P + "bookmark.tree.StatsPanel$HandlerStatsSlot", entry, "stats");
            result.add("handlerName", primitive(NeiUiAccess.get(env.loader, P + "bookmark.tree.StatsPanel$HandlerStatsSlot", entry, "handlerName")));
            for (String name : new String[] {"iterations", "recipeCount"}) result.add(name, primitive(NeiUiAccess.get(env.loader, P + "bookmark.tree.StatsPanel$HandlerStats", stats, name)));
        } else if (NeiUiAccess.is(entry, P + "bookmark.tree.StatsPanel$SectionLabel")) result.add("text", primitive(NeiUiAccess.get(env.loader, P + "bookmark.tree.StatsPanel$SectionLabel", entry, "text")));
        else if (NeiUiAccess.is(entry, P + "TextField")) result.add("text", primitive(call(env, P + "TextField", entry, "text")));
        else if (NeiUiAccess.is(entry, P + "Button")) {
            result.add("label", primitive(optionalCall(env, P + "Button", entry, "getRenderLabel")));
            result.add("tooltip", primitive(optionalCall(env, P + "Button", entry, "getButtonTip")));
        } else if (NeiUiAccess.is(entry, P + "config.OptionButton")) {
            result.add("label", primitive(optionalCall(env, P + "config.OptionButton", entry, "getButtonText")));
            result.add("tooltip", primitive(optionalCall(env, P + "config.OptionButton", entry, "getTooltip")));
        }
        return result;
    }

    private static JsonObject treeNode(Object item, Environment env, EntityPlayer player, Read out) {
        JsonObject result = new JsonObject(); String owner = P + "bookmark.tree.ItemTreeSlot";
        result.addProperty("nodeId", nodeId(item));
        for (String name : new String[] {"ingeritRequestedAmount", "currentRequestedAmount", "requestedAmount", "containerAmount", "producedAmount", "leftAmount", "isFluidDisplay", "isContainerItem", "multiplier"}) result.add(name, primitive(NeiUiAccess.get(env.loader, owner, item, name)));
        Object stack = NeiUiAccess.get(env.loader, owner, item, "itemStack"); result.add("item", stack instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) stack, player, out.request.include, out.request.advanced) : JsonNull.INSTANCE);
        for (String name : new String[] {"ingrItem", "prefItem"}) { Object bookmark = NeiUiAccess.get(env.loader, owner, item, name); result.add(name, bookmark == null ? JsonNull.INSTANCE : NeiViewAdapter.bookmark(bookmark, env.loader, player, out.request)); }
        return result;
    }

    private static JsonObject subset(Object entry, Environment env) {
        String owner = P + "SubsetWidget$SubsetTag"; JsonObject result = new JsonObject();
        for (String name : new String[] {"fullname", "path", "parentPath", "state"}) result.add(name, primitive(NeiUiAccess.get(env.loader, owner, entry, name)));
        result.add("displayName", primitive(call(env, owner, entry, "displayName")));
        Object visible = NeiUiAccess.get(env.loader, owner, entry, "visible"); result.add("visible", primitive(visible));
        Object items = NeiUiAccess.get(env.loader, owner, entry, "items"), children = NeiUiAccess.get(env.loader, owner, entry, "children");
        result.addProperty("itemCount", items instanceof List<?> ? ((List<?>) items).size() : 0); result.addProperty("childCount", children instanceof List<?> ? ((List<?>) children).size() : 0);
        return result;
    }

    private static JsonObject position(Object value, Environment env, EntityPlayer player, Read out) {
        JsonObject result = new JsonObject(); if (value == null) return result;
        for (String name : new String[] {"relx", "rely", "width", "height"}) result.add(name, primitive(field(env, P + "PositionedStack", value, name)));
        result.add("chance", primitive(call(env, P + "PositionedStack", value, "getChance"))); result.add("acceptsLabel", primitive(call(env, P + "PositionedStack", value, "getAcceptsLabel")));
        Object stack = field(env, P + "PositionedStack", value, "item"); result.add("item", stack instanceof ItemStack ? NeiDataAdapter.stack((ItemStack) stack, player, out.request.include, out.request.advanced) : JsonNull.INSTANCE);
        result.addProperty("alternativesTotal", array(field(env, P + "PositionedStack", value, "items")).size());
        result.addProperty("tooltipTotal", list(call(env, P + "PositionedStack", value, "getTooltip")).size());
        Object badges = optionalCall(env, P + "PositionedStack", value, "getBadges"); result.addProperty("badgesTotal", badges instanceof List<?> ? ((List<?>) badges).size() : 0);
        if (NeiUiAccess.is(value, "gregtech.nei.GTNEIDefaultHandler$FixedPositionedStack")) {
            String owner = "gregtech.nei.GTNEIDefaultHandler$FixedPositionedStack";
            for (String getter : new String[] {"isInput", "isNotConsumed", "isFluid", "getSelectedFluidIndex"}) result.add(getter, primitive(optionalCall(env, owner, value, getter)));
            Object amount = optionalField(env, owner, value, "realStackSize"); if (amount != null) result.add("realStackSize", primitive(amount));
        }
        return result;
    }

    private static JsonElement badge(Object badge, Environment env) { JsonObject result = new JsonObject(); if (badge == null) return result; result.addProperty("class", badge.getClass().getName()); for (String getter : new String[] {"getText", "getColor", "hasShadow", "getAlignment"}) result.add(getter, primitive(call(env, P + "recipe.Badge", badge, getter))); result.addProperty("tooltipTotal", list(call(env, P + "recipe.Badge", badge, "getTooltip")).size()); return result; }
    static Object config(Environment env, String source) { Object set = field(env, P + "NEIClientConfig", null, source); if (set == null) throw new BridgeException("not_found", "NEI config scope is not initialized."); return field(env, P + "config.ConfigSet", set, "config"); }
    static JsonObject configTag(Object tag, Environment env) { JsonObject result = new JsonObject(); for (String name : new String[] {"name", "qualifiedname", "value"}) result.add(name, primitive(field(env, "codechicken.lib.config.ConfigTag", tag, name))); Object children = call(env, "codechicken.lib.config.ConfigTagParent", tag, "childTagMap"); result.addProperty("childCount", children instanceof Map<?, ?> ? ((Map<?, ?>) children).size() : 0); return result; }
    private static Object handler(Environment env, JsonObject args) {
        if (!env.recipeScreen) throw new BridgeException("unsupported", "Recipe detail requires GuiRecipe.");
        int index = args.has("handlerIndex") ? index(args, "handlerIndex") : env.handlerIndex;
        if (index < 0 || index >= env.handlers.size()) throw new BridgeException("not_found", "handlerIndex is absent."); return env.handlers.get(index);
    }
    private static Object selectedPosition(Environment env, JsonObject args, Selection selection) { List<?> positions = positions(env, handler(env, args), index(args, "recipeIndex"), selection.kind); int index = args.has("positionIndex") ? index(args, "positionIndex") : 0; if (index >= positions.size()) throw new BridgeException("not_found", "positionIndex is absent."); return positions.get(index); }
    private static List<?> positions(Environment env, Object handler, int index, String kind) {
        int count = ((Number) call(env, HANDLER, handler, "numRecipes")).intValue(); if (index >= count) throw new BridgeException("not_found", "Original recipeIndex is absent.");
        String getter = "inputs".equals(kind) ? "getIngredientStacks" : "outputs".equals(kind) ? "getResultStack" : "getOtherStacks";
        Object value = call(env, HANDLER, handler, getter, new Class<?>[] {int.class}, index);
        return "outputs".equals(kind) ? value == null ? Collections.emptyList() : Collections.singletonList(value) : list(value);
    }
    private static Object bookmarkItem(Environment env, int index) { Panel panel = env.panel("bookmarks"); Object item = call(env, P + "bookmark.BookmarkGrid", panel.grid, "getBookmarkItem", new Class<?>[] {int.class}, index); if (item == null) throw new BridgeException("not_found", "Active bookmark itemIndex is absent."); return item; }
    private static List<?> catalysts(Environment env) { if (!env.recipeScreen) return Collections.emptyList(); Object widget = call(env, GUI, env.screen, "getRecipeCatalystWidget"); return array(NeiUiAccess.get(env.loader, P + "recipe.GuiRecipeCatalyst", widget, "items")); }
    private static void text(String text, Read out) { out.data.addProperty("unit", "characters"); out.data.addProperty("offsetUnit", "unicode_codepoints"); out.total = text.codePointCount(0, text.length()); out.next = Math.min(out.request.offset, out.total); int end = out.next + Math.min(out.effectiveLimit, out.total - out.next); out.data.addProperty("text", text.substring(text.offsetByCodePoints(0, out.next), text.offsetByCodePoints(0, end))); out.next = end; }
    interface Encoder { JsonElement encode(Object value, int index); }
    private static void page(List<?> values, Read out, Encoder encoder) {
        out.total = values.size(); out.next = Math.min(out.request.offset, out.total); JsonArray entries = new JsonArray(); out.data.add("entries", entries);
        while (out.next < out.total && entries.size() < out.effectiveLimit && out.progress(entries.size())) {
            JsonObject entry = new JsonObject(); entry.addProperty("index", out.next); entry.add("value", encoder.encode(values.get(out.next), out.next));
            if (!out.add(entries, entry)) break; out.next++;
        }
    }
    private static List<?> collection(Object value) { if (value instanceof List<?>) return (List<?>) value; if (value != null && value.getClass().isArray()) return array(value); throw new BridgeException("invalid_args", "valuePath can select only an existing array or list."); }
    private static int collectionSize(Object value) { return value instanceof List<?> ? ((List<?>) value).size() : value != null && value.getClass().isArray() ? Array.getLength(value) : 1; }
    private static List<?> array(final Object value) { if (value == null) return Collections.emptyList(); if (!value.getClass().isArray()) throw new BridgeException("adapter_error", "Expected public model array."); return new java.util.AbstractList<Object>() { public int size() { return Array.getLength(value); } public Object get(int index) { return Array.get(value, index); } }; }
    // Iteration preserves the current map/set order. UI context detects model replacement; response is non-atomic.
    private static List<?> iterable(final java.util.Collection<?> values) { return new java.util.AbstractList<Object>() { private java.util.Iterator<?> cursor = values.iterator(); private int next; public int size() { return values.size(); } public Object get(int index) { if (index < next) { cursor = values.iterator(); next = 0; } while (cursor.hasNext()) { Object value = cursor.next(); if (next++ == index) return value; } throw new IndexOutOfBoundsException(); } }; }
    private static List<?> mapValues(Map<?, ?> values) { return iterable(values.values()); }
    private static List<?> mapEntries(Map<?, ?> values) { return iterable(values.entrySet()); }
    static String context(Environment env) {
        StringBuilder value = new StringBuilder();
        for (String name : new String[] {"drawWidgets", "controlWidgets"}) {
            Object widgets = NeiUiAccess.optional(env.loader, P + "LayoutManager", null, name);
            if (widgets instanceof java.util.Collection<?>) {
                java.util.Collection<?> entries = (java.util.Collection<?>) widgets; value.append('/').append(entries.size()); int i = 0;
                for (Object entry : entries) { if (i++ >= 256) break; value.append('/').append(NeiViewAdapter.widget(entry, env.loader)); if (NeiUiAccess.is(entry, P + "TextField")) value.append('/').append(optionalCall(env, P + "TextField", entry, "text")); }
            }
        }
        if (env.recipeScreen) {
            Object container = NeiUiAccess.optional(env.loader, GUI, env.screen, "container");
            Object widgets = container == null ? null : optionalCall(env, P + "WidgetContainer", container, "getWidgets");
            value.append(':').append(widgets instanceof List<?> ? ((List<?>) widgets).size() : 0);
            Object search = NeiUiAccess.optional(env.loader, GUI, env.screen, "searchField");
            value.append(':').append(search == null ? "" : optionalCall(env, P + "TextField", search, "text"));
            Object catalyst = optionalCall(env, GUI, env.screen, "getRecipeCatalystWidget");
            Object items = catalyst == null ? null : NeiUiAccess.optional(env.loader, P + "recipe.GuiRecipeCatalyst", catalyst, "items");
            List<?> catalystItems = array(items); value.append(':').append(catalystItems.size());
            for (int i = 0; i < Math.min(catalystItems.size(), 256); i++) { Object position = catalystItems.get(i); value.append('/').append(optionalField(env, P + "PositionedStack", position, "relx")).append('/').append(optionalField(env, P + "PositionedStack", position, "rely")); Object alternatives = optionalField(env, P + "PositionedStack", position, "items"); List<?> stacks = array(alternatives); value.append('/').append(stacks.size()); for (int j = 0; j < Math.min(stacks.size(), 256); j++) value.append('/').append(NeiViewAdapter.stackSignature(stacks.get(j))); }
        }
        if (NeiUiAccess.is(env.screen, P + "bookmark.tree.GuiCraftingTree")) {
            String owner = P + "bookmark.tree.GuiCraftingTree";
            for (String name : new String[] {"grid", "groupId", "math", "canvas", "statsPanelVisible", "uiState"}) { Object state = NeiUiAccess.optional(env.loader, owner, env.screen, name); value.append(':').append(state instanceof Number || state instanceof Boolean ? String.valueOf(state) : System.identityHashCode(state)); }
            Object math = NeiUiAccess.optional(env.loader, owner, env.screen, "math");
            if (math != null) for (String name : new String[] {"rootSlots", "items", "parentOf", "childrenOf"}) { Object nodes = optionalField(env, P + "bookmark.tree.CraftingTreeMath", math, name); value.append(':').append(System.identityHashCode(nodes)).append('/').append(nodes instanceof Map<?, ?> ? ((Map<?, ?>) nodes).size() : collectionSize(nodes)); }
        }
        Object tags = NeiUiAccess.optional(env.loader, P + "SubsetWidget", null, "tags"); value.append(':').append(System.identityHashCode(tags)).append('/').append(tags instanceof Map<?, ?> ? ((Map<?, ?>) tags).size() : 0);
        return value.toString();
    }
    private static List<?> list(Object value) { return value == null ? Collections.emptyList() : value instanceof List<?> ? (List<?>) value : Collections.emptyList(); }
    private static String nodeId(Object value) { return value == null ? "" : Integer.toHexString(System.identityHashCode(value)); }
    private static int index(JsonObject args, String key) { return JsonData.integer(args, key, 0, Integer.MAX_VALUE); }
    private static Class<?> type(Environment env, String owner) { try { return Class.forName(owner, false, env.loader); } catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "Public model type unavailable: " + owner); } }
    private static Object field(Environment env, String owner, Object target, String name) { return NeiUiAccess.publicField(env.loader, owner, target, name); }
    private static Object optionalField(Environment env, String owner, Object target, String name) { try { return field(env, owner, target, name); } catch (BridgeException error) { return null; } }
    private static Object call(Environment env, String owner, Object target, String name) { return call(env, owner, target, name, new Class<?>[0]); }
    private static Object call(Environment env, String owner, Object target, String name, Class<?>[] types, Object... args) { return NeiUiAccess.publicCall(env.loader, owner, target, name, types, args); }
    private static Object optionalCall(Environment env, String owner, Object target, String name) { try { return call(env, owner, target, name); } catch (BridgeException error) { return null; } }
    private static JsonElement primitive(Object value) { if (value == null) return JsonNull.INSTANCE; if (value instanceof Number) return new JsonPrimitive((Number) value); if (value instanceof Boolean) return new JsonPrimitive((Boolean) value); if (value instanceof Enum<?>) return new JsonPrimitive(((Enum<?>) value).name()); return new JsonPrimitive(JsonData.bounded(String.valueOf(value), 8192)); }

    private static final class Selection {
        final String target, kind, stackSource, tooltipSource, widgetSource;
        Selection(JsonObject args) {
            target = JsonData.string(args, "target"); if (!TARGETS.contains(target)) throw new BridgeException("invalid_args", "Unknown detail target.");
            boolean positioned = Arrays.asList("positions", "alternatives", "badges").contains(target), metadata = Arrays.asList("metadata", "metadata_value").contains(target), text = Arrays.asList("tooltip_lines", "tooltip_text", "nbt", "item_visuals").contains(target);
            if ("item_visuals".equals(target)) { allow(args, "advanced", false); allow(args, "includeTooltips", false); allow(args, "modifiers", false); }
            stackSource = args.has("stackSource") ? JsonData.string(args, "stackSource") : "recipe";
            if (!Arrays.asList("recipe", "panel", "catalyst", "tree_item", "tree_stat").contains(stackSource)) throw new BridgeException("invalid_args", "Unknown stackSource.");
            boolean recipe = positioned || metadata || text && "recipe".equals(stackSource);
            allow(args, "stackSource", text); allow(args, "handlerIndex", recipe); allow(args, "recipeIndex", recipe);
            if (recipe) index(args, "recipeIndex");
            kind = args.has("positionKind") ? JsonData.string(args, "positionKind") : "inputs";
            if (!Arrays.asList("inputs", "outputs", "other").contains(kind)) throw new BridgeException("invalid_args", "Unknown positionKind.");
            allow(args, "positionKind", positioned || text && "recipe".equals(stackSource));
            allow(args, "positionIndex", Arrays.asList("alternatives", "badges").contains(target) || text && "recipe".equals(stackSource));
            if (Arrays.asList("alternatives", "badges").contains(target)) index(args, "positionIndex");
            else if (args.has("positionIndex")) index(args, "positionIndex");
            allow(args, "alternativeIndex", text && "recipe".equals(stackSource)); if (args.has("alternativeIndex")) index(args, "alternativeIndex");
            boolean item = text && !"recipe".equals(stackSource) || "bookmark_permutations".equals(target);
            allow(args, "itemIndex", item); if (item) index(args, "itemIndex");
            allow(args, "panel", text && "panel".equals(stackSource));
            tooltipSource = args.has("tooltipSource") ? JsonData.string(args, "tooltipSource") : "item";
            if (!Arrays.asList("item", "position", "handler", "badge").contains(tooltipSource)) throw new BridgeException("invalid_args", "Unknown tooltipSource.");
            allow(args, "tooltipSource", Arrays.asList("tooltip_lines", "tooltip_text").contains(target));
            if (!"item".equals(tooltipSource) && !"recipe".equals(stackSource)) throw new BridgeException("invalid_args", "position/handler tooltipSource requires recipe stackSource.");
            allow(args, "badgeIndex", "badge".equals(tooltipSource) && Arrays.asList("tooltip_lines", "tooltip_text").contains(target)); if ("badge".equals(tooltipSource)) index(args, "badgeIndex");
            allow(args, "lineIndex", "tooltip_text".equals(target)); if ("tooltip_text".equals(target)) index(args, "lineIndex");
            allow(args, "metadataIndex", "metadata_value".equals(target)); if ("metadata_value".equals(target)) index(args, "metadataIndex");
            allow(args, "valuePath", "metadata_value".equals(target));
            if (args.has("valuePath")) { if (!args.get("valuePath").isJsonArray() || args.getAsJsonArray("valuePath").size() > 8) throw new BridgeException("invalid_args", "valuePath must be an array of at most eight indices."); for (JsonElement value : args.getAsJsonArray("valuePath")) { JsonObject selector = new JsonObject(); selector.add("index", value); index(selector, "index"); } }
            widgetSource = args.has("widgetSource") ? JsonData.string(args, "widgetSource") : "layout";
            allow(args, "widgetSource", "widgets".equals(target)); if (!Arrays.asList("layout", "recipe", "tree", "tree_stats", "options").contains(widgetSource)) throw new BridgeException("invalid_args", "Unknown widgetSource.");
            allow(args, "settingSource", "settings".equals(target)); allow(args, "configPath", "settings".equals(target)); allow(args, "subsetPath", "subset_items".equals(target));
            if (args.has("settingSource") && !Arrays.asList("global", "world").contains(JsonData.string(args, "settingSource"))) throw new BridgeException("invalid_args", "Unknown settingSource.");
            for (String key : new String[] {"configPath", "subsetPath"}) if (args.has(key) && JsonData.string(args, key).length() > 512) throw new BridgeException("invalid_args", key + " exceeds 512 characters.");
            if ("subset_items".equals(target) && JsonData.string(args, "subsetPath").isEmpty()) throw new BridgeException("invalid_args", "subsetPath must be nonempty.");
        }
        private static void allow(JsonObject args, String key, boolean allowed) { if (!allowed && args.has(key)) throw new BridgeException("invalid_args", key + " is not valid for this detail target."); }
    }
}
