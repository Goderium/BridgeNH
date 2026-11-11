package com.Goderium.BridgeNH.client.nei;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Collections;

/** Genuine data/ownership boundaries: no GL context, renderer callback, screenshot, or client control. */
public final class NeiNaturalRenderSelfTest {
    private static int checks;
    public static int run() { checks = 0; vertices(); lifecycle(); backendEntries(); cpuSliceCoverage(); return checks; }
    public static void main(String[] args) { System.out.println("NEI native render boundary fixtures passed: " + run()); }

    private static void vertices() {
        int[] raw = new int[40];
        for (int vertex = 0; vertex < 5; vertex++) {
            int p = vertex * 8; raw[p] = Float.floatToRawIntBits(vertex + 0.25f); raw[p + 1] = Float.floatToRawIntBits(-3.5f);
            raw[p + 2] = Float.floatToRawIntBits(71.0f); raw[p + 3] = Float.floatToRawIntBits(0.2f); raw[p + 4] = Float.floatToRawIntBits(0.7f);
            raw[p + 5] = 0x78563412; raw[p + 6] = 0x00400000; raw[p + 7] = 15728880;
        }
        int[] before = raw.clone(); JsonObject primitive = NeiNaturalRenderCapture.vanillaPrimitive(raw, 8, 4, 7, true, true, true, true);
        JsonArray vertices = primitive.getAsJsonArray("vertices");
        check(vertices.size() == 4 && "quad".equals(primitive.get("primitive").getAsString()), "one precise existing four-vertex slice retains quad topology");
        check(vertices.get(0).getAsJsonObject().get("x").getAsFloat() == 1.25f && vertices.get(3).getAsJsonObject().get("x").getAsFloat() == 4.25f, "offset selects only the requested buffer range");
        check(vertices.get(0).getAsJsonObject().get("y").getAsFloat() == -3.5f && vertices.get(0).getAsJsonObject().get("z").getAsFloat() == 71.0f, "actual translated signed vertex coordinates are retained");
        check(vertices.get(0).getAsJsonObject().get("u").getAsFloat() == 0.2f && vertices.get(0).getAsJsonObject().get("v").getAsFloat() == 0.7f, "actual CTM/custom emitted atlas UV is retained");
        check(vertices.get(0).getAsJsonObject().get("nativePackedColor").getAsInt() == 0x78563412 && vertices.get(0).getAsJsonObject().get("nativePackedLightmap").getAsInt() == 15728880, "native packed color/light are not reinterpreted as guessed GUI tint");
        check(Arrays.equals(raw, before), "observation never mutates or resets the native source array");
        JsonObject noAttributes = NeiNaturalRenderCapture.vanillaPrimitive(raw, 0, 3, 4, false, false, false, false);
        JsonObject vertex = noAttributes.getAsJsonArray("vertices").get(0).getAsJsonObject();
        check("triangle".equals(noAttributes.get("primitive").getAsString()) && !vertex.has("u") && !vertex.has("nativePackedColor") && !vertex.has("nativePackedLightmap") && !vertex.has("nativePackedNormal"), "disabled native attributes never expose old buffer garbage");
        check("vertex_sequence".equals(NeiNaturalRenderCapture.vanillaPrimitive(raw, 0, 4, 5, true, true, true, true).get("primitive").getAsString()), "triangle strips are not incorrectly labeled independent quads");
        raw[0] = Float.floatToRawIntBits(Float.NaN); JsonObject nonFinite = NeiNaturalRenderCapture.vanillaPrimitive(raw, 0, 1, 0, false, false, false, false).getAsJsonArray("vertices").get(0).getAsJsonObject();
        check(nonFinite.get("x").isJsonNull() && nonFinite.get("nonFinite").getAsBoolean(), "non-finite native coordinates remain explicit and JSON-safe");
        boolean denied = false; try { NeiNaturalRenderCapture.vanillaPrimitive(raw, 33, 2, 7, true, true, true, true); } catch (IllegalArgumentException expected) { denied = true; }
        check(denied, "invalid native slice cannot read past its original array");
    }
    private static void backendEntries() {
        JsonObject arrays = NeiNaturalRenderCapture.backendCommand("drawArrays", 4, 13, 29, -1, 0L, 1);
        check(arrays.get("source").getAsString().equals("actual_backend_draw_entry") && arrays.get("drawMode").getAsInt() == 4 && arrays.get("first").getAsInt() == 13 && arrays.get("count").getAsInt() == 29, "backend entry keeps actual converted native mode/first/count without claiming original GLSM mode");
        check(!arrays.get("gpuDrawSuccessObserved").getAsBoolean() && !arrays.get("gpuBufferReadback").getAsBoolean() && arrays.get("observedStage").getAsString().equals("HEAD_before_original_GL_delegate") && !arrays.has("indexType"), "HEAD entry is distinct from GPU success and never reads GPU buffers");
        JsonObject indexed = NeiNaturalRenderCapture.backendCommand("drawElementsInstanced", 4, 0, 42, 5123, 4567890123L, 8);
        check(indexed.get("nativeIndicesArgument").getAsLong() == 4567890123L && indexed.get("indexType").getAsInt() == 5123 && indexed.get("instanceCount").getAsInt() == 8, "native indexed/instanced arguments preserve long pointer-or-offset value without decoding it");
        Object screen = new Object(); NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.world = new Object(); scene.tiles = Collections.emptyMap(); long packed = NeiPreviewProvider.pack(2, 70, -4);
        NeiNaturalRenderCapture.arm(screen, "backend", scene, packed); NeiNaturalRenderCapture.begin(screen, "backend");
        com.Goderium.BridgeNH.mixin.NeiAngelicaGeometryMixin observer = new com.Goderium.BridgeNH.mixin.NeiAngelicaGeometryMixin() { };
        Class<?> callback = org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class;
        String[] names = {"codexbridge$observeArrays", "codexbridge$observeElements", "codexbridge$observeArraysInstanced", "codexbridge$observeElementsInstanced"};
        Class<?>[][] types = {{int.class, int.class, int.class, callback}, {int.class, int.class, int.class, long.class, callback}, {int.class, int.class, int.class, int.class, callback}, {int.class, int.class, int.class, long.class, int.class, callback}};
        Object[][] arguments = {{4, 13, 29, null}, {4, 42, 5123, 4567890123L, null}, {4, 13, 29, 8, null}, {4, 42, 5123, 4567890123L, 8, null}};
        try {
            for (int i = 0; i < names.length; i++) {
                java.lang.reflect.Method hook = com.Goderium.BridgeNH.mixin.NeiAngelicaGeometryMixin.class.getDeclaredMethod(names[i], types[i]); hook.setAccessible(true);
                check(!java.lang.reflect.Modifier.isStatic(hook.getModifiers()), "native backend observer matches instance method " + names[i]); hook.invoke(observer, arguments[i]);
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError("Native backend hook signature mismatch", error); }
        NeiNaturalRenderCapture.end(screen, "backend"); JsonObject data = NeiNaturalRenderCapture.read(screen, "backend", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE).getAsJsonObject("data");
        check(data.getAsJsonObject("hookDiagnostics").getAsJsonObject("observedInRequestedNaturalFrame").get("Lwjgl3GLRenderBackend.draw").getAsBoolean() && !data.getAsJsonObject("hookDiagnostics").getAsJsonObject("observedInRequestedNaturalFrame").has("GLStateManager.draw"), "runtime entry diagnostic names the actual backend rather than excluded GLSM");
        check(!data.get("submissionEntryObserved").getAsBoolean() && data.getAsJsonArray("entries").size() == 0, "backend callbacks outside the requested owner never fabricate selected-owner or GPU evidence");
        NeiNaturalRenderCapture.clear();
    }

    private static void lifecycle() {
        NeiNaturalRenderCapture.clear(); Object screen = new Object(), world = new Object();
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.world = world; scene.tiles = Collections.emptyMap();
        long packed = NeiPreviewProvider.pack(2, 70, -4);
        JsonObject absent = NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE);
        check("not_present".equals(absent.get("status").getAsString()), "a getter never silently arms or renders a capture");
        JsonObject armed = NeiNaturalRenderCapture.arm(screen, "view1", scene, packed);
        String armedContext = NeiNaturalRenderCapture.context(screen);
        check(armed.get("ownerPosition").equals(armed.get("position")), "capture receipt exposes its exact requested owner position");
        check("pending".equals(armed.get("status").getAsString()) && armed.get("maximumNaturalFrames").getAsInt() == 1 && !armed.get("pixelDataStored").getAsBoolean(), "explicit arm observes one natural frame and has no pixel storage");
        JsonObject pending = NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE);
        check("pending".equals(pending.get("status").getAsString()) && pending.getAsJsonObject("data").get("naturalFramesObserved").getAsInt() == 0, "getter leaves pending observation idle until a real frame arrives");
        NeiNaturalRenderCapture.begin(new Object(), "view1"); NeiNaturalRenderCapture.end(new Object(), "view1");
        check("pending".equals(NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE).get("status").getAsString()), "another GUI cannot satisfy a capture request");
        NeiNaturalRenderCapture.begin(screen, "view1"); NeiNaturalRenderCapture.markHook(1); NeiNaturalRenderCapture.end(screen, "view1");
        JsonObject completed = NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE);
        check(armedContext.equals(NeiNaturalRenderCapture.context(screen)), "natural frame completion retains explicit-arm token identity for pagination");
        check(completed.getAsJsonObject("data").getAsJsonObject("hookDiagnostics").getAsJsonObject("observedInRequestedNaturalFrame").get("RenderBlocks.owner").getAsBoolean(), "hook diagnostics describe actual entry observation separately from selected owner acceptance");
        check(completed.getAsJsonObject("data").get("naturalFramesObserved").getAsInt() == 1 && !completed.getAsJsonObject("data").get("ownerObserved").getAsBoolean(), "missing owner is a truthful observed empty frame, not invented geometry");
        check(completed.getAsJsonObject("data").getAsJsonArray("gaps").size() >= 2 && !completed.getAsJsonObject("data").get("graphicsComplete").getAsBoolean(), "unsupported owner/submission and final-pixel limitations are explicit");
        NeiNaturalRenderCapture.begin(screen, "view1"); NeiNaturalRenderCapture.end(screen, "view1");
        check(NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE).getAsJsonObject("data").get("naturalFramesObserved").getAsInt() == 1, "completed observation does not become a continuous listener");
        check("not_present".equals(NeiNaturalRenderCapture.read(screen, "view2", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE).get("status").getAsString()), "recipe/view changes invalidate the old frame provenance");
        check("not_present".equals(NeiNaturalRenderCapture.read(screen, "view1", scene, packed + 1, "preview_render_geometry", 0, 16, Long.MAX_VALUE).get("status").getAsString()), "another position cannot consume the selected owner's snapshot");
        NeiNaturalRenderCapture.arm(screen, "view1", scene, packed); NeiNaturalRenderCapture.begin(screen, "view1"); NeiNaturalRenderCapture.end(screen, "view2");
        check(!armedContext.equals(NeiNaturalRenderCapture.context(screen)), "a new explicit arm gets a new context identity");
        JsonObject changed = NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE);
        check(changed.getAsJsonObject("data").getAsJsonArray("gaps").toString().contains("view_changed_during_natural_frame"), "view changes during drawing invalidate emitted records");
        NeiNaturalRenderCapture.clear();
        check("not_present".equals(NeiNaturalRenderCapture.read(screen, "view1", scene, packed, "preview_render_geometry", 0, 16, Long.MAX_VALUE).get("status").getAsString()), "GUI close clears transient render records");
        NeiNaturalRenderCapture.arm(screen, "bounded", scene, packed); NeiNaturalRenderCapture.begin(screen, "bounded");
        for (int i = 0; i < 300; i++) { JsonObject record = new JsonObject(); record.addProperty("kind", "fixture_natural_record"); record.addProperty("ordinal", i); NeiNaturalRenderCapture.add(record); }
        NeiNaturalRenderCapture.end(screen, "bounded");
        JsonObject bounded = NeiNaturalRenderCapture.read(screen, "bounded", scene, packed, "preview_render_geometry", 0, 64, Long.MAX_VALUE);
        check(bounded.get("total").getAsInt() == NeiNaturalRenderCapture.MAX_ENTRIES && bounded.getAsJsonObject("data").get("captureLimited").getAsBoolean() && !bounded.get("complete").getAsBoolean(), "native recording cap bounds memory and explicitly reports partial data");
        check(bounded.getAsJsonObject("data").getAsJsonArray("entries").size() == 64 && bounded.get("nextOffset").getAsInt() == 64 && bounded.get("hasMore").getAsBoolean(), "frame records page without repeating a render or a source getter");
        JsonObject page = NeiNaturalRenderCapture.read(screen, "bounded", scene, packed, "preview_render_geometry", 64, 64, Long.MAX_VALUE);
        check(page.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("ordinal").getAsInt() == 64 && page.getAsJsonObject("data").get("naturalFramesObserved").getAsInt() == 1, "second page preserves record order and the same one-frame provenance");
        NeiNaturalRenderCapture.clear();
    }
    private static void cpuSliceCoverage() {
        net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.instance;
        java.util.Map<String, Object> original = new java.util.LinkedHashMap<String, Object>();
        Object screen = new Object(), tile = new Object(); long packed = NeiPreviewProvider.pack(2, 70, -4);
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.world = new Object(); scene.tiles = Collections.singletonMap(packed, tile);
        try {
            for (String name : new String[] {"drawMode", "rawBufferIndex", "vertexCount", "rawBuffer"}) {
                java.lang.reflect.Field field = net.minecraft.client.renderer.Tessellator.class.getDeclaredField(name); field.setAccessible(true); original.put(name, field.get(tess));
            }
            tessField(tess, "drawMode", 7); tessField(tess, "rawBufferIndex", 14964 * 8); tessField(tess, "vertexCount", 14964);
            // First fixture frame initializes only the observation caches, without any draw or GPU call.
            for (int pass = 0; pass < 2; pass++) {
                NeiNaturalRenderCapture.arm(screen, "zero-slice", scene, packed); NeiNaturalRenderCapture.begin(screen, "zero-slice");
                NeiNaturalRenderCapture.tileBegin(tile); NeiNaturalRenderCapture.tessellatorDraw(tess); NeiNaturalRenderCapture.tileEnd(tile); NeiNaturalRenderCapture.end(screen, "zero-slice");
            }
            JsonObject zero = NeiNaturalRenderCapture.read(screen, "zero-slice", scene, packed, "preview_render_geometry", 0, 64, Long.MAX_VALUE).getAsJsonObject("data");
            check(zero.get("ownerObserved").getAsBoolean() && zero.get("submissionEntryObserved").getAsBoolean() && zero.get("capturedVertices").getAsInt() == 0, "selected owner and a large native draw entry cannot fabricate an owner CPU slice when its raw index did not grow");
            check(zero.getAsJsonArray("gaps").toString().contains("raw_buffer_index_unchanged") && zero.getAsJsonArray("gaps").toString().contains("selected_owner_cpu_vertices_not_captured"), "zero CPU geometry explicitly reports both raw slice coverage and the whole selected owner gap");
            check(zero.getAsJsonArray("entries").toString().contains("14964") && !zero.getAsJsonArray("entries").toString().contains("native_primitive"), "large unrelated queued native batch remains metadata only and is never copied wholesale");
            tessField(tess, "rawBufferIndex", 0); tessField(tess, "vertexCount", 0);
            int[] raw = new int[48 * 8]; for (int i = 0; i < 48; i++) raw[i * 8] = Float.floatToRawIntBits(i + 0.5f);
            tessField(tess, "rawBuffer", raw); NeiNaturalRenderCapture.arm(screen, "positive-slice", scene, packed); NeiNaturalRenderCapture.begin(screen, "positive-slice");
            NeiNaturalRenderCapture.tileBegin(tile); tessField(tess, "rawBufferIndex", raw.length); tessField(tess, "vertexCount", 48);
            NeiNaturalRenderCapture.tileEnd(tile); NeiNaturalRenderCapture.end(screen, "positive-slice");
            JsonObject positive = NeiNaturalRenderCapture.read(screen, "positive-slice", scene, packed, "preview_render_geometry", 0, 64, Long.MAX_VALUE).getAsJsonObject("data");
            check(positive.get("capturedVertices").getAsInt() == 48 && !positive.getAsJsonArray("gaps").toString().contains("selected_owner_cpu_vertices_not_captured"), "verified 48-vertex native slice remains available and never receives the zero-CPU coverage gap");
            check(positive.getAsJsonArray("entries").toString().contains("actual_tile_emission") && raw[0] == Float.floatToRawIntBits(0.5f), "positive owner slice preserves actual coordinate data and leaves the CPU source untouched");
        } catch (ReflectiveOperationException error) { throw new AssertionError("Native Tessellator fixture fields changed", error); }
        finally { try { for (java.util.Map.Entry<String, Object> value : original.entrySet()) tessField(tess, value.getKey(), value.getValue()); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } NeiNaturalRenderCapture.clear(); }
    }
    private static void tessField(Object tess, String name, Object value) throws ReflectiveOperationException {
        java.lang.reflect.Field field = net.minecraft.client.renderer.Tessellator.class.getDeclaredField(name); field.setAccessible(true); field.set(tess, value);
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
