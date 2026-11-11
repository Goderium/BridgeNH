package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.client.nei.NeiViewAdapter.Read;
import java.util.Arrays;

/** Pages the completed native Tooltip list, including later native additions to the observed list. */
final class NeiHoverAdapter {
    static boolean requested(JsonObject args) { return args.has("section") && "detail".equals(JsonData.string(args, "section")) && Arrays.asList("hover_tooltip_lines", "hover_tooltip_text", "hover_hotkeys").contains(JsonData.string(args, "target")); }
    static void read(JsonObject args, Read out) {
        NeiHoverCapture.Snapshot snapshot = out.hover;
        out.data.addProperty("source", snapshot == null ? "Passive natural NEI Tooltip frame observation; no Tooltip getter or hover mutation invoked." : snapshot.source);
        if (snapshot == null) { out.absent("No matching completed native NEI Tooltip frame has been observed."); return; }
        out.data.addProperty("frameId", snapshot.frameId); out.data.addProperty("frameAgeMillis", Math.max(0D, (System.nanoTime() - snapshot.completedAt) / 1000000D));
        out.data.addProperty("mouseX", snapshot.x); out.data.addProperty("mouseY", snapshot.y); out.data.add("physicalModifiers", snapshot.modifiers.deepCopy());
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : snapshot.pointer.entrySet()) out.data.add(entry.getKey(), entry.getValue().deepCopy());
        out.data.add("nativeTooltipGate", snapshot.nativeGate.deepCopy());
        out.data.add("nativeTooltipRender", snapshot.nativeRender.deepCopy()); out.data.add("callbackDiagnostics", snapshot.callbackDiagnostics.deepCopy());
        out.data.add("chromaticTooltipRender", snapshot.chromaticRender.deepCopy());
        out.data.addProperty("tooltipPhase", snapshot.phase); out.data.addProperty("observerRegistered", snapshot.registration.equals("registered_native_listener"));
        out.data.addProperty("observerStatus", snapshot.registration); out.data.addProperty("callbackCalls", snapshot.callbacks);
        out.data.addProperty("originalTooltipLines", snapshot.originalLines); out.data.addProperty("capturedTooltipLines", snapshot.lines.size());
        out.data.addProperty("capturedCodepoints", snapshot.codepoints); out.data.addProperty("captureLimited", snapshot.limited);
        out.data.addProperty("coordinateSpace", "scaled_gui_mouse_coordinates");
        out.data.addProperty("frameOnly", true);
        out.data.add("item", snapshot.stack == null ? com.google.gson.JsonNull.INSTANCE : NeiDataAdapter.stack(snapshot.stack, null, false, false));
        out.data.addProperty("itemProvenance", snapshot.stackSource);
        JsonArray missing = new JsonArray();
        if (snapshot.callbacks == 0 && !snapshot.nativeRender.get("finalListHandoffObserved").getAsBoolean() && !snapshot.chromaticRender.get("publicEventObserved").getAsBoolean()) missing.add(new com.google.gson.JsonPrimitive("No native Tooltip callback, renderer List handoff or public Chromatic render event observed in this frame; custom direct drawing is available through rendered_text when it uses FontRenderer."));
        if (snapshot.chromaticRender.has("gaps")) for (com.google.gson.JsonElement gap : snapshot.chromaticRender.getAsJsonArray("gaps")) missing.add(gap.deepCopy());
        missing.add(new com.google.gson.JsonPrimitive("Final clipping, arbitrary tooltip icons, custom drawing and visible pixels are not reconstructed; Chromatic event text covers only its actual public page components, and rendered_text describes actual font requests."));
        out.data.add("missingCapabilities", missing);
        if (snapshot.limited) out.partial("The native completed Tooltip snapshot reached a memory or capture budget; only retained lines can be continued.");
        String target = JsonData.string(args, "target"); out.data.addProperty("target", target);
        if ("hover_tooltip_text".equals(target)) {
            int index = JsonData.integer(args, "lineIndex", 0, Integer.MAX_VALUE);
            if (index >= snapshot.lines.size()) throw new BridgeException("not_found", "lineIndex exceeds the retained completed Tooltip.");
            NeiHoverCapture.Line line = snapshot.lines.get(index); out.total = line.codepoints;
            int start = Math.min(out.request.offset, out.total); out.next = start + Math.min(out.request.limit, out.total - start);
            out.data.addProperty("lineIndex", index); out.data.addProperty("unit", "codepoints");
            out.data.addProperty("offsetUnit", "unicode_codepoints");
            out.data.addProperty("text", line.text.substring(line.text.offsetByCodePoints(0, start), line.text.offsetByCodePoints(0, out.next)));
            out.data.addProperty("originalUtf16Units", line.originalUtf16); out.data.addProperty("textTruncated", line.text.length() != line.originalUtf16); return;
        }
        JsonArray entries = new JsonArray(); out.data.add("hover_hotkeys".equals(target) ? "hotkeys" : "entries", entries);
        out.total = "hover_hotkeys".equals(target) ? snapshot.hotkeys.size() : snapshot.lines.size(); out.next = Math.min(out.request.offset, out.total);
        while (out.next < out.total && entries.size() < out.request.limit && out.progress(entries.size())) {
            JsonObject entry = new JsonObject();
            if ("hover_hotkeys".equals(target)) {
                NeiHoverCapture.Hotkey key = snapshot.hotkeys.get(out.next); entry.addProperty("key", key.key); entry.addProperty("description", key.description);
            } else {
                NeiHoverCapture.Line line = snapshot.lines.get(out.next); entry.addProperty("lineIndex", out.next); entry.addProperty("text", NeiTextCapture.prefix(line.text, 512));
                entry.addProperty("capturedCodepoints", line.codepoints); entry.addProperty("originalUtf16Units", line.originalUtf16);
                entry.addProperty("previewTruncated", line.codepoints > 512); entry.addProperty("textTruncated", line.text.length() != line.originalUtf16);
                entry.addProperty("textTarget", "hover_tooltip_text");
            }
            if (!out.add(entries, entry)) break; out.next++;
        }
        if (out.total == 0 && !snapshot.limited) out.absent("No retained native Tooltip or hotkeys are present in this completed frame.");
    }
}
