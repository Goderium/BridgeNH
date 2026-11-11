package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter.Read;

/** Pages over one completed passive draw snapshot. Coordinates deliberately remain local draw arguments. */
final class NeiRenderedAdapter {
    static boolean requested(JsonObject args) {
        String section = args.has("section") ? JsonData.string(args, "section") : "summary";
        if ("rendered".equals(section)) return true;
        String target = "detail".equals(section) ? JsonData.string(args, "target") : "";
        return "rendered_text".equals(target) || "rendered_text_value".equals(target);
    }

    static void read(JsonObject args, Read out) {
        NeiTextCapture.Snapshot snapshot = out.rendered;
        out.data.addProperty("observerActive", NeiTextCapture.observerActive());
        out.data.addProperty("terminalHooksLifetime", NeiTextCapture.terminalHooksLifetime());
        out.data.addProperty("privateLineHooksLifetime", NeiTextCapture.privateLineHooksLifetime());
        out.data.addProperty("source", "Passive FontRenderer.drawString terminal request observer before batched-font cancellation, plus independent renderString wrapped lines; last completed Forge DrawScreenEvent frame");
        out.data.addProperty("coordinateSpace", "font_draw_arguments");
        out.data.addProperty("screenPositionStatus", "unmapped");
        out.data.addProperty("visibilityStatus", "draw_commands; GL transforms, clipping, occlusion, bidi glyph ordering and glyph coverage are not observed");
        if (snapshot == null) {
            out.absent("No completed matching NEI draw frame is available. Read after the client renders naturally; no draw was invoked."); return;
        }
        out.data.addProperty("frameId", snapshot.frameId);
        out.data.addProperty("frameAgeMillis", Math.max(0D, (System.nanoTime() - snapshot.completedAt) / 1000000D));
        out.data.addProperty("scope", snapshot.scope);
        out.data.addProperty("screenClass", snapshot.screen.getClass().getName());
        out.data.addProperty("scaledGuiWidth", snapshot.width); out.data.addProperty("scaledGuiHeight", snapshot.height);
        out.data.addProperty("textDrawsTotal", snapshot.draws.size()); out.data.addProperty("capturedCodepoints", snapshot.codepoints);
        out.data.addProperty("terminalHookCalls", snapshot.terminalHooks); out.data.addProperty("privateLineHookCalls", snapshot.privateLineHooks);
        out.data.addProperty("shadowPassSkips", snapshot.shadowPassSkips); out.data.addProperty("terminalAncestorSkips", snapshot.terminalAncestorSkips);
        out.data.addProperty("emptyTextInputs", snapshot.emptyInputs); out.data.addProperty("recordingAttempts", snapshot.recordingAttempts);
        out.data.addProperty("emptyCapture", snapshot.emptyCapture); out.data.addProperty("captureStatus", snapshot.captureStatus);
        out.data.addProperty("droppedTextDraws", snapshot.droppedDraws); out.data.addProperty("filteredNonNeiDraws", snapshot.filteredDraws);
        out.data.addProperty("unscopedDrawsAfterBudget", snapshot.unscopedAfterBudget); out.data.addProperty("observerErrors", snapshot.observerErrors);
        out.data.addProperty("captureBudgetNanos", NeiTextCapture.OBSERVER_BUDGET_NANOS);
        out.data.addProperty("captureDurationNanos", snapshot.observerNanos);
        out.data.addProperty("boundedFramePartial", snapshot.partial); out.data.addProperty("captureBudgetLimited", snapshot.budgetLimited);
        out.data.addProperty("frameTextTruncated", snapshot.textTruncated);
        if (snapshot.emptyCapture) out.partial("The completed GUI frame retained no text. Per-frame hook and filtering counters describe observation; complete NEI text coverage is unknown.");
        else if (snapshot.partial) out.partial("The completed frame reached a capture limit; pagination covers only retained data.");
        if ("rendered".equals(out.request.section)) { out.single(); return; }
        String target = JsonData.string(args, "target"); out.data.addProperty("target", target);
        if ("rendered_text_value".equals(target)) {
            int index = JsonData.integer(args, "itemIndex", 0, Integer.MAX_VALUE);
            if (index >= snapshot.draws.size()) throw new BridgeException("not_found", "itemIndex exceeds the captured completed frame.");
            NeiTextCapture.TextDraw draw = snapshot.draws.get(index);
            out.data.addProperty("itemIndex", index); out.data.addProperty("unit", "codepoints");
            out.total = draw.codepoints; int start = Math.min(out.request.offset, out.total);
            out.next = start + Math.min(out.request.limit, out.total - start);
            out.data.addProperty("text", draw.text.substring(draw.text.offsetByCodePoints(0, start), draw.text.offsetByCodePoints(0, out.next)));
            out.data.addProperty("originalUtf16Units", draw.originalUtf16Units); out.data.addProperty("textTruncated", draw.truncated);
            return;
        }
        out.data.addProperty("unit", "entries"); out.total = snapshot.draws.size(); out.next = Math.min(out.request.offset, out.total);
        JsonArray entries = new JsonArray(); out.data.add("entries", entries);
        while (out.next < out.total && entries.size() < out.request.limit && out.progress(entries.size())) {
            NeiTextCapture.TextDraw draw = snapshot.draws.get(out.next); JsonObject entry = new JsonObject();
            entry.addProperty("itemIndex", out.next); entry.addProperty("text", NeiTextCapture.prefix(draw.text, 512));
            entry.addProperty("capturedCodepoints", draw.codepoints); entry.addProperty("originalUtf16Units", draw.originalUtf16Units);
            entry.addProperty("textTruncated", draw.truncated); entry.addProperty("previewTruncated", draw.codepoints > 512);
            entry.addProperty("x", draw.x); entry.addProperty("y", draw.y); entry.addProperty("color", draw.color);
            entry.addProperty("dropShadow", draw.shadow); entry.addProperty("fontClass", draw.fontClass); entry.addProperty("caller", draw.caller);
            entry.addProperty("drawSource", draw.drawSource);
            if (!out.add(entries, entry)) break;
            out.next++;
        }
    }
}
