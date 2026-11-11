package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.util.BridgeException;

import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.IRecipeHandler;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.mixin.NeiFontRendererMixin;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import net.minecraft.item.ItemStack;
import codechicken.nei.PositionedStack;
import java.util.List;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Passive completed frames, bounded captures, Unicode tails, strict selectors and pinned continuation. */
public final class NeiRenderedSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0;
        NeiTextCapture.clear();
        NeiTextCapture.observe(new Object(), "outside NEI", 4, 5, 0xffffff, true);
        check(NeiTextCapture.latest(new Object(), "") == null && NeiTextCapture.observerActive(), "inactive observer collects no loading/HUD text");
        check(!RequestRegistry.readOnly("nei.preview_control") && RequestRegistry.readOnly("nei.view"), "preview actions retain mutation-grade request deduplication while passive reads stay read-only");
        check(NeiTextCapture.neiCaller(new StackTraceElement[] {new StackTraceElement("net.minecraft.client.gui.GuiScreen", "draw", "", 1)}).isEmpty(), "normal container text needs an actual NEI call path");
        check(NeiTextCapture.neiCaller(new StackTraceElement[] {new StackTraceElement("codechicken.nei.LayoutManager", "draw", "", 1)}).contains("LayoutManager.draw"), "normal container admits existing NEI rendering path");
        check(NeiTextCapture.prefix("A\uD83D\uDE80B", 2).equals("A\uD83D\uDE80"), "bounded capture never splits a surrogate pair");
        Object screen = new Object();
        NeiTextCapture.begin(screen, "stable", "fixture", true, 400, 240);
        NeiTextCapture.observe(new Object(), "shadow request", 8, 9, -1, true);
        check(NeiTextCapture.latest(screen, "stable") == null, "in-progress frame is never published");
        NeiTextCapture.end(screen, "stable");
        NeiTextCapture.Snapshot completed = NeiTextCapture.latest(screen, "stable");
        check(completed != null && completed.draws.size() == 1 && completed.draws.get(0).shadow, "one terminal request retains one shadow-aware draw record");
        check(completed.draws.get(0).x == 8 && completed.draws.get(0).y == 9 && completed.draws.get(0).color == -1, "raw local arguments retain signed color without invented pixel coordinates");
        NeiTextCapture.begin(screen, "changed", "fixture", true, 400, 240);
        check(NeiTextCapture.latest(screen, "stable") == completed, "a new draw does not invalidate the previous completed frame");
        NeiTextCapture.end(screen, "different");
        check(NeiTextCapture.latest(screen, "stable") == null, "mutation during a draw discards its inconsistent frame");
        verifyBounds();
        verifyMixinGuards();
        verifyDiagnostics();
        verifyPages();
        NeiTextCapture.clear();
        return checks;
    }

    private static void verifyMixinGuards() throws Exception {
        Object screen = new Object(); NeiFontRendererMixin hooks = new NeiFontRendererMixin() { };
        Class<?>[] signature = {String.class, int.class, int.class, int.class, boolean.class, CallbackInfoReturnable.class};
        Method terminal = NeiFontRendererMixin.class.getDeclaredMethod("codexbridge$observeTerminal", signature);
        Method line = NeiFontRendererMixin.class.getDeclaredMethod("codexbridge$observeLine", signature);
        terminal.setAccessible(true); line.setAccessible(true);
        long terminalsBefore = NeiTextCapture.terminalHooksLifetime(), linesBefore = NeiTextCapture.privateLineHooksLifetime();
        NeiTextCapture.begin(screen, "hooks", "fixture", true, 100, 100);
        // Angelica can complete the terminal request without invoking any private line or RETURN hook.
        terminal.invoke(hooks, "shadow", 1, 2, 3, true, null);
        terminal.invoke(hooks, "normal", 4, 5, 6, false, null);
        resetSpentBudget();
        line.invoke(hooks, "shadow", 2, 3, 3, true, null);
        NeiTextCapture.observeLine(hooks, "shadow", 1, 2, 3, false, terminalStack("drawString"));
        NeiTextCapture.observeLine(hooks, "normal", 4, 5, 6, false, terminalStack("func_85187_a"));
        // drawSplitString's existing wrapping/alignment emits lines directly to renderString, no terminal wrapper.
        resetSpentBudget();
        line.invoke(hooks, "wrapped one", 7, 8, 9, false, null);
        resetSpentBudget();
        line.invoke(hooks, "wrapped two", 7, 17, 9, false, null);
        resetSpentBudget();
        line.invoke(hooks, "wrapped two", 7, 17, 9, false, null);
        NeiTextCapture.end(screen, "hooks"); NeiTextCapture.Snapshot snapshot = NeiTextCapture.latest(screen, "hooks");
        check(snapshot.draws.size() == 5, "hook fixtures retain cached terminal requests without private callbacks and three independent wrapped draws");
        check(snapshot.draws.get(0).shadow && !snapshot.draws.get(1).shadow && !snapshot.draws.get(2).shadow, "terminal requests retain shadow metadata without mutable HEAD/RETURN depth state");
        check(snapshot.draws.get(3).text.equals(snapshot.draws.get(4).text) && snapshot.draws.get(2).y == 8 && snapshot.draws.get(3).y == 17,
                "standard wrapped-line coordinates and duplicate genuine calls remain distinct");
        check(snapshot.terminalHooks == 2 && snapshot.privateLineHooks == 6 && snapshot.shadowPassSkips == 1
                && snapshot.terminalAncestorSkips == 2 && snapshot.recordingAttempts == 5, "per-frame counters separate cache terminals, fallback lines and duplicate/shadow skips");
        check(NeiTextCapture.terminalHooksLifetime() == terminalsBefore + 2 && NeiTextCapture.privateLineHooksLifetime() == linesBefore + 6,
                "lifetime hook diagnostics include actual sink calls independently of retained entries");
        check(snapshot.draws.get(0).drawSource.equals("terminal_drawString_request") && snapshot.draws.get(2).drawSource.equals("private_renderString_line"),
                "retained entries distinguish terminal requests from independent wrapped lines");
        check(!NeiTextCapture.terminalAncestor(new StackTraceElement[] {new StackTraceElement("example.FontRenderer", "drawString", "", 1)}),
                "unrelated drawString methods cannot suppress real private lines");
        StackTraceElement[] bounded = new StackTraceElement[65];
        Arrays.fill(bounded, new StackTraceElement("example.Deep", "call", "", 1)); bounded[64] = terminalStack("drawString")[0];
        check(!NeiTextCapture.terminalAncestor(bounded), "private duplicate stack scanning stays bounded to64frames");
    }

    private static StackTraceElement[] terminalStack(String method) {
        return new StackTraceElement[] {new StackTraceElement("net.minecraft.client.gui.FontRenderer", method, "", 1)};
    }

    private static void verifyDiagnostics() {
        Object screen = new Object();
        NeiTextCapture.begin(screen, "empty", "fixture", true, 10, 10); NeiTextCapture.end(screen, "empty");
        NeiTextCapture.Snapshot snapshot = NeiTextCapture.latest(screen, "empty");
        check(snapshot.emptyCapture && snapshot.partial && snapshot.captureStatus.equals("no_font_hooks"), "completed frame with no font hooks cannot claim full rendered-text coverage");
        NeiViewAdapter.Read out = new NeiViewAdapter.Read(new NeiViewAdapter.Request(args("rendered")), generous()); out.rendered = snapshot;
        NeiRenderedAdapter.read(args("rendered"), out);
        check(out.status.equals("partial") && out.data.get("terminalHookCalls").getAsInt() == 0 && out.data.get("emptyCapture").getAsBoolean(),
                "rendered API distinguishes honest empty frames from a lifetime-active observer");
        NeiTextCapture.begin(screen, "emptyInputs", "fixture", true, 10, 10);
        NeiTextCapture.observe(screen, null, 0, 0, 0, false); NeiTextCapture.observe(screen, "", 0, 0, 0, false);
        NeiTextCapture.observeLine(screen, "shadow pass", 0, 0, 0, true); NeiTextCapture.end(screen, "emptyInputs");
        snapshot = NeiTextCapture.latest(screen, "emptyInputs");
        check(snapshot.terminalHooks == 2 && snapshot.privateLineHooks == 1 && snapshot.emptyInputs == 2 && snapshot.shadowPassSkips == 1
                && snapshot.recordingAttempts == 0 && snapshot.captureStatus.equals("no_text_retained"), "no retained text reports empty inputs and real hook activity instead of claiming interception failure");
        NeiTextCapture.begin(screen, "scope", "container_nei_callers", false, 10, 10);
        NeiTextCapture.observeLine(screen, "ordinary container", 0, 0, 0, false,
                new StackTraceElement[] {new StackTraceElement("net.minecraft.client.gui.GuiScreen", "draw", "", 1)});
        NeiTextCapture.end(screen, "scope"); snapshot = NeiTextCapture.latest(screen, "scope");
        check(snapshot.filteredDraws == 1 && snapshot.privateLineHooks == 1 && snapshot.recordingAttempts == 0 && snapshot.emptyCapture,
                "scope-filtered container calls remain diagnosable without collecting non-NEI text");
    }

    private static void verifyBounds() throws Exception {
        Object screen = new Object(); StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 9000; i++) huge.append("\uD83D\uDE80");
        NeiTextCapture.begin(screen, "bounds", "fixture", true, 10, 10);
        NeiTextCapture.observe(screen, huge.toString(), 0, 0, 0, false);
        NeiTextCapture.end(screen, "bounds");
        NeiTextCapture.Snapshot snapshot = NeiTextCapture.latest(screen, "bounds");
        check(snapshot.draws.get(0).codepoints == 8192 && snapshot.draws.get(0).originalUtf16Units == 18000 && snapshot.partial, "line budget retains explicit original length and truncation at8192codepoints");
        for (int i = 0; i < 120; i++) { NeiTextCapture.begin(screen, "warm", "fixture", true, 10, 10); NeiTextCapture.observe(screen, "small", 0, 0, 0, false); NeiTextCapture.end(screen, "warm"); }
        NeiTextCapture.begin(screen, "entries", "fixture", true, 10, 10);
        for (int i = 0; i < 520; i++) { resetSpentBudget(); NeiTextCapture.observe(screen, "item" + i, i, 0, 0, false); }
        NeiTextCapture.end(screen, "entries"); snapshot = NeiTextCapture.latest(screen, "entries");
        check(snapshot.draws.size() == 512 && snapshot.droppedDraws == 8 && snapshot.partial, "object cap retains at most512draws and reports omitted tail");
        NeiTextCapture.begin(screen, "characters", "fixture", true, 10, 10);
        for (int i = 0; i < 9; i++) { resetSpentBudget(); NeiTextCapture.observe(screen, huge.toString(), 0, 0, 0, false); }
        NeiTextCapture.end(screen, "characters"); snapshot = NeiTextCapture.latest(screen, "characters");
        check(snapshot.codepoints == 65536 && snapshot.draws.size() == 8 && snapshot.droppedDraws == 1, "aggregate memory cap is65536Unicodecodepoints per frame");
        NeiTextCapture.begin(screen, "budget", "fixture", true, 10, 10);
        Field active = NeiTextCapture.class.getDeclaredField("active"); active.setAccessible(true); Object frame = active.get(null);
        Field spent = frame.getClass().getDeclaredField("observerNanos"); spent.setAccessible(true); spent.setLong(frame, NeiTextCapture.OBSERVER_BUDGET_NANOS);
        NeiTextCapture.observe(screen, "over budget", 0, 0, 0, false); NeiTextCapture.end(screen, "budget"); snapshot = NeiTextCapture.latest(screen, "budget");
        check(snapshot.draws.isEmpty() && snapshot.budgetLimited && snapshot.unscopedAfterBudget == 1 && snapshot.partial, "CPU-budget cutoff is explicit instead of blocking the render thread");
        NeiTextCapture.begin(screen, "thread", "fixture", true, 10, 10);
        Thread wrongThread = new Thread(() -> NeiTextCapture.observe(screen, "wrong thread", 0, 0, 0, false)); wrongThread.start(); wrongThread.join();
        NeiTextCapture.end(screen, "thread"); check(NeiTextCapture.latest(screen, "thread").draws.isEmpty(), "other-thread font calls cannot enter a GUI frame");
    }

    private static void resetSpentBudget() throws Exception {
        Field active = NeiTextCapture.class.getDeclaredField("active"); active.setAccessible(true); Object frame = active.get(null);
        Field spent = frame.getClass().getDeclaredField("observerNanos"); spent.setAccessible(true); spent.setLong(frame, 0);
    }

    private static void verifyPages() throws Exception {
        IRecipeHandler handler = new IRecipeHandler() {
            public String getRecipeName() { return "captured rendering"; }
            public int numRecipes() { return 1; }
            public List<PositionedStack> getIngredientStacks(int recipe) { return Collections.emptyList(); }
            public PositionedStack getResultStack(int recipe) { return null; }
            public List<PositionedStack> getOtherStacks(int recipe) { return Collections.emptyList(); }
        };
        GuiRecipe screen = new GuiRecipe(handler, Arrays.asList(0)); String marker = NeiRenderCapture.marker(screen);
        NeiTextCapture.begin(screen, marker, "fixture", true, 640, 360);
        NeiTextCapture.observe(screen, "A\uD83D\uDE80B", 10, 20, 0xffffffff, true);
        for (int i = 1; i < 72; i++) { resetSpentBudget(); NeiTextCapture.observe(screen, "draw " + i, i, i, i, false); }
        NeiTextCapture.end(screen, marker);
        JsonObject first = read(screen, args("rendered")); String token = first.get("contextToken").getAsString();
        check(first.getAsJsonObject("data").get("textDrawsTotal").getAsInt() == 72 && first.getAsJsonObject("data").get("screenPositionStatus").getAsString().equals("unmapped"), "current completed-frame summary is structured with honest coordinates");
        JsonObject request = detail("rendered_text"); request.addProperty("limit", 64); request.addProperty("contextToken", token);
        JsonObject objects = read(screen, request); check(objects.get("nextOffset").getAsInt() == 64 && objects.get("total").getAsInt() == 72, "rendered object read has bounded continuation beyond64");
        NeiTextCapture.begin(screen, marker, "new frame", true, 640, 360); NeiTextCapture.observe(screen, "new frame only", 0, 0, 0, false); NeiTextCapture.end(screen, marker);
        JsonObject camera = receipt("set_camera", "applied", false); GameBridge.finishNeiPreviewControl(camera);
        check(camera.get("contextPreserved").getAsBoolean() && !camera.get("requiresViewRefresh").getAsBoolean(), "actual preview entry policy preserves pure camera contexts");
        JsonObject unchanged = receipt("set_channel", "unchanged", false); GameBridge.finishNeiPreviewControl(unchanged);
        check(unchanged.get("contextPreserved").getAsBoolean(), "actual preview entry policy preserves unchanged channel contexts");
        request.addProperty("offset", 64); objects = read(screen, request);
        check(objects.getAsJsonObject("data").getAsJsonArray("entries").size() == 8 && objects.get("total").getAsInt() == 72 && objects.get("complete").getAsBoolean(), "camera and unchanged actions preserve the pinned old frame while ordinary rendering continues");
        request = detail("rendered_text_value"); request.addProperty("itemIndex", 0); request.addProperty("contextToken", token); request.addProperty("offset", 1); request.addProperty("limit", 1);
        JsonObject text = read(screen, request); check(text.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && text.get("nextOffset").getAsInt() == 2 && text.get("total").getAsInt() == 3, "Unicode paging operates on whole codepoints");
        final JsonObject stale = request; screen.page++; expect("stale_context", () -> read(screen, stale)); screen.page--;
        expect("stale_context", () -> NeiViewAdapter.read(screen, null, stale, "other-session", generous()));
        expect("stale_context", () -> read(new GuiRecipe(handler, Arrays.asList(0)), stale));
        JsonObject fresh = read(screen, args("rendered")); check(fresh.getAsJsonObject("data").get("textDrawsTotal").getAsInt() == 1 && !fresh.get("contextToken").getAsString().equals(token), "offset-zero recapture publishes a fresh frame/token");
        JsonObject goodAction = args("summary"); goodAction.addProperty("contextToken", fresh.get("contextToken").getAsString());
        NeiViewAdapter.validateContext(screen, goodAction, "render-fixture"); check(true, "read context is reusable for exact current preview action");
        screen.page++; expect("stale_context", () -> NeiViewAdapter.validateContext(screen, goodAction, "render-fixture")); screen.page--;
        final JsonObject bad = detail("rendered_text"); bad.addProperty("advanced", true); expect("invalid_args", () -> read(screen, bad));
        final JsonObject badSummary = args("rendered"); badSummary.addProperty("target", "rendered_text"); expect("invalid_args", () -> read(screen, badSummary));
        final JsonObject noIndex = detail("rendered_text_value"); expect("invalid_args", () -> read(screen, noIndex));
        final JsonObject noToken = detail("rendered_text"); noToken.addProperty("offset", 1); expect("invalid_args", () -> read(screen, noToken));
        final JsonObject tooLarge = detail("rendered_text"); tooLarge.addProperty("limit", 65); expect("invalid_args", () -> read(screen, tooLarge));
        final JsonObject tooLargeText = detail("rendered_text_value"); tooLargeText.addProperty("itemIndex", 0); tooLargeText.addProperty("limit", 8193); expect("invalid_args", () -> read(screen, tooLargeText));
        NeiTextCapture.clear(); JsonObject absent = read(screen, args("rendered")); check(absent.get("status").getAsString().equals("not_present"), "missing completed frame remains explicit and causes no redraw");
        for (String action : new String[] {"set_channel", "set_tier", "set_layer", "select_block", "clear_selection", "press_control", "set_camera"}) {
            NeiTextCapture.begin(screen, marker, "policy", true, 640, 360); NeiTextCapture.observe(screen, "policy frame", 0, 0, 0, false); NeiTextCapture.end(screen, marker);
            JsonObject pinned = read(screen, args("rendered")); JsonObject old = args("rendered"); old.addProperty("contextToken", pinned.get("contextToken").getAsString());
            JsonObject effect = receipt(action, "press_control".equals(action) ? "callback_completed" : "applied", "set_camera".equals(action));
            GameBridge.finishNeiPreviewControl(effect);
            check(!effect.get("contextPreserved").getAsBoolean() && effect.get("requiresViewRefresh").getAsBoolean(), action + " model/screen changes invalidate the context through the entry policy");
            expect("stale_context", () -> read(screen, old));
            check(NeiTextCapture.latest(screen, marker) == null, action + " clears the completed frame together with the whole old context");
        }
    }

    private static JsonObject receipt(String action, String status, boolean screenChanged) {
        JsonObject result = new JsonObject(); result.addProperty("action", action); result.addProperty("status", status);
        result.addProperty("currentScreenChanged", screenChanged); return result;
    }

    private static long generous() { return System.nanoTime() + 60000000000L; }
    private static JsonObject args(String section) { JsonObject args = new JsonObject(); args.addProperty("screenId", "render-fixture"); args.addProperty("section", section); return args; }
    private static JsonObject detail(String target) { JsonObject args = args("detail"); args.addProperty("target", target); return args; }
    private static JsonObject read(Object screen, JsonObject args) { return NeiViewAdapter.read(screen, null, args, "render-fixture", generous()); }
    private static void expect(String code, Runnable action) { try { action.run(); throw new AssertionError("Expected " + code); } catch (BridgeException failure) { check(failure.code.equals(code), "expected " + code + ", got " + failure.code); } }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
}
