package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;

import com.google.gson.JsonObject;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;

/** Native Tooltip listener. It returns original collections and observes only natural GUI frames. */
public final class NeiHoverCapture {
    static final int LINE_LIMIT = 1024, CODEPOINT_LIMIT = 65536, LINE_CODEPOINT_LIMIT = 8192, HOTKEY_LIMIT = 256;
    private static final String MANAGER = "codechicken.nei.guihook.GuiContainerManager", HANDLER = "codechicken.nei.guihook.IContainerTooltipHandler";
    private static Frame active;
    private static Snapshot latest;
    private static Object observer;
    private static String registrationStatus = "not_registered";
    private static long sequence;
    private static boolean nativeMethodEverObserved;

    static void begin(Object screen, String marker, int x, int y) {
        TooltipModifiers.bindClientThread();
        active = new Frame(screen, marker, x, y, NeiDataAdapter.modifiers());
        attach(screen);
    }

    private static void attach(Object screen) {
        if (!NeiUiAccess.is(screen, "net.minecraft.client.gui.inventory.GuiContainer")) return;
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            Class<?> api = Class.forName(HANDLER, false, loader);
            if (observer == null) {
                observer = Proxy.newProxyInstance(loader, new Class<?>[] {api}, new Listener());
                NeiUiAccess.publicCall(loader, MANAGER, null, "addTooltipHandler", new Class<?>[] {api}, observer);
            }
            Class<?> container = Class.forName("net.minecraft.client.gui.inventory.GuiContainer", false, loader);
            Object manager = NeiUiAccess.publicCall(loader, MANAGER, null, "getManager", new Class<?>[] {container}, screen);
            if (manager == null) { registrationStatus = "existing_manager_unavailable"; return; }
            Object entries = NeiUiAccess.publicField(loader, MANAGER, manager, "instanceTooltipHandlers");
            if (!(entries instanceof List<?>)) { registrationStatus = "native_listener_collection_unavailable"; return; }
            @SuppressWarnings("unchecked") List<Object> handlers = (List<Object>) entries;
            synchronized (handlers) {
                // Move only our listener to the end, leaving every native handler's order intact.
                if (handlers.isEmpty() || handlers.get(handlers.size() - 1) != observer) { handlers.remove(observer); handlers.add(observer); }
            }
            registrationStatus = "registered_native_listener";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { registrationStatus = "native_listener_unavailable:" + error.getClass().getSimpleName(); }
    }

    static Object observe(String method, Object[] args) {
        Object original = args == null || args.length == 0 ? null : args[args.length - 1];
        Frame frame = active;
        if (frame == null || frame.thread != Thread.currentThread() || args == null || args.length == 0 || args[0] != frame.screen) return original;
        frame.callbacks++;
        CallbackStats stats = frame.callback(method);
        if (stats != null) stats.record(original);
        if ("handleHotkeys".equals(method) && original instanceof Map<?, ?>) frame.hotkeys = (Map<?, ?>) original;
        else if (original instanceof List<?>) {
            frame.lines = (List<?>) original; frame.phase = method;
            if ("handleItemTooltip".equals(method) && args.length >= 5) { frame.stack = args[1] instanceof ItemStack ? (ItemStack) args[1] : null; frame.stackSource = "original_handleItemTooltip_argument"; }
            if ("handleTooltip".equals(method) && args.length >= 4) { frame.x = ((Number) args[1]).intValue(); frame.y = ((Number) args[2]).intValue(); }
            if ("handleItemTooltip".equals(method) && args.length >= 5) { frame.x = ((Number) args[2]).intValue(); frame.y = ((Number) args[3]).intValue(); }
        }
        return original;
    }

    public static void nativeRenderStart(Object screen, int x, int y) {
        nativeMethodEverObserved = true;
        Frame frame = nativeFrame(screen);
        if (frame != null) { frame.nativeMethodEntries++; frame.nativeX = x; frame.nativeY = y; }
    }
    public static boolean nativeGateResult(Object screen, boolean original) {
        Frame frame = nativeFrame(screen);
        if (frame != null) { frame.gateCalls++; frame.gateResult = original; }
        return original;
    }
    public static ItemStack nativeStackResult(Object screen, ItemStack original) {
        Frame frame = nativeFrame(screen);
        if (frame != null) { frame.stackCalls++; frame.stack = original; frame.stackSource = "original_renderToolTips_getStackMouseOver_result"; }
        return original;
    }
    public static void nativeFinalList(Object screen, Object original, String renderer) {
        try {
            Frame frame = nativeFrame(screen);
            if (frame != null && original instanceof List<?>) {
                frame.finalCalls++; frame.finalLines = (List<?>) original; frame.finalSize = frame.finalLines.size(); frame.renderer = renderer;
                frame.phase = "renderToolTips." + renderer;
            }
        } catch (RuntimeException | LinkageError error) { }
    }
    private static Frame nativeFrame(Object screen) { Frame frame = active; return frame != null && frame.thread == Thread.currentThread() && frame.screen == screen ? frame : null; }

    static void chromaticEvent(Object event) {
        Frame frame = active;
        if (frame == null || frame.thread != Thread.currentThread() || event == null || !NeiChromaticTooltipCapture.EVENT.equals(event.getClass().getName())) return;
        frame.chromaticCalls++;
        if (frame.chromaticCalls > 4 || frame.chromaticObserverNanos >= NeiTextCapture.OBSERVER_BUDGET_NANOS) { frame.chromaticFailure = "public_event_frame_budget_exhausted"; return; }
        try {
            frame.chromatic = new NeiChromaticTooltipCapture.Page(event, NeiTextCapture.OBSERVER_BUDGET_NANOS - frame.chromaticObserverNanos);
            frame.chromatic.data.addProperty("retainedEventOrdinal", frame.chromaticCalls);
            frame.chromaticObserverNanos += frame.chromatic.data.get("observerNanos").getAsLong();
            if (frame.chromatic.itemObserved) { frame.stack = frame.chromatic.item; frame.stackSource = "public_RenderTooltipEvent_context_getItem"; }
        } catch (RuntimeException | LinkageError error) { frame.chromaticFailure = "public_event_capture_unavailable:" + error.getClass().getSimpleName(); }
    }

    static void end(Object screen, String marker) {
        Frame frame = active; active = null;
        if (frame == null || frame.thread != Thread.currentThread() || frame.screen != screen) return;
        if (!frame.marker.equals(marker)) { latest = null; return; }
        try { latest = new Snapshot(frame, ++sequence, registrationStatus); }
        catch (RuntimeException | LinkageError error) { latest = null; registrationStatus = "native_capture_failed:" + error.getClass().getSimpleName(); }
    }
    static void clear() { active = null; latest = null; }
    static Snapshot latest(Object screen, String marker) { return latest != null && latest.screen == screen && latest.marker.equals(marker) ? latest : null; }

    private static final class Listener implements InvocationHandler {
        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("toString".equals(method.getName())) return "Codex passive native NEI Tooltip observer";
            }
            try { return observe(method.getName(), args); }
            catch (RuntimeException | LinkageError error) { return args == null || args.length == 0 ? null : args[args.length - 1]; }
        }
    }

    private static final class Frame {
        final Object screen; final String marker; final Thread thread = Thread.currentThread(); final JsonObject modifiers;
        List<?> lines = Collections.emptyList(), finalLines; Map<?, ?> hotkeys = Collections.emptyMap(); ItemStack stack;
        String phase = "none"; int x, y, callbacks;
        String renderer = "none", stackSource = "not_observed"; int nativeMethodEntries, nativeX, nativeY, gateCalls, stackCalls, finalCalls, finalSize = -1; boolean gateResult;
        final CallbackStats tooltip = new CallbackStats(), itemTooltip = new CallbackStats(), hotkey = new CallbackStats();
        NeiChromaticTooltipCapture.Page chromatic; int chromaticCalls; long chromaticObserverNanos; String chromaticFailure = "none";
        Frame(Object screen, String marker, int x, int y, JsonObject modifiers) { this.screen = screen; this.marker = marker; this.x = x; this.y = y; this.modifiers = modifiers; }
        CallbackStats callback(String method) { return "handleTooltip".equals(method) ? tooltip : "handleItemTooltip".equals(method) ? itemTooltip : "handleHotkeys".equals(method) ? hotkey : null; }
    }

    private static final class CallbackStats {
        int calls, firstSize = -1, lastSize = -1; String firstCaller = "not_observed", lastCaller = "not_observed";
        void record(Object original) {
            int size = original instanceof List<?> ? ((List<?>) original).size() : original instanceof Map<?, ?> ? ((Map<?, ?>) original).size() : -1;
            String caller = "native_caller_not_found_in_bounded_trace"; StackTraceElement[] trace = Thread.currentThread().getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 32); i++) if (trace[i].getClassName().startsWith("codechicken.nei.")) { caller = NeiTextCapture.prefix(trace[i].getClassName() + "#" + trace[i].getMethodName(), 128); break; }
            if (calls++ == 0) { firstSize = size; firstCaller = caller; } lastSize = size; lastCaller = caller;
        }
        JsonObject data() { JsonObject out = new JsonObject(); out.addProperty("calls", calls); out.addProperty("firstEnteringSize", firstSize); out.addProperty("lastEnteringSize", lastSize); out.addProperty("firstNativeCaller", firstCaller); out.addProperty("lastNativeCaller", lastCaller); return out; }
    }

    static final class Snapshot {
        final Object screen; final String marker, phase, registration; final long frameId, completedAt = System.nanoTime();
        final JsonObject modifiers, pointer, nativeGate, nativeRender, callbackDiagnostics, chromaticRender; final String source, stackSource; final int x, y, callbacks, originalLines, codepoints; final boolean limited;
        final List<Line> lines; final List<Hotkey> hotkeys; final ItemStack stack;
        Snapshot(Frame frame, long frameId, String registration) {
            screen = frame.screen; marker = frame.marker; phase = frame.chromatic != null ? "ChromaticTooltips.RenderTooltipEvent.after_drawContent" : frame.finalLines == null ? frame.phase : "renderToolTips." + frame.renderer; this.registration = registration; this.frameId = frameId;
            x = frame.x; y = frame.y; callbacks = frame.callbacks; modifiers = frame.modifiers.deepCopy(); pointer = LogicalGuiPointer.frameData(); nativeGate = gate(frame);
            List<?> original = frame.finalLines == null ? frame.lines : frame.finalLines; originalLines = frame.chromatic == null ? original.size() : frame.chromatic.originalLines; stackSource = frame.stackSource;
            source = frame.chromatic != null ? "Actual Chromatic RenderTooltipEvent page after drawContent returned, copied through public component getters during this natural GUI frame; callback Lists are separate diagnostics." : frame.finalLines == null ? "Original passive IContainerTooltipHandler callback List retained until the completed natural frame; no final renderer handoff observed." : "Original renderToolTips List argument handed to " + frame.renderer + " retained until the completed natural frame; the native List and renderer are unchanged.";
            callbackDiagnostics = new JsonObject(); callbackDiagnostics.add("handleTooltip", frame.tooltip.data()); callbackDiagnostics.add("handleItemTooltip", frame.itemTooltip.data()); callbackDiagnostics.add("handleHotkeys", frame.hotkey.data());
            callbackDiagnostics.addProperty("callerProvenance", "First codechicken.nei caller within the first 32 actual callback stack frames, at most 128 codepoints; no full trace retained.");
            callbackDiagnostics.addProperty("retainedCallbackListSizeAtFrameEnd", frame.lines.size());
            chromaticRender = frame.chromatic == null ? new JsonObject() : frame.chromatic.data.deepCopy();
            chromaticRender.addProperty("publicEventObserved", frame.chromaticCalls > 0); chromaticRender.addProperty("publicEventCalls", frame.chromaticCalls);
            chromaticRender.addProperty("captureFailure", frame.chromaticFailure); chromaticRender.addProperty("cumulativeObserverNanos", frame.chromaticObserverNanos);
            chromaticRender.addProperty("maximumEventsPerFrame", 4); chromaticRender.addProperty("lastObservedEventRetained", frame.chromatic != null && frame.chromatic.data.get("retainedEventOrdinal").getAsInt() == frame.chromaticCalls);
            nativeRender = new JsonObject(); nativeRender.addProperty("hookEverObserved", nativeMethodEverObserved); nativeRender.addProperty("methodEntries", frame.nativeMethodEntries); nativeRender.addProperty("renderMethodObserved", frame.nativeMethodEntries > 0);
            nativeRender.addProperty("mouseX", frame.nativeX); nativeRender.addProperty("mouseY", frame.nativeY); nativeRender.addProperty("stackResultCalls", frame.stackCalls); nativeRender.addProperty("finalListHandoffObserved", frame.finalLines != null);
            nativeRender.addProperty("finalListCalls", frame.finalCalls); nativeRender.addProperty("finalListEnteringSize", frame.finalSize); nativeRender.addProperty("renderer", frame.renderer); nativeRender.addProperty("rendererCompleted", false);
            nativeRender.addProperty("provenance", "Actual optional renderToolTips method entry, original getStackMouseOver result and unchanged drawPagedTooltip/Consumer.accept argument; a handoff does not prove visible pixels or native renderer success.");
            List<Line> captured = new ArrayList<Line>(); int count = 0; boolean partial = !"none".equals(frame.chromaticFailure);
            long started = System.nanoTime();
            if (frame.chromatic != null) { captured.addAll(frame.chromatic.lines); count = frame.chromatic.codepoints; partial |= frame.chromatic.limited; }
            else for (int i = 0; i < Math.min(original.size(), LINE_LIMIT); i++) {
                if (count >= CODEPOINT_LIMIT || i > 0 && System.nanoTime() - started >= NeiTextCapture.OBSERVER_BUDGET_NANOS) { partial = true; break; }
                Object value = original.get(i); partial |= !(value instanceof String);
                String line = value instanceof String ? (String) value : "unsupported_non_string_tooltip_line";
                String text = NeiTextCapture.prefix(line, Math.min(LINE_CODEPOINT_LIMIT, CODEPOINT_LIMIT - count));
                int cp = text.codePointCount(0, text.length()); count += cp;
                captured.add(new Line(text, line.length(), cp)); partial |= text.length() != line.length();
            }
            partial |= captured.size() != originalLines;
            List<Hotkey> keys = new ArrayList<Hotkey>();
            for (Map.Entry<?, ?> entry : frame.hotkeys.entrySet()) {
                if (keys.size() >= HOTKEY_LIMIT || count >= CODEPOINT_LIMIT || System.nanoTime() - started >= NeiTextCapture.OBSERVER_BUDGET_NANOS) { partial = true; break; }
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) { partial = true; continue; }
                String key = NeiTextCapture.prefix((String) entry.getKey(), Math.min(1024, CODEPOINT_LIMIT - count)); count += key.codePointCount(0, key.length());
                String value = NeiTextCapture.prefix((String) entry.getValue(), Math.min(LINE_CODEPOINT_LIMIT, CODEPOINT_LIMIT - count)); count += value.codePointCount(0, value.length());
                keys.add(new Hotkey(key, value)); partial |= key.length() != ((String) entry.getKey()).length() || value.length() != ((String) entry.getValue()).length();
            }
            lines = Collections.unmodifiableList(captured); hotkeys = Collections.unmodifiableList(keys); codepoints = count; limited = partial;
            stack = frame.stack == null ? null : frame.stack.copy();
        }
    }
    private static JsonObject gate(Frame frame) {
        JsonObject out = new JsonObject();
        out.addProperty("provenance", "Unchanged return value of the original shouldShowTooltip call inside actual renderToolTips(II)V; no predicate sampler or second getter call.");
        out.addProperty("forced", false); out.addProperty("decisionObserved", frame.gateCalls > 0); out.addProperty("decisionCalls", frame.gateCalls); out.addProperty("sampledPredicateCalled", false);
        out.add("sampledShouldShowTooltip", com.google.gson.JsonNull.INSTANCE);
        out.addProperty("status", frame.gateCalls > 0 ? "actual_native_decision" : "not_observed");
        if (frame.gateCalls > 0) out.addProperty("shouldShowTooltip", frame.gateResult); else out.add("shouldShowTooltip", com.google.gson.JsonNull.INSTANCE);
        return out;
    }
    static final class Line {
        final String text; final int originalUtf16, codepoints;
        Line(String text, int originalUtf16, int codepoints) { this.text = text; this.originalUtf16 = originalUtf16; this.codepoints = codepoints; }
    }
    static final class Hotkey {
        final String key, description;
        Hotkey(String key, String description) { this.key = key; this.description = description; }
    }
}
