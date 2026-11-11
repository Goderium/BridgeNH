package com.Goderium.BridgeNH.client.nei;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.item.ItemStack;

/** Detached actual-page observation through public Chromatic APIs only. No renderer or pagination call. */
final class NeiChromaticTooltipCapture {
    static final String EVENT = "com.slprime.chromatictooltips.event.RenderTooltipEvent";
    private static final String TEXT = "com.slprime.chromatictooltips.component.TextComponent";
    private static final String SECTION = "com.slprime.chromatictooltips.component.SectionComponent";
    private static final int NODE_LIMIT = 256, DEPTH_LIMIT = 16;

    static final class Page {
        final JsonObject data = new JsonObject();
        final JsonArray nodes = new JsonArray();
        final List<NeiHoverCapture.Line> lines = new ArrayList<>();
        final Set<String> gaps = new LinkedHashSet<>();
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        final long started = System.nanoTime(), budgetNanos;
        int originalLines, codepoints;
        boolean limited, itemObserved;
        ItemStack item;

        Page(Object event, long budgetNanos) {
            this.budgetNanos = Math.max(0L, budgetNanos);
            data.addProperty("source", EVENT + " posted after actual TooltipRenderer.drawContent returned; public event page/context/rectangle only.");
            data.addProperty("actualPageObserved", true); data.addProperty("drawContentReturned", true); data.addProperty("visiblePixelsVerified", false);
            data.addProperty("scope", "actual_drawn_page"); data.add("pageIndex", JsonNull.INSTANCE); data.add("totalPages", JsonNull.INSTANCE);
            data.addProperty("pageNavigationMetadataStatus", "no_pure_public_page_index_or_count_getter");
            gaps.add("page_index_total_count_and_flip_range_not_exposed_by_pure_public_getters");
            Class<?> type = event.getClass(); JsonObject rectangle = new JsonObject();
            for (String field : new String[] {"x", "y", "width", "height"}) {
                try { rectangle.addProperty(field, ((Number) NeiRenderReflection.publicField(type, event, field)).intValue()); }
                catch (RuntimeException error) { rectangle.add(field, JsonNull.INSTANCE); gaps.add("public_event_rectangle_unavailable:" + field); }
            }
            data.add("rectangle", rectangle); data.addProperty("rectangleProvenance", "Original public RenderTooltipEvent rectangle in Chromatic's scaled GUI coordinates; not component or pixel reconstruction.");
            try {
                Object context = NeiRenderReflection.publicField(type, event, "context");
                Object original = context == null ? null : NeiRenderReflection.call(context.getClass(), context, "getItem");
                itemObserved = context != null && (original == null || original instanceof ItemStack);
                item = original instanceof ItemStack ? ((ItemStack) original).copy() : null;
                if (!itemObserved) gaps.add("public_context_item_unavailable");
            } catch (RuntimeException | LinkageError error) { gaps.add("public_context_item_unavailable"); }
            try { node(NeiRenderReflection.publicField(type, event, "page"), -1, 0); }
            catch (RuntimeException | LinkageError error) { gaps.add("public_event_page_unavailable"); }
            visited.clear();
            data.addProperty("contextItemObserved", itemObserved); data.addProperty("capturedNodes", nodes.size()); data.add("components", nodes);
            data.addProperty("maximumNodes", NODE_LIMIT); data.addProperty("maximumDepth", DEPTH_LIMIT);
            data.addProperty("observerNanos", Math.max(0L, System.nanoTime() - started)); data.addProperty("maximumObserverNanos", this.budgetNanos);
            data.addProperty("captureLimited", limited); data.addProperty("textContentComplete", !limited && gaps.size() == 1);
            JsonArray missing = new JsonArray(); for (String gap : gaps) missing.add(new JsonPrimitive(gap)); data.add("gaps", missing);
        }

        private boolean budget() {
            if (nodes.size() >= NODE_LIMIT || System.nanoTime() - started >= budgetNanos) {
                limited = true; gaps.add("public_page_observer_budget_exhausted"); return false;
            }
            return true;
        }

        private void node(Object component, int parent, int depth) {
            if (!budget()) return;
            if (depth > DEPTH_LIMIT) { limited = true; gaps.add("public_page_component_depth_limit"); return; }
            if (component == null || !visited.add(component)) { gaps.add(component == null ? "null_public_page_component" : "repeated_or_cyclic_public_page_component"); return; }
            int index = nodes.size(); JsonObject node = new JsonObject(); nodes.add(node);
            node.addProperty("index", index); node.addProperty("parentIndex", parent); node.addProperty("depth", depth);
            node.addProperty("componentClass", NeiTextCapture.prefix(component.getClass().getName(), 256));
            for (String getter : new String[] {"getWidth", "getHeight", "getSpacing"}) {
                try { node.addProperty(getter.substring(3).toLowerCase(java.util.Locale.ROOT), ((Number) NeiRenderReflection.call(component.getClass(), component, getter)).intValue()); }
                catch (RuntimeException | LinkageError error) { gaps.add("public_component_metric_unavailable:" + getter); }
            }
            if (!budget()) return;
            if (NeiUiAccess.is(component, TEXT)) {
                node.addProperty("kind", "text"); node.addProperty("lineStart", lines.size());
                try {
                    Object value = NeiRenderReflection.call(component.getClass(), component, "getLines");
                    if (!(value instanceof List<?>)) { gaps.add("public_text_lines_unavailable"); return; }
                    List<?> text = (List<?>) value; originalLines += text.size();
                    for (Object line : text) {
                        if (lines.size() >= NeiHoverCapture.LINE_LIMIT || codepoints >= NeiHoverCapture.CODEPOINT_LIMIT || !budget()) { limited = true; gaps.add("public_page_text_retention_limit"); break; }
                        if (!(line instanceof String)) { gaps.add("non_string_public_text_line"); continue; }
                        String original = (String) line;
                        String retained = NeiTextCapture.prefix(original, Math.min(NeiHoverCapture.LINE_CODEPOINT_LIMIT, NeiHoverCapture.CODEPOINT_LIMIT - codepoints));
                        int count = retained.codePointCount(0, retained.length()); codepoints += count;
                        lines.add(new NeiHoverCapture.Line(retained, original.length(), count));
                        if (retained.length() != original.length()) { limited = true; gaps.add("public_page_text_retention_limit"); }
                    }
                    node.addProperty("originalLines", text.size()); node.addProperty("capturedLines", lines.size() - node.get("lineStart").getAsInt());
                } catch (RuntimeException | LinkageError error) { gaps.add("public_text_lines_unavailable"); }
            } else if (NeiUiAccess.is(component, SECTION)) {
                node.addProperty("kind", "section");
                try {
                    Object value = NeiRenderReflection.call(component.getClass(), component, "getComponents");
                    if (!(value instanceof List<?>)) { gaps.add("public_section_children_unavailable"); return; }
                    List<?> children = (List<?>) value; node.addProperty("originalChildren", children.size());
                    for (Object child : children) { if (!budget()) break; node(child, index, depth + 1); }
                } catch (RuntimeException | LinkageError error) { gaps.add("public_section_children_unavailable"); }
            } else {
                node.addProperty("kind", "public_metrics_only"); node.addProperty("contentStatus", "unknown_nontext_custom_or_inline_content");
                gaps.add("nontext_custom_or_inline_content_has_no_supported_public_child_text_or_icon_getter");
            }
        }
    }
}
