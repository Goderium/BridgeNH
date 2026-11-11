package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native-list pass-through, late additions, immutable completed frames and real view pagination. */
public final class NeiHoverSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0; NeiHoverCapture.clear();
        verifyNativeRegistration();
        verifyNativeDecisionHooks();
        GuiRecipe screen = new GuiRecipe(new IRecipeHandler() {
            public String getRecipeName() { return "native Tooltip fixture"; }
            public int numRecipes() { return 1; }
            public List<codechicken.nei.PositionedStack> getIngredientStacks(int recipe) { return Collections.emptyList(); }
            public codechicken.nei.PositionedStack getResultStack(int recipe) { return null; }
            public List<codechicken.nei.PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
        }, Collections.singletonList(0));
        List<String> lines = new ArrayList<String>(); lines.add("A\uD83D\uDE80B");
        Map<String, String> keys = new LinkedHashMap<String, String>(); keys.put("R", "Recipe");
        NeiHoverCapture.begin(screen, NeiRenderCapture.marker(screen), 10, 20);
        check(NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 12, 34, lines}) == lines, "native original List identity is returned");
        check(NeiHoverCapture.observe("handleHotkeys", new Object[] {screen, 12, 34, keys}) == keys, "native original hotkey Map identity is returned");
        check(NeiHoverCapture.observe("handleTooltip", null) == null, "null callback is safe");
        lines.add("late native handler addition"); keys.put("U", "Usage");
        check(NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen)) == null, "unfinished frame is never returned");
        NeiHoverCapture.end(screen, NeiRenderCapture.marker(screen));
        NeiHoverCapture.Snapshot first = NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen));
        check(first.lines.size() == 2 && first.hotkeys.size() == 2 && first.lines.get(1).text.contains("late native"), "copy at frame end retains native later additions");
        lines.clear(); keys.clear(); check(first.lines.size() == 2 && first.hotkeys.size() == 2, "published frame is detached from mutable native collections");
        JsonObject args = detail("hover_tooltip_lines"); args.addProperty("limit", 1); JsonObject data = read(screen, args); String token = data.get("contextToken").getAsString();
        check(data.get("nextOffset").getAsInt() == 1 && data.getAsJsonObject("data").get("frameId").getAsLong() == first.frameId, "actual view pins one completed Tooltip frame");
        NeiHoverCapture.begin(screen, NeiRenderCapture.marker(screen), 50, 60);
        NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 50, 60, Collections.singletonList("new hover")}); NeiHoverCapture.end(screen, NeiRenderCapture.marker(screen));
        args.addProperty("offset", 1); args.addProperty("contextToken", token); data = read(screen, args);
        check(data.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("text").getAsString().contains("late native"), "new natural frames cannot replace a pinned continuation");
        JsonObject text = detail("hover_tooltip_text"); text.addProperty("lineIndex", 0); text.addProperty("limit", 1); text.addProperty("offset", 1); text.addProperty("contextToken", token);
        data = read(screen, text); check(data.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && data.get("total").getAsInt() == 3, "Tooltip continuation preserves Unicode codepoints");
        data = read(screen, detail("hover_tooltip_lines")); check(data.getAsJsonObject("data").get("frameId").getAsLong() != first.frameId, "offset zero without token recaptures latest natural frame");
        final JsonObject stale = text; expect("stale_context", () -> read(screen, stale));
        for (String invalid : new String[] {"advanced", "modifiers", "x", "y", "includeTooltips"}) {
            JsonObject bad = detail("hover_tooltip_lines"); bad.addProperty(invalid, false); expect("invalid_args", () -> read(screen, bad));
        }
        NeiHoverCapture.begin(screen, NeiRenderCapture.marker(screen), 0, 0);
        List<String> huge = new ArrayList<String>(); StringBuilder value = new StringBuilder(); for (int i = 0; i < 9000; i++) value.append("\uD83D\uDE80"); huge.add(value.toString());
        NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 0, 0, huge}); NeiHoverCapture.end(screen, NeiRenderCapture.marker(screen));
        check(NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen)).limited && NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen)).lines.get(0).codepoints == 8192, "native long line has explicit safe retention bound");
        NeiViewAdapter.invalidateRendered(); check(NeiHoverCapture.latest(screen, NeiRenderCapture.marker(screen)) == null, "view invalidation clears both passive sinks");
        data = read(screen, detail("hover_tooltip_lines")); check(data.get("status").getAsString().equals("not_present"), "missing native frame is explicit");
        verifyPreviewEntryContracts(screen);
        verifyVisualEntrySelectors(screen);
        verifyChromaticPublicEvents(screen);
        return checks;
    }
    public static final class PublicTooltipContext {
        final net.minecraft.item.ItemStack item;
        PublicTooltipContext(net.minecraft.item.ItemStack item) { this.item = item; }
        public net.minecraft.item.ItemStack getItem() { return item; }
        public Object getActivePageComponent() { throw new AssertionError("No page construction getter"); }
        public Object getTooltipSize() { throw new AssertionError("No lazy tooltip layout getter"); }
        public void nextTooltipPage() { throw new AssertionError("No page mutation"); }
        public void previousTooltipPage() { throw new AssertionError("No page mutation"); }
    }
    public static final class PublicCustomComponent {
        public int getWidth() { return 17; }
        public int getHeight() { return 13; }
        public int getSpacing() { return 3; }
        public Object getLines() { throw new AssertionError("Unknown custom getter must not be guessed"); }
        public void draw() { throw new AssertionError("No custom redraw"); }
    }
    private static void verifyChromaticPublicEvents(Object screen) throws Exception {
        List<String> text = new ArrayList<String>(); text.add("actual page A\uD83D\uDE80B"); text.add("second native line");
        com.slprime.chromatictooltips.component.TextComponent nativeText = new com.slprime.chromatictooltips.component.TextComponent(text);
        List<Object> children = new ArrayList<Object>(); children.add(nativeText); children.add(new PublicCustomComponent());
        com.slprime.chromatictooltips.component.SectionComponent page = new com.slprime.chromatictooltips.component.SectionComponent(children);
        net.minecraft.item.ItemStack item = new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 3, 7);
        com.slprime.chromatictooltips.event.RenderTooltipEvent event = new com.slprime.chromatictooltips.event.RenderTooltipEvent(new PublicTooltipContext(item), page, 14, 21, 100, 80);
        NeiChromaticTooltipCapture.Page decoded = new NeiChromaticTooltipCapture.Page(event, Long.MAX_VALUE);
        check(decoded.lines.size() == 2 && decoded.lines.get(0).text.equals(text.get(0)) && decoded.nodes.size() == 3, "public page decoder preserves ordered text and custom component metrics without redraw");
        check(decoded.data.getAsJsonArray("gaps").toString().contains("nontext_custom_or_inline") && !decoded.data.get("textContentComplete").getAsBoolean(), "unknown component content remains an explicit gap even when its public dimensions are available");
        check(decoded.data.get("pageIndex").isJsonNull() && decoded.data.get("totalPages").isJsonNull() && decoded.data.getAsJsonObject("rectangle").get("x").getAsInt() == 14, "actual event rectangle is retained while unavailable protected page totals are not inferred");
        String marker = NeiRenderCapture.marker(screen); NeiHoverCapture.begin(screen, marker, 2, 3);
        List<String> callback = new ArrayList<String>(); callback.add("Temporary Name");
        NeiHoverCapture.observe("handleItemTooltip", new Object[] {screen, item, 2, 3, callback});
        new NeiRenderCapture().tooltipDrawn(event); callback.clear(); item.stackSize = 99;
        NeiHoverCapture.end(screen, marker); NeiHoverCapture.Snapshot snapshot = NeiHoverCapture.latest(screen, marker);
        text.clear(); children.clear();
        check(snapshot.lines.size() == 2 && !snapshot.lines.get(0).text.contains("Temporary Name") && snapshot.stack.stackSize == 3, "public drawn-page text/item snapshot is detached from cleared callback Lists and later native model/item changes");
        check(!snapshot.nativeGate.get("decisionObserved").getAsBoolean() && !snapshot.nativeRender.get("finalListHandoffObserved").getAsBoolean(), "Chromatic public event cannot populate unobserved vanilla gate or native final List handoff");
        check(snapshot.callbackDiagnostics.get("retainedCallbackListSizeAtFrameEnd").getAsInt() == 0 && snapshot.chromaticRender.get("publicEventCalls").getAsInt() == 1 && snapshot.source.contains("callback Lists are separate diagnostics") && snapshot.phase.contains("after_drawContent"), "empty callback List and actual drawn Chromatic page have separate provenance and counts");
        JsonObject view = read(screen, detail("hover_tooltip_lines"));
        check(view.getAsJsonObject("data").getAsJsonArray("entries").size() == 2 && view.getAsJsonObject("data").getAsJsonObject("chromaticTooltipRender").get("publicEventObserved").getAsBoolean(), "existing hover entry exposes event page metadata and its retained real text");
        long frame = snapshot.frameId; new NeiRenderCapture().tooltipDrawn(event);
        check(NeiHoverCapture.latest(screen, marker).frameId == frame, "public event outside an active natural frame cannot replace a completed snapshot");
        text.add("wrong-thread event"); children.add(nativeText); NeiHoverCapture.begin(screen, marker, 2, 3);
        Thread other = new Thread(() -> new NeiRenderCapture().tooltipDrawn(event)); other.start(); other.join();
        new NeiRenderCapture().tooltipDrawn(new cpw.mods.fml.common.eventhandler.Event()); NeiHoverCapture.end(screen, marker);
        check(!NeiHoverCapture.latest(screen, marker).chromaticRender.get("publicEventObserved").getAsBoolean(), "wrong thread and unrelated Forge events cannot enter the public page observer");
        List<String> oversized = new ArrayList<String>(); StringBuilder value = new StringBuilder(); for (int i = 0; i < 9000; i++) value.append("\uD83D\uDE80"); oversized.add(value.toString());
        com.slprime.chromatictooltips.component.SectionComponent huge = new com.slprime.chromatictooltips.component.SectionComponent(Collections.<Object>singletonList(new com.slprime.chromatictooltips.component.TextComponent(oversized)));
        decoded = new NeiChromaticTooltipCapture.Page(new com.slprime.chromatictooltips.event.RenderTooltipEvent(new PublicTooltipContext(null), huge, 0, 0, 1, 1), Long.MAX_VALUE);
        check(decoded.limited && decoded.lines.get(0).codepoints == 8192 && decoded.lines.get(0).originalUtf16 == 18000, "public event text retention preserves Unicode bounds and original lengths");
        List<Object> cycle = new ArrayList<Object>(); com.slprime.chromatictooltips.component.SectionComponent cyclic = new com.slprime.chromatictooltips.component.SectionComponent(cycle); cycle.add(cyclic);
        decoded = new NeiChromaticTooltipCapture.Page(new com.slprime.chromatictooltips.event.RenderTooltipEvent(new PublicTooltipContext(null), cyclic, 0, 0, 1, 1), Long.MAX_VALUE);
        check(decoded.nodes.size() == 1 && decoded.data.getAsJsonArray("gaps").toString().contains("cyclic"), "cyclic public children are bounded without recursion or native mutation");
        List<Object> many = new ArrayList<Object>(); for (int i = 0; i < 300; i++) many.add(new PublicCustomComponent());
        decoded = new NeiChromaticTooltipCapture.Page(new com.slprime.chromatictooltips.event.RenderTooltipEvent(new PublicTooltipContext(null), new com.slprime.chromatictooltips.component.SectionComponent(many), 0, 0, 1, 1), Long.MAX_VALUE);
        check(decoded.limited && decoded.nodes.size() == 256, "large public component trees are bounded independently of page text count");
        Object nested = nativeText; for (int i = 0; i < 20; i++) nested = new com.slprime.chromatictooltips.component.SectionComponent(Collections.singletonList(nested));
        decoded = new NeiChromaticTooltipCapture.Page(new com.slprime.chromatictooltips.event.RenderTooltipEvent(new PublicTooltipContext(null), nested, 0, 0, 1, 1), Long.MAX_VALUE);
        check(decoded.limited && decoded.nodes.size() == 17 && decoded.data.getAsJsonArray("gaps").toString().contains("depth_limit"), "deep public section trees never traverse inaccessible descendants past the explicit depth budget");
        decoded = new NeiChromaticTooltipCapture.Page(event, 0);
        check(decoded.limited && decoded.nodes.size() == 0 && decoded.data.getAsJsonArray("gaps").toString().contains("budget_exhausted"), "depleted public event observer budget never fabricates component coverage");
        NeiHoverCapture.clear();
    }
    private static void verifyVisualEntrySelectors(Object screen) {
        for (String key : new String[] {"advanced", "includeTooltips", "modifiers", "textureSide"}) {
            JsonObject args = detail("item_visuals"); args.addProperty("recipeIndex", 0); args.addProperty(key, false); expect("invalid_args", () -> read(screen, args));
            JsonObject preview = detail("preview_candidate_visuals"); preview.addProperty("groupIndex", 0); preview.addProperty("alternativeIndex", 0); preview.addProperty(key, false); expect("invalid_args", () -> read(screen, preview));
        }
        for (String source : new String[] {"panel", "catalyst", "tree_item", "tree_stat"}) {
            JsonObject args = detail("item_visuals"); args.addProperty("stackSource", source); args.addProperty("itemIndex", 0); args.addProperty("recipeIndex", 0); expect("invalid_args", () -> read(screen, args));
        }
        JsonObject recipe = detail("item_visuals"); recipe.addProperty("recipeIndex", 0); recipe.addProperty("itemIndex", 0); expect("invalid_args", () -> read(screen, recipe));
        JsonObject preview = detail("preview_candidate_visuals"); preview.addProperty("groupIndex", 0); preview.addProperty("alternativeIndex", 0);
        check(read(screen, preview).get("status").getAsString().equals("not_present"), "preview candidate visuals route existing absent scene without scanning");
        JsonObject defaults = new JsonObject(); defaults.addProperty("screenId", "hover-fixture");
        check(read(screen, defaults).get("section").getAsString().equals("summary"), "default summary remains valid after hover and visual request plumbing");
    }
    private static void verifyPreviewEntryContracts(Object screen) {
        JsonObject single = detail("preview_block");
        check(new NeiViewAdapter.Request(single).limit == 1, "selected preview block defaults to its single-result limit");
        for (String target : new String[] {"preview_block", "preview_hint_text", "preview_candidate_groups", "preview_candidate_items", "preview_candidate_tooltip_lines", "preview_candidate_tooltip_text", "preview_candidate_nbt", "preview_textures"}) {
            JsonObject request = detail(target);
            if (target.startsWith("preview_candidate_") && !target.equals("preview_candidate_groups")) request.addProperty("groupIndex", 0);
            if (target.equals("preview_candidate_tooltip_lines") || target.equals("preview_candidate_tooltip_text") || target.equals("preview_candidate_nbt")) request.addProperty("alternativeIndex", 0);
            if (target.equals("preview_candidate_tooltip_text")) request.addProperty("lineIndex", 0);
            check(read(screen, request).get("status").getAsString().equals("not_present"), "actual entry routes explicit absent preview target " + target);
        }
        for (String target : new String[] {"preview_native_model", "preview_ctm_context", "preview_render_state", "preview_render_geometry"}) {
            JsonObject request = detail(target); request.addProperty("limit", 64);
            check(read(screen, request).get("status").getAsString().equals("not_present"), "native render target reads absence without arming/drawing " + target);
            request.addProperty("limit", 65); expect("invalid_args", () -> read(screen, request)); request.addProperty("limit", 64);
            request.addProperty("recipeIndex", 0); expect("invalid_args", () -> read(screen, request)); request.remove("recipeIndex");
            request.addProperty("previewBlockSource", "position"); JsonObject point = new JsonObject(); point.addProperty("x", 0); point.addProperty("y", 64); point.addProperty("z", 0); point.addProperty("method", "draw"); request.add("previewPosition", point); expect("invalid_args", () -> read(screen, request));
        }
        JsonObject position = new JsonObject(); position.addProperty("x", -33554432); position.addProperty("y", 2047); position.addProperty("z", 33554431);
        single.addProperty("previewBlockSource", "position"); single.add("previewPosition", position);
        check(new NeiViewAdapter.Request(single).limit == 1, "preview entry accepts complete signed packed coordinate boundaries");
        position.addProperty("y", 2048); expect("invalid_args", () -> read(screen, single));
        JsonObject invalid = detail("preview_candidate_groups"); invalid.addProperty("advanced", true); expect("invalid_args", () -> read(screen, invalid));
        JsonObject nbt = detail("preview_candidate_nbt"); nbt.addProperty("groupIndex", 0); nbt.addProperty("alternativeIndex", 0); nbt.add("modifiers", new JsonObject()); expect("invalid_args", () -> read(screen, nbt));
        JsonObject text = detail("preview_hint_text"); text.addProperty("limit", 8192); check(new NeiViewAdapter.Request(text).limit == 8192, "text continuation uses full bounded Unicode limit");
        JsonObject resourceRead = detail("preview_candidate_groups"); String token = read(screen, resourceRead).get("contextToken").getAsString();
        resourceRead.addProperty("contextToken", token); NeiPreviewResources.changedForFixture();
        expect("stale_context", () -> read(screen, resourceRead));
        resourceRead.remove("contextToken"); check(read(screen, resourceRead).get("status").getAsString().equals("not_present"), "resource reload recapture works without a built preview");
    }
    private static void verifyNativeRegistration() {
        net.minecraft.client.gui.inventory.GuiContainer nativeScreen = new net.minecraft.client.gui.inventory.GuiContainer(null) {
            protected void drawGuiContainerBackgroundLayer(float partial, int x, int y) { }
        };
        codechicken.nei.guihook.GuiContainerManager manager = new codechicken.nei.guihook.GuiContainerManager();
        codechicken.nei.guihook.GuiContainerManager.current = manager;
        codechicken.nei.guihook.IContainerTooltipHandler first = new codechicken.nei.guihook.IContainerTooltipHandler() { };
        codechicken.nei.guihook.IContainerTooltipHandler second = new codechicken.nei.guihook.IContainerTooltipHandler() { };
        manager.instanceTooltipHandlers.add(first); manager.instanceTooltipHandlers.add(second);
        NeiHoverCapture.begin(nativeScreen, "registration", 1, 2);
        check(manager.instanceTooltipHandlers.size() == 3 && manager.instanceTooltipHandlers.get(0) == first && manager.instanceTooltipHandlers.get(1) == second,
                "actual reflection registration adds only own observer after ordered native handlers");
        codechicken.nei.guihook.IContainerTooltipHandler observer = manager.instanceTooltipHandlers.get(2);
        List<String> lines = new ArrayList<String>(); lines.add("native proxy callback");
        check(observer.handleTooltip(nativeScreen, 3, 4, lines) == lines, "actual native dynamic Proxy returns original collection");
        lines.add("after callback"); NeiHoverCapture.end(nativeScreen, "registration");
        check(NeiHoverCapture.latest(nativeScreen, "registration").registration.equals("registered_native_listener")
                && NeiHoverCapture.latest(nativeScreen, "registration").lines.size() == 2, "native registration and passive final-frame copy both execute");
        NeiHoverCapture.Snapshot observed = NeiHoverCapture.latest(nativeScreen, "registration");
        check(observed.pointer.get("cursorSource").getAsString().equals("physical") && !observed.pointer.get("naturalDrawInvoked").getAsBoolean(), "manual fixture callback never claims logical native draw acceptance");
        check(observed.nativeGate.get("status").getAsString().equals("not_observed") && !observed.nativeGate.get("decisionObserved").getAsBoolean() && !observed.nativeGate.get("forced").getAsBoolean(), "listener callback alone cannot invent the actual renderToolTips gate decision");
        check(observed.callbackDiagnostics.getAsJsonObject("handleTooltip").get("calls").getAsInt() == 1 && observed.callbackDiagnostics.getAsJsonObject("handleTooltip").get("firstEnteringSize").getAsInt() == 1, "callback entry size is distinct from later additions at completed frame");
        codechicken.nei.guihook.GuiContainerManager.tooltipAllowed = false;
        int calls = codechicken.nei.guihook.GuiContainerManager.tooltipPredicateCalls;
        NeiHoverCapture.begin(nativeScreen, "suppressed", 1, 2); NeiHoverCapture.end(nativeScreen, "suppressed");
        check(NeiHoverCapture.latest(nativeScreen, "suppressed").nativeGate.get("sampledShouldShowTooltip").isJsonNull() && codechicken.nei.guihook.GuiContainerManager.tooltipPredicateCalls == calls, "completed-frame observation never invokes a second predicate or invents an unobserved decision");
        codechicken.nei.guihook.GuiContainerManager.tooltipAllowed = true;
        manager.instanceTooltipHandlers.add(second); NeiHoverCapture.begin(nativeScreen, "registration", 1, 2);
        check(manager.instanceTooltipHandlers.get(manager.instanceTooltipHandlers.size() - 1) == observer && manager.instanceTooltipHandlers.size() == 4,
                "late native listener keeps its order while only observer is moved last");
        NeiHoverCapture.end(nativeScreen, "registration"); NeiHoverCapture.clear(); codechicken.nei.guihook.GuiContainerManager.current = null;
    }
    private static void verifyNativeDecisionHooks() throws Exception {
        net.minecraft.client.gui.inventory.GuiContainer screen = new net.minecraft.client.gui.inventory.GuiContainer(null) { protected void drawGuiContainerBackgroundLayer(float partial, int x, int y) { } };
        com.Goderium.BridgeNH.mixin.NeiTooltipDecisionMixin hook = new com.Goderium.BridgeNH.mixin.NeiTooltipDecisionMixin() { }; hook.window = screen;
        Class<?> type = com.Goderium.BridgeNH.mixin.NeiTooltipDecisionMixin.class;
        java.lang.reflect.Method entry = type.getDeclaredMethod("codexbridge$tooltipEntry", int.class, int.class, org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        java.lang.reflect.Method gate = type.getDeclaredMethod("codexbridge$tooltipGate", boolean.class);
        java.lang.reflect.Method stackHook = type.getDeclaredMethod("codexbridge$tooltipStack", net.minecraft.item.ItemStack.class);
        java.lang.reflect.Method paged = type.getDeclaredMethod("codexbridge$tooltipPagedList", List.class);
        java.lang.reflect.Method alternative = type.getDeclaredMethod("codexbridge$tooltipAlternativeList", Object.class);
        for (java.lang.reflect.Method method : new java.lang.reflect.Method[] {entry, gate, stackHook, paged, alternative}) method.setAccessible(true);
        NeiHoverCapture.clear(); NeiHoverCapture.begin(screen, "native-decision", 10, 20); entry.invoke(hook, 31, 41, null);
        List<String> empty = new ArrayList<String>(); NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 31, 41, empty});
        codechicken.nei.guihook.GuiContainerManager.tooltipPredicateCalls = 0; codechicken.nei.guihook.GuiContainerManager.tooltipAllowed = false;
        boolean original = codechicken.nei.guihook.GuiContainerManager.shouldShowTooltip(screen);
        check(!(Boolean) gate.invoke(hook, original), "actual decision callback preserves a false native gate");
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 3);
        check(stackHook.invoke(hook, stack) == stack && paged.invoke(hook, empty) == empty, "actual stack and final paged List callback return the exact original objects");
        NeiHoverCapture.end(screen, "native-decision"); NeiHoverCapture.Snapshot denied = NeiHoverCapture.latest(screen, "native-decision");
        check(codechicken.nei.guihook.GuiContainerManager.tooltipPredicateCalls == 1 && denied.nativeGate.get("decisionCalls").getAsInt() == 1 && !denied.nativeGate.get("shouldShowTooltip").getAsBoolean(), "predicate runs once and its original false result remains recorded after completion");
        check(denied.stack != null && denied.stack != stack && denied.stack.stackSize == 3 && denied.lines.isEmpty(), "selected native stack survives an empty final Tooltip and is copied only at frame completion");
        check(denied.nativeRender.get("renderMethodObserved").getAsBoolean() && denied.nativeRender.get("mouseX").getAsInt() == 31 && denied.nativeRender.get("finalListHandoffObserved").getAsBoolean() && denied.nativeRender.get("finalListEnteringSize").getAsInt() == 0, "empty final handoff and actual method entry coordinates remain explicit");
        check(!denied.nativeRender.get("rendererCompleted").getAsBoolean() && denied.source.contains("drawPagedTooltip"), "a native List handoff never claims renderer or visible-pixel success");
        NeiHoverCapture.begin(screen, "alternative", 8, 9); entry.invoke(hook, 8, 9, null);
        List<String> callback = new ArrayList<String>(); callback.add("replaced native handler List"); NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 8, 9, callback});
        List<String> finalList = new ArrayList<String>(); finalList.add("original alternative renderer List");
        check((Boolean) gate.invoke(hook, true) && alternative.invoke(hook, finalList) == finalList, "true gate and alternative Consumer original argument pass through unchanged");
        finalList.add("native addition after handoff"); callback.clear(); NeiHoverCapture.observe("handleTooltip", new Object[] {screen, 8, 9, callback});
        final java.util.concurrent.atomic.AtomicInteger threadFailures = new java.util.concurrent.atomic.AtomicInteger(); Thread other = new Thread(() -> { if (!NeiHoverCapture.nativeGateResult(screen, true)) threadFailures.incrementAndGet(); NeiHoverCapture.nativeFinalList(screen, Collections.singletonList("wrong-thread List"), "fixture"); }); other.start(); other.join();
        NeiHoverCapture.nativeGateResult(new Object(), false); NeiHoverCapture.nativeFinalList(new Object(), Collections.singletonList("wrong-screen List"), "fixture");
        NeiHoverCapture.end(screen, "alternative"); NeiHoverCapture.Snapshot accepted = NeiHoverCapture.latest(screen, "alternative"); finalList.clear();
        check(accepted.lines.size() == 2 && accepted.lines.get(0).text.equals("original alternative renderer List") && accepted.originalLines == 2, "actual final handoff overrides callback-only List and retains native later additions in a detached completed snapshot");
        check(accepted.nativeGate.get("decisionCalls").getAsInt() == 1 && accepted.nativeGate.get("shouldShowTooltip").getAsBoolean() && threadFailures.get() == 0, "another screen or thread cannot replace the actual native decision or final List");
        JsonObject counts = accepted.callbackDiagnostics.getAsJsonObject("handleTooltip");
        check(counts.get("calls").getAsInt() == 2 && counts.get("firstEnteringSize").getAsInt() == 1 && counts.get("lastEnteringSize").getAsInt() == 0 && counts.get("lastNativeCaller").getAsString().length() <= 128, "per-callback counts, entering sizes and short caller remain bounded and distinguish phases");
        check(accepted.phase.equals("renderToolTips.alternativeConsumer.accept") && accepted.nativeRender.get("finalListEnteringSize").getAsInt() == 1, "final renderer provenance and handoff size remain separate from completed List size");
        codechicken.nei.guihook.GuiContainerManager.tooltipAllowed = true; NeiHoverCapture.clear();
    }
    private static JsonObject detail(String target) { JsonObject args = new JsonObject(); args.addProperty("screenId", "hover-fixture"); args.addProperty("section", "detail"); args.addProperty("target", target); return args; }
    private static JsonObject read(Object screen, JsonObject args) { return NeiViewAdapter.read(screen, null, args, "hover-session", System.nanoTime() + 60000000000L); }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException error) { check(code.equals(error.code), "error " + code); } }
}
