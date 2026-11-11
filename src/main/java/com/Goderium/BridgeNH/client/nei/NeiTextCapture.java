package com.Goderium.BridgeNH.client.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Pure Java sink for terminal font requests and independent wrapped lines. No game classes load during bootstrap. */
public final class NeiTextCapture {
    static final int DRAW_LIMIT = 512, FRAME_CODEPOINT_LIMIT = 65536, DRAW_CODEPOINT_LIMIT = 8192;
    static final long OBSERVER_BUDGET_NANOS = 2000000L;
    private static volatile Frame active;
    private static volatile Snapshot latest;
    private static volatile boolean observerActive;
    private static final AtomicLong terminalHooks = new AtomicLong(), privateLineHooks = new AtomicLong();
    private static long sequence;

    static void begin(Object screen, String marker, String scope, boolean allText, int width, int height) {
        active = new Frame(screen, marker, scope, allText, width, height, Thread.currentThread());
    }

    static void end(Object screen, String marker) {
        Frame frame = active;
        active = null;
        if (frame == null || frame.thread != Thread.currentThread() || frame.screen != screen) return;
        if (!frame.marker.equals(marker)) { latest = null; return; }
        latest = new Snapshot(frame, ++sequence, System.nanoTime());
    }

    static void clear() { active = null; latest = null; }
    static boolean observerActive() { return observerActive; }
    static long terminalHooksLifetime() { return terminalHooks.get(); }
    static long privateLineHooksLifetime() { return privateLineHooks.get(); }
    static Snapshot latest(Object screen, String marker) {
        Snapshot snapshot = latest;
        return snapshot != null && snapshot.screen == screen && snapshot.marker.equals(marker) ? snapshot : null;
    }

    /** Records the terminal request before a batched-font injector can bypass private vanilla drawing. */
    public static void observe(Object font, String text, int x, int y, int color, boolean shadow) {
        observe(font, text, x, y, color, shadow, true, null);
    }

    /** Private drawing is only a fallback for lines not already represented by a terminal ancestor. */
    public static void observeLine(Object font, String text, int x, int y, int color, boolean shadowPass) {
        observe(font, text, x, y, color, shadowPass, false, null);
    }

    static void observeLine(Object font, String text, int x, int y, int color, boolean shadowPass, StackTraceElement[] stack) {
        observe(font, text, x, y, color, shadowPass, false, stack);
    }

    private static void observe(Object font, String text, int x, int y, int color, boolean shadow, boolean terminal, StackTraceElement[] stack) {
        if (!observerActive) observerActive = true;
        if (terminal) terminalHooks.incrementAndGet(); else privateLineHooks.incrementAndGet();
        Frame frame = active;
        if (frame == null || frame.thread != Thread.currentThread()) return;
        if (terminal) frame.terminalHooks++; else frame.privateLineHooks++;
        long started = System.nanoTime();
        try {
            if (text == null || text.isEmpty()) { frame.emptyInputs++; return; }
            if (!terminal && shadow) { frame.shadowPassSkips++; return; }
            if (frame.observerNanos >= OBSERVER_BUDGET_NANOS) { frame.budgetLimited = true; frame.unscopedAfterBudget++; return; }
            if (!terminal) {
                if (stack == null) stack = Thread.currentThread().getStackTrace();
                if (terminalAncestor(stack)) { frame.terminalAncestorSkips++; return; }
            }
            String caller = frame.allText ? "dedicated_nei_screen" : neiCaller(stack == null ? Thread.currentThread().getStackTrace() : stack);
            if (caller.isEmpty()) { frame.filteredDraws++; return; }
            frame.recordingAttempts++;
            if (frame.draws.size() >= DRAW_LIMIT || frame.codepoints >= FRAME_CODEPOINT_LIMIT) { frame.droppedDraws++; return; }
            int remaining = Math.min(DRAW_CODEPOINT_LIMIT, FRAME_CODEPOINT_LIMIT - frame.codepoints);
            String captured = prefix(text, remaining);
            int count = captured.codePointCount(0, captured.length());
            boolean truncated = captured.length() != text.length();
            frame.codepoints += count;
            frame.textTruncated |= truncated;
            frame.draws.add(new TextDraw(captured, text.length(), count, truncated, x, y, color, shadow,
                    font == null ? "" : prefix(font.getClass().getName(), 256), caller,
                    terminal ? "terminal_drawString_request" : "private_renderString_line"));
        } catch (RuntimeException | LinkageError failure) {
            // Observation must never break an existing draw or change its return value.
            frame.observerErrors++;
        } finally { frame.observerNanos += Math.max(0L, System.nanoTime() - started); }
    }

    static boolean terminalAncestor(StackTraceElement[] stack) {
        for (int i = 0; i < Math.min(stack.length, 64); i++) {
            StackTraceElement caller = stack[i];
            if ("net.minecraft.client.gui.FontRenderer".equals(caller.getClassName())
                    && ("drawString".equals(caller.getMethodName()) || "func_85187_a".equals(caller.getMethodName()))) return true;
        }
        return false;
    }

    static String neiCaller(StackTraceElement[] stack) {
        for (int i = 0; i < Math.min(stack.length, 64); i++) {
            String owner = stack[i].getClassName();
            if (owner.startsWith("codechicken.nei.") || owner.startsWith("com.github.bartimaeusnek.blockrenderer6343.")
                    || owner.startsWith("blockrenderer6343.")) return prefix(owner + "." + stack[i].getMethodName(), 256);
        }
        return "";
    }

    static String prefix(String text, int codepoints) {
        int end = 0, count = 0;
        while (end < text.length() && count < codepoints) { end += Character.charCount(text.codePointAt(end)); count++; }
        return end == text.length() ? text : text.substring(0, end);
    }

    private static final class Frame {
        final Object screen; final String marker, scope; final boolean allText; final int width, height; final Thread thread;
        final List<TextDraw> draws = new ArrayList<TextDraw>();
        int codepoints, droppedDraws, filteredDraws, unscopedAfterBudget, observerErrors;
        int terminalHooks, privateLineHooks, shadowPassSkips, terminalAncestorSkips, emptyInputs, recordingAttempts;
        long observerNanos; boolean budgetLimited, textTruncated;
        Frame(Object screen, String marker, String scope, boolean allText, int width, int height, Thread thread) {
            this.screen = screen; this.marker = marker; this.scope = scope; this.allText = allText;
            this.width = width; this.height = height; this.thread = thread;
        }
    }

    static final class Snapshot {
        final Object screen; final String marker, scope; final int width, height, codepoints, droppedDraws, filteredDraws, unscopedAfterBudget, observerErrors;
        final int terminalHooks, privateLineHooks, shadowPassSkips, terminalAncestorSkips, emptyInputs, recordingAttempts;
        final long frameId, completedAt, observerNanos; final boolean partial, budgetLimited, textTruncated, emptyCapture;
        final String captureStatus;
        final List<TextDraw> draws;
        Snapshot(Frame frame, long frameId, long completedAt) {
            screen = frame.screen; marker = frame.marker; scope = frame.scope; width = frame.width; height = frame.height;
            codepoints = frame.codepoints; droppedDraws = frame.droppedDraws; filteredDraws = frame.filteredDraws;
            unscopedAfterBudget = frame.unscopedAfterBudget; observerErrors = frame.observerErrors;
            terminalHooks = frame.terminalHooks; privateLineHooks = frame.privateLineHooks;
            shadowPassSkips = frame.shadowPassSkips; terminalAncestorSkips = frame.terminalAncestorSkips;
            emptyInputs = frame.emptyInputs; recordingAttempts = frame.recordingAttempts;
            this.frameId = frameId; this.completedAt = completedAt; observerNanos = frame.observerNanos;
            budgetLimited = frame.budgetLimited; textTruncated = frame.textTruncated;
            emptyCapture = frame.draws.isEmpty();
            partial = emptyCapture || budgetLimited || textTruncated || droppedDraws > 0 || observerErrors > 0;
            captureStatus = emptyCapture ? terminalHooks + privateLineHooks == 0 ? "no_font_hooks" : "no_text_retained"
                    : partial ? "bounded_partial" : "captured";
            draws = Collections.unmodifiableList(frame.draws);
        }
    }

    static final class TextDraw {
        final String text, fontClass, caller, drawSource; final int originalUtf16Units, codepoints, x, y, color; final boolean truncated, shadow;
        TextDraw(String text, int originalUtf16Units, int codepoints, boolean truncated, int x, int y, int color,
                boolean shadow, String fontClass, String caller, String drawSource) {
            this.text = text; this.originalUtf16Units = originalUtf16Units; this.codepoints = codepoints;
            this.truncated = truncated; this.x = x; this.y = y; this.color = color; this.shadow = shadow;
            this.fontClass = fontClass; this.caller = caller; this.drawSource = drawSource;
        }
    }
}
