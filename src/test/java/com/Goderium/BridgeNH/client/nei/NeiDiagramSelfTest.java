package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.recipe.GuiRecipe;
import com.github.dcysteine.neicustomdiagram.api.diagram.CompositeDiagram;
import com.github.dcysteine.neicustomdiagram.api.diagram.CustomDiagramState;
import com.github.dcysteine.neicustomdiagram.api.diagram.Diagram;
import com.github.dcysteine.neicustomdiagram.api.diagram.DiagramGroup;
import com.github.dcysteine.neicustomdiagram.api.diagram.DiagramState;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.DisplayComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.ItemComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.interactable.CustomInteractable;
import com.github.dcysteine.neicustomdiagram.api.diagram.interactable.InteractiveComponentGroup;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Layout;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Lines;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Slot;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.SlotGroup;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Text;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.TooltipElement;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.TooltipLine;
import com.github.dcysteine.neicustomdiagram.api.draw.Point;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Definition pages, current animation selection and read-only unknown callback regression fixtures. */
public final class NeiDiagramSelfTest {
    private static int checks;
    private static long generous() { return System.nanoTime() + 60000000000L; }
    public static int run() { checks = 0; verify(); return checks; }
    private static JsonObject args(String target) { JsonObject args = new JsonObject(); args.addProperty("screenId", "diagram-fixture"); args.addProperty("section", "detail"); args.addProperty("target", target); args.addProperty("recipeIndex", 0); return args; }
    private static JsonObject value(int metadataIndex, int... path) { JsonObject args = args("metadata_value"); args.addProperty("metadataIndex", metadataIndex); JsonArray indices = new JsonArray(); for (int index : path) indices.add(new JsonPrimitive(index)); args.add("valuePath", indices); return args; }
    private static JsonObject read(GuiRecipe screen, JsonObject args) { return NeiViewAdapter.read(screen, null, args, "diagram-session", generous()); }
    private static void verify() {
        Layout layout = new Layout(); StringBuilder longText = new StringBuilder("A\uD83D\uDE80B"); for (int i = 0; i < 900; i++) longText.append('x');
        List<TooltipLine> tooltipLines = new ArrayList<TooltipLine>(); List<Lines.Segment> segments = new ArrayList<Lines.Segment>(); List<Slot> groupedSlots = new ArrayList<Slot>();
        for (int i = 0; i < 150; i++) {
            layout.labels.add(new Text("label-" + i + "|"));
            segments.add(new Lines.Segment(new Point(i, 1), new Point(i + 1, 2)));
            if (i == 0) { List<TooltipElement> elements = new ArrayList<TooltipElement>(); for (int j = 0; j < 150; j++) elements.add(new TooltipElement(j == 0 ? longText.toString() : "element-" + j + "|")); tooltipLines.add(new TooltipLine(elements)); }
            else tooltipLines.add(new TooltipLine(Collections.singletonList(new TooltipElement("tooltip-" + i + "|"))));
        }
        layout.labels.add(new Text(longText.toString())); layout.lines.add(new Lines(segments)); Tooltip tooltip = new Tooltip(tooltipLines);
        for (int i = 0; i < 150; i++) { Slot slot = new Slot(tooltip); layout.slots.put(new Layout.SlotKey("slot-" + i), slot); groupedSlots.add(slot); }
        layout.slotGroups.put(new Layout.SlotGroupKey("all-slots"), new SlotGroup(groupedSlots));
        List<DisplayComponent> candidates = new ArrayList<DisplayComponent>();
        for (int i = 0; i < 150; i++) candidates.add(new DisplayComponent(new ItemComponent(new StructuredInfoSelfTest.FakeItem()), tooltip));
        List<Object> interactables = new ArrayList<Object>(); interactables.add(new InteractiveComponentGroup(candidates, tooltip)); UnknownDrawing drawing = new UnknownDrawing(); interactables.add(new CustomInteractable(drawing, tooltip));
        DiagramState state = new DiagramState(); DiagramGroup handler = new DiagramGroup(Collections.singletonList(new Diagram(layout, interactables)), state); GuiRecipe screen = new GuiRecipe(handler, Collections.singletonList(0));
        JsonObject data = read(screen, args("metadata"));
        check(data.get("total").getAsInt() == 7, "current NCD definitions use seven metadata descriptors without a new target");
        String token = data.get("contextToken").getAsString();
        assertEntries(screen, value(1), token, 151, "all 150+ labels continue beyond64 and128");
        JsonObject request = value(1, 150, 0); request.addProperty("offset", 1); request.addProperty("limit", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && data.getAsJsonObject("data").get("offsetUnit").getAsString().equals("unicode_codepoints"), "diagram label character page preserves one complete supplementary character");
        request.remove("offset"); request.remove("limit"); request.remove("contextToken"); data = read(screen, request); check(data.get("total").getAsInt() == 903 && data.get("complete").getAsBoolean(), "default character page reaches complete long literal diagram text");
        assertEntries(screen, value(2, 0, 0), token, 150, "all existing line segments continue beyond64");
        assertEntries(screen, value(3), token, 150, "all existing named slots continue beyond64");
        assertEntries(screen, value(4, 0, 0), token, 150, "all existing grouped slots continue beyond64");
        assertEntries(screen, value(3, 0, 0), token, 150, "all stored tooltip lines continue beyond64 without producing draw text");
        assertEntries(screen, value(3, 0, 0, 0), token, 150, "all stored tooltip elements continue beyond64 within a single line");
        request = value(3, 0, 0, 0, 0, 0); request.addProperty("offset", 1); request.addProperty("limit", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80"), "nested tooltip text stays reachable within the existing valuePath contract");
        assertEntries(screen, value(5, 0, 0), token, 150, "all stored component candidates continue beyond64");
        request = value(5, 0, 0, 0, 2); request.addProperty("offset", 1); request.addProperty("limit", 1); request.addProperty("contextToken", token); data = read(screen, request);
        check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80"), "candidate additional information has complete character continuation");
        request = value(5); request.addProperty("contextToken", token); data = read(screen, request); check(data.getAsJsonObject("data").has("entries"), "component overview is readable: " + data.getAsJsonObject("data").get("reason")); JsonObject before = data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject();
        state.fixtureTicks(20); data = read(screen, request); JsonObject after = data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject();
        check(data.get("contextToken").getAsString().equals(token) && before.get("selectedIndex").getAsInt() == 0 && after.get("selectedIndex").getAsInt() == 1 && after.get("animationNonAtomic").getAsBoolean(), "normal cycle retains token while reporting changed current selection");
        request = value(5, 0, 0); request.addProperty("offset", 128); request.addProperty("contextToken", token); data = read(screen, request); check(data.get("nextOffset").getAsInt() == 150, "normal animation does not prevent continuation over every stored candidate");
        check(drawing.calls == 0 && data.getAsJsonObject("data").has("providerCoverage"), "unknown drawing is explicit and provider coverage survives integration without callbacks");
        final JsonObject stale = value(1); stale.addProperty("contextToken", token); layout.labels.set(0, new Text("changed literal definition")); expectStale(() -> read(screen, stale));
        layout.labels.set(0, new Text("label-0|"));
        CustomDiagramState customState = new CustomDiagramState(); customState.fixtureTicks(20); DiagramGroup custom = new DiagramGroup(Collections.singletonList(new CompositeDiagram(layout, interactables)), customState);
        JsonObject unknown = NeiDiagramProvider.read(custom, 0, 0, 7, generous()); check(unknown.has("coverage") && drawing.calls == 0, "composite/custom-state coverage remains explicit without active/state/draw callbacks");
        Layout unknownLayout = new Layout(); UnknownText unknownText = new UnknownText(); unknownLayout.labels.add(unknownText); DiagramGroup unknownHandler = new DiagramGroup(Collections.singletonList(new Diagram(unknownLayout, Collections.emptyList())), new DiagramState());
        unknown = NeiDiagramProvider.metadataValue(unknownHandler, 0, 1, new int[0], 0, 64, generous()); check(unknownText.calls == 0 && unknown.has("coverage"), "unknown text overrides remain explicit without invoking their getters");
        JsonObject recipe = new JsonObject(); recipe.addProperty("screenId", "diagram-fixture"); recipe.addProperty("section", "recipe"); recipe.addProperty("recipeIndex", 0); data = read(screen, recipe);
        check(data.getAsJsonObject("data").getAsJsonArray("recipes").get(0).getAsJsonObject().has("diagram"), "recipe overview exposes the current diagram provider summary");
    }
    private static void assertEntries(GuiRecipe screen, JsonObject request, String token, int total, String label) {
        request.addProperty("limit", 64); List<JsonElement> entries = new ArrayList<JsonElement>();
        for (int offset = 0; offset < total;) { request.addProperty("offset", offset); request.addProperty("contextToken", token); JsonObject data = read(screen, request); check(data.get("total").getAsInt() == total, label + " has actual model total; actual=" + data.get("total") + " status=" + data.get("status") + " reason=" + data.getAsJsonObject("data").get("reason")); JsonArray page = data.getAsJsonObject("data").getAsJsonArray("entries"); for (JsonElement entry : page) entries.add(entry); int next = data.get("nextOffset").getAsInt(); check(next > offset && next - offset == page.size(), label + " advances without skips"); offset = next; }
        check(entries.size() == total, label);
    }
    private static final class UnknownDrawing { int calls; public void draw() { calls++; throw new AssertionError("Unknown rendering callback must not run"); } @Override public String toString() { calls++; throw new AssertionError("Unknown drawable serialization must not run"); } }
    private static final class UnknownText extends Text { int calls; UnknownText() { super("unknown"); } @Override public String text() { calls++; throw new AssertionError("Unknown text override must not run"); } }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expectStale(Runnable action) { try { action.run(); throw new AssertionError("Expected stale_context"); } catch (BridgeException error) { check(error.code.equals("stale_context"), "semantic definition edit invalidates the old token"); } }
}
