package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

/** Requested single-position, finite natural-frame observation. Never changes a tessellator or submits a draw. */
public final class NeiNaturalRenderCapture {
    static final int MAX_ENTRIES = 256;
    static final int MAX_VERTICES = 2048;
    private static final int MAX_STORED_BYTES = 512 * 1024;
    private static final long MAX_OBSERVER_NANOS = 4000000L;
    private static final long FRESH_NANOS = 500000000L;
    private static final long REQUEST_NANOS = 3000000000L;
    private static final String TESS_MANAGER = "com.gtnewhorizon.gtnhlib.client.renderer.TessellatorManager";
    private static long nextId;
    private static Request request;
    private static Owner owner;
    private static boolean inFrame;
    private static Thread frameThread;
    private static Boolean templateLayoutVerified;
    private static int observedHooks;

    private static final class Request {
        final long id, position, created;
        final String marker, resources;
        final WeakReference<Object> screen, world, tile;
        final List<JsonElement> entries = new ArrayList<>();
        final Set<String> gaps = new LinkedHashSet<>();
        long capturedAt, observerNanos;
        int frames, vertices, storedBytes, observedHooks;
        boolean done, limited, ownerObserved, submissionObserved;
        Object pendingTess;
        Request(Object screen, String marker, Object world, Object tile, long position) {
            id = ++nextId; created = System.nanoTime(); this.position = position; this.marker = marker;
            resources = NeiPreviewResources.context(); this.screen = new WeakReference<>(screen);
            this.world = new WeakReference<>(world); this.tile = new WeakReference<>(tile);
        }
    }

    private static final class Owner {
        final Object source, tess;
        final String kind;
        int start, depth = 1;
        Owner(Object source, Object tess, String kind, int start) { this.source = source; this.tess = tess; this.kind = kind; this.start = start; }
    }

    static JsonObject read(Object screen, String marker, NeiPreviewProvider.Scene scene, long position, String target, int offset, int limit, long started) {
        long now = System.nanoTime();
        boolean same = request != null && request.screen.get() == screen && request.world.get() == scene.world
                && request.position == position && request.marker.equals(marker) && request.resources.equals(NeiPreviewResources.context());
        if (!same) {
            JsonObject absent = NeiPreviewDetailProvider.base(target); absent.addProperty("status", "not_present");
            absent.getAsJsonObject("data").addProperty("reason", "No matching explicit preview_render_capture snapshot. The getter never arms, renders, or refreshes a capture.");
            absent.getAsJsonObject("data").add("position", NeiPreviewProvider.coordinates(position, 0, 64, 0).get("position"));
            absent.getAsJsonObject("data").add("ownerPosition", absent.getAsJsonObject("data").get("position"));
            absent.getAsJsonObject("data").addProperty("captureOperation", "preview_render_capture"); return absent;
        }
        boolean expired = !request.done && now - request.created > REQUEST_NANOS;
        JsonObject result = NeiPreviewDetailProvider.base(target); JsonObject data = result.getAsJsonObject("data");
        data.addProperty("captureId", request.id); data.addProperty("captureStatus", request.done ? "observed" : expired ? "deadline_expired" : "pending_natural_frame");
        data.add("position", NeiPreviewProvider.coordinates(position, 0, 64, 0).get("position"));
        data.add("ownerPosition", data.get("position"));
        data.addProperty("resourceContext", request.resources); data.addProperty("renderMarker", request.marker);
        data.addProperty("naturalFramesObserved", request.frames); data.addProperty("ownerObserved", request.ownerObserved);
        data.addProperty("submissionEntryObserved", request.submissionObserved); data.addProperty("observerNanos", request.observerNanos);
        data.addProperty("maximumObserverNanos", MAX_OBSERVER_NANOS); data.addProperty("observerBudgetType", "cumulative soft budget; each native read is checked between records and is not preempted");
        data.addProperty("maximumVertices", MAX_VERTICES); data.addProperty("maximumEntries", MAX_ENTRIES);
        data.addProperty("maximumStoredBytes", MAX_STORED_BYTES); data.addProperty("storedBytes", request.storedBytes);
        data.addProperty("capturedVertices", request.vertices); data.addProperty("captureLimited", request.limited);
        data.addProperty("observedAgeMs", request.done ? Math.max(0L, (now - request.capturedAt) / 1000000L) : 0L);
        data.addProperty("refreshAfterMs", FRESH_NANOS / 1000000L); data.addProperty("pixelDataStored", false);
        data.addProperty("coordinateFrame", "Actual emitted preview/TESR vertex coordinates before captured native model-view/projection transforms. Not player-world or final screen pixels.");
        data.addProperty("graphicsComplete", false);
        data.addProperty("graphicsCoverage", "Single requested fake-world block owner, actual native tessellator slices, compact CTM return values, TESR CPU templates and routed draw/state records. No extra rendering. GPU-only buffer content, arbitrary shader evaluation, later batching and exact occlusion/final pixels are explicit gaps.");
        data.add("runtime", NeiRenderState.runtime()); JsonArray gaps = new JsonArray();
        data.add("hookDiagnostics", hookDiagnostics(request.observedHooks));
        for (String gap : request.gaps) gaps.add(new com.google.gson.JsonPrimitive(gap)); data.add("gaps", gaps);
        if (!request.done) {
            result.addProperty("status", expired ? "not_present" : "pending"); data.add("entries", new JsonArray());
            data.addProperty("nextStep", "Read the same target/context after the next existing GUI draw; polling never forces rendering."); return result;
        }
        NeiPreviewDetailProvider.page(result, request.entries.size(), offset, limit, started, index -> request.entries.get(index));
        if (request.limited) result.addProperty("complete", false);
        return result;
    }

    static JsonObject arm(Object screen, String marker, NeiPreviewProvider.Scene scene, long position) {
        NeiRenderState.initialize();
        request = new Request(screen, marker, scene.world, scene.tiles == null ? null : scene.tiles.get(position), position);
        owner = null; inFrame = false; frameThread = null;
        JsonObject result = new JsonObject(); result.addProperty("status", "pending"); result.addProperty("captureId", request.id);
        result.addProperty("operation", "preview_render_capture"); result.addProperty("maximumNaturalFrames", 1);
        result.addProperty("deadlineMs", REQUEST_NANOS / 1000000L); result.addProperty("pixelDataStored", false);
        result.addProperty("scope", "One explicitly requested existing fake-world position, during the next already-running GUI frame only. No renderer callback, extra draw, or GUI state change.");
        result.add("position", NeiPreviewProvider.coordinates(position, 0, 64, 0).get("position"));
        result.add("ownerPosition", result.get("position")); return result;
    }

    public static void begin(Object screen, String marker) {
        if (request == null || request.done || request.screen.get() != screen || !request.marker.equals(marker)) return;
        if (System.nanoTime() - request.created > REQUEST_NANOS) { gap("request_expired_before_natural_frame"); return; }
        if (inFrame) return;
        inFrame = true; owner = null; frameThread = Thread.currentThread(); request.frames++;
    }

    public static void end(Object screen, String marker) {
        if (!inFrame || request == null || request.screen.get() != screen || frameThread != Thread.currentThread()) return;
        inFrame = false; owner = null; frameThread = null; request.pendingTess = null;
        if (!request.marker.equals(marker) || !request.resources.equals(NeiPreviewResources.context())) {
            request.entries.clear(); request.vertices = 0; request.storedBytes = 0; request.submissionObserved = false;
            gap("view_changed_during_natural_frame"); request.ownerObserved = false;
        }
        if (!request.ownerObserved) gap("requested_position_did_not_enter_a_supported_render_owner");
        if (!request.submissionObserved) gap("no_supported_draw_submission_entry_observed");
        if (request.ownerObserved && request.vertices == 0) gap("selected_owner_cpu_vertices_not_captured; draw_entry_metadata_does_not_supply_selected_geometry");
        request.done = true; request.capturedAt = System.nanoTime();
    }

    public static void clear() { request = null; owner = null; inFrame = false; frameThread = null; }

    /** Monotonic explicit-arm identity only; natural completion keeps tokens stable for paging. */
    static String context(Object screen) {
        return request != null && request.screen.get() == screen ? "render_capture:" + request.id : "render_capture:none";
    }

    /** One cheap bit check per actual hook invocation; no counters, reflection, or allocations when idle. */
    public static void markHook(int bit) {
        if ((observedHooks & bit) == 0) observedHooks |= bit;
        if (inFrame && frameThread == Thread.currentThread() && request != null && !request.done && (request.observedHooks & bit) == 0) request.observedHooks |= bit;
    }

    private static JsonObject hookDiagnostics(int frameMask) {
        JsonObject data = new JsonObject(), ever = new JsonObject(), frame = new JsonObject();
        String[] names = {"RenderBlocks.owner", "Tessellator.draw", "TileEntityRendererDispatcher.owner", "CTMUtils.getBlockIcon", "TesrBatchRenderer.queue", "Lwjgl3GLRenderBackend.draw"};
        for (int i = 0; i < names.length; i++) { ever.addProperty(names[i], (observedHooks & 1 << i) != 0); frame.addProperty(names[i], (frameMask & 1 << i) != 0); }
        data.add("everObservedInNaturalExecution", ever); data.add("observedInRequestedNaturalFrame", frame);
        data.addProperty("meaning", "Actual hook-entry observations, not class availability or registration claims. A false flag means unobserved and may mean an unused render path or unavailable/late hook; ownerObserved and entry source separately prove the selected position's path.");
        return data;
    }

    static boolean accepting() { return inFrame && frameThread == Thread.currentThread() && request != null && !request.done && owner != null && !request.limited; }
    private static boolean position(int x, int y, int z) { return request != null && request.position == NeiPreviewProvider.pack(x, y, z); }
    private static boolean budget() {
        if (request == null || request.limited) return false;
        if (request.entries.size() >= MAX_ENTRIES || request.vertices >= MAX_VERTICES || request.observerNanos >= MAX_OBSERVER_NANOS) {
            request.limited = true; gap("requested_observer_budget_exhausted"); return false;
        }
        return true;
    }
    private static boolean budget(long currentHookStarted) {
        if (!budget()) return false;
        if (request.observerNanos + Math.max(0L, System.nanoTime() - currentHookStarted) >= MAX_OBSERVER_NANOS) {
            request.limited = true; gap("requested_observer_budget_exhausted"); return false;
        }
        return true;
    }
    static void charge(long started) { if (request != null) request.observerNanos += Math.max(0L, System.nanoTime() - started); }

    public static void blockBegin(Object renderer, Block block, int x, int y, int z) {
        if (!inFrame || frameThread != Thread.currentThread() || request == null || request.done || !position(x, y, z) || !budget()) return;
        if (owner != null) { if (owner.source == renderer && "block".equals(owner.kind)) owner.depth++; return; }
        long started = System.nanoTime();
        try {
            Object world = NeiRenderReflection.field(net.minecraft.client.renderer.RenderBlocks.class, renderer, "blockAccess", "field_147845_a");
            if (world != request.world.get()) return;
            Object tess = tessellator(); owner = new Owner(renderer, tess, "block", rawIndex(tess)); request.ownerObserved = true;
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_owner_begin"); entry.addProperty("ownerKind", "block");
            entry.addProperty("rendererClass", renderer.getClass().getName()); entry.addProperty("blockClass", block.getClass().getName());
            entry.addProperty("renderType", block.getRenderType()); entry.add("state", NeiRenderState.snapshot()); add(entry);
        } catch (RuntimeException | LinkageError error) { owner = null; gap("block_owner_or_tessellator_api_unavailable"); }
        finally { charge(started); }
    }

    public static void blockEnd(Object renderer, int x, int y, int z) {
        if (frameThread != Thread.currentThread() || owner == null || owner.source != renderer || !"block".equals(owner.kind) || !position(x, y, z)) return;
        if (--owner.depth > 0) return;
        long started = System.nanoTime();
        try {
            Object current = tessellator();
            if (current != owner.tess) gap("native_tessellator_changed_during_selected_owner; new_buffer_has_no_verified_selected_slice_start");
            copyVanilla(owner.tess, owner.start, "actual_block_emission", started); request.pendingTess = owner.tess;
        }
        catch (RuntimeException | LinkageError error) { gap("native_block_vertex_slice_unavailable"); }
        finally { owner = null; charge(started); }
    }

    public static void tileBegin(Object tile) {
        if (!inFrame || frameThread != Thread.currentThread() || request == null || request.done || request.tile.get() != tile || !budget()) return;
        if (owner != null) { if (owner.source == tile && "tile".equals(owner.kind)) owner.depth++; return; }
        long started = System.nanoTime();
        try {
            Object tess = tessellator(); owner = new Owner(tile, tess, "tile", rawIndex(tess)); request.ownerObserved = true;
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_owner_begin"); entry.addProperty("ownerKind", "tile");
            entry.addProperty("tileClass", tile.getClass().getName()); entry.add("state", NeiRenderState.snapshot()); add(entry);
        } catch (RuntimeException | LinkageError error) { owner = null; gap("tile_owner_api_unavailable"); }
        finally { charge(started); }
    }

    public static void tileEnd(Object tile) {
        if (frameThread != Thread.currentThread() || owner == null || owner.source != tile || !"tile".equals(owner.kind)) return;
        if (--owner.depth > 0) return;
        long started = System.nanoTime();
        try { copyVanilla(owner.tess, owner.start, "actual_tile_emission", started); }
        catch (RuntimeException | LinkageError error) { gap("tile_vertex_path_not_vanilla_tessellator"); }
        finally { owner = null; charge(started); }
    }

    public static void tessellatorDraw(Object tessellator) {
        if (!inFrame || frameThread != Thread.currentThread() || request == null || request.done || request.limited) return;
        boolean selected = owner != null && owner.tess == tessellator, queued = request.pendingTess == tessellator;
        if (!selected && !queued) return;
        long started = System.nanoTime();
        try {
            if (selected) { copyVanilla(tessellator, owner.start, "actual_natural_draw_entry", started); owner.start = 0; }
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_submission_entry"); entry.addProperty("source", "Tessellator.draw HEAD before normal renderer/optimization");
            entry.addProperty("selectedOwnerActive", selected); entry.addProperty("queuedSelectedSlice", queued);
            entry.addProperty("drawMode", NeiRenderReflection.integer(Tessellator.class, tessellator, "drawMode", "field_78409_u"));
            entry.addProperty("nativeBatchVertexCount", NeiRenderReflection.integer(Tessellator.class, tessellator, "vertexCount", "field_78406_i"));
            entry.add("state", NeiRenderState.snapshot()); add(entry); request.submissionObserved = true; request.pendingTess = null;
        } catch (RuntimeException | LinkageError error) { gap("native_draw_entry_api_unavailable"); }
        finally { charge(started); }
    }

    private static Object tessellator() {
        Class<?> api = NeiRenderReflection.optional(TESS_MANAGER);
        return api == null ? Tessellator.instance : NeiRenderReflection.call(api, null, "get");
    }
    private static int rawIndex(Object tess) {
        if (!(tess instanceof Tessellator) || tess.getClass().getName().contains("DirectTessellator") || tess.getClass().getName().contains("CallbackTessellator")) return 0;
        return NeiRenderReflection.integer(Tessellator.class, tess, "rawBufferIndex", "field_147569_p");
    }

    private static void copyVanilla(Object tess, int from, String source, long started) {
        if (!budget(started)) return;
        if (!(tess instanceof Tessellator) || tess.getClass().getName().contains("DirectTessellator") || tess.getClass().getName().contains("CallbackTessellator")) {
            gap("non_vanilla_direct_tessellator_geometry_requires_native_template_provider"); return;
        }
        int end = rawIndex(tess), count = NeiRenderReflection.integer(Tessellator.class, tess, "vertexCount", "field_78406_i");
        if (end == from) {
            gap("selected_owner_raw_buffer_index_unchanged; no_verified_cpu_vertex_slice; queued_batch_not_attributed_to_owner");
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_cpu_slice_unavailable"); entry.addProperty("source", source);
            entry.addProperty("tessellatorClass", tess.getClass().getName()); entry.addProperty("fromRawIndex", from); entry.addProperty("toRawIndex", end);
            entry.addProperty("nativeBatchVertexCount", count); entry.addProperty("status", "selected_slice_has_no_raw_index_growth"); add(entry); return;
        }
        int[] raw = (int[]) NeiRenderReflection.field(Tessellator.class, tess, "rawBuffer", "field_78405_h");
        if (raw == null || from < 0 || end < from || from % 8 != 0 || end % 8 != 0 || end > raw.length || end != count * 8) { gap("native_vertex_layout_or_buffer_reset_unverified"); return; }
        int mode = NeiRenderReflection.integer(Tessellator.class, tess, "drawMode", "field_78409_u");
        boolean texture = NeiRenderReflection.bool(Tessellator.class, tess, "hasTexture", "field_78400_o"), color = NeiRenderReflection.bool(Tessellator.class, tess, "hasColor", "field_78399_n");
        boolean light = NeiRenderReflection.bool(Tessellator.class, tess, "hasBrightness", "field_78414_p"), normal = NeiRenderReflection.bool(Tessellator.class, tess, "hasNormals", "field_78413_q");
        int width = mode == 7 ? 4 : mode == 4 ? 3 : mode == 1 ? 2 : 1;
        for (int cursor = from; cursor < end && budget(started); cursor += width * 8) {
            int vertices = Math.min(width, (end - cursor) / 8);
            if (request.vertices + vertices > MAX_VERTICES) { request.limited = true; gap("vertex_cap"); break; }
            JsonObject entry = vanillaPrimitive(raw, cursor, vertices, mode, texture, color, light, normal);
            entry.addProperty("source", source); entry.addProperty("nativeBufferVertexOffset", cursor / 8); add(entry); request.vertices += vertices;
        }
    }

    static JsonObject vanillaPrimitive(int[] raw, int from, int count, int mode, boolean texture, boolean color, boolean light, boolean normal) {
        if (raw == null || from < 0 || count < 0 || count > 4 || from + count * 8 > raw.length) throw new IllegalArgumentException("Invalid native vertex slice");
        JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_primitive"); entry.addProperty("drawMode", mode);
        entry.addProperty("primitive", mode == 7 && count == 4 ? "quad" : mode == 4 && count == 3 ? "triangle" : mode == 1 && count == 2 ? "line" : "vertex_sequence");
        JsonArray vertices = new JsonArray();
        for (int index = 0; index < count; index++) {
            int p = from + index * 8; JsonObject vertex = new JsonObject();
            for (int axis = 0; axis < 3; axis++) finite(vertex, axis == 0 ? "x" : axis == 1 ? "y" : "z", Float.intBitsToFloat(raw[p + axis]));
            if (texture) { finite(vertex, "u", Float.intBitsToFloat(raw[p + 3])); finite(vertex, "v", Float.intBitsToFloat(raw[p + 4])); }
            if (color) vertex.addProperty("nativePackedColor", raw[p + 5]);
            if (normal) vertex.addProperty("nativePackedNormal", raw[p + 6]);
            if (light) vertex.addProperty("nativePackedLightmap", raw[p + 7]); vertices.add(vertex);
        }
        entry.add("vertices", vertices); entry.addProperty("layoutProvenance", "Observed vanilla Tessellator 8-int layout, verified rawBufferIndex=vertexCount*8. Coordinates already contain its native translation.");
        return entry;
    }

    private static void finite(JsonObject object, String key, float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) { object.add(key, com.google.gson.JsonNull.INSTANCE); object.addProperty("nonFinite", true); }
        else object.addProperty(key, value);
    }

    public static void ctmResolved(IIcon original, Block block, IBlockAccess world, int x, int y, int z, int side, IIcon resolved) {
        if (!accepting() || !position(x, y, z) || world != request.world.get() || !budget()) return;
        long started = System.nanoTime();
        try {
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_ctm_resolution"); entry.addProperty("side", side);
            entry.add("originalIcon", NeiTextureProvider.icon(original, "blocks")); entry.add("resolvedIcon", NeiTextureProvider.icon(resolved, "blocks"));
            entry.addProperty("source", "Actual CTMUtils.getBlockIcon(existing fake world,x,y,z,side) RETURN during native drawing; not another query or replay.");
            Class<?> api = NeiRenderReflection.optional("com.prupe.mcpatcher.ctm.CTMUtils"); Object compact = NeiRenderReflection.call(api, null, "getCurrentCompact");
            entry.addProperty("compactContextPresent", compact != null);
            if (compact != null) {
                Class<?> context = NeiRenderReflection.optional("com.prupe.mcpatcher.ctm.CTMUtils$CTMCompactContext");
                Object override = NeiRenderReflection.call(context, compact, "compact");
                entry.add("compactRule", NeiNativeRenderProvider.ctmRule(override));
                entry.addProperty("compactCoverage", "A compact CTM face may split into several sprites; the returned icon alone is not the final face. Read native_primitive UV/coordinates for the emitted split faces.");
            }
            add(entry);
        } catch (RuntimeException | LinkageError error) { gap("native_ctm_return_context_unavailable"); }
        finally { charge(started); }
    }

    public static void tesrTemplate(Object template, Object texture, Object material) {
        if (!accepting() || !budget()) return;
        long started = System.nanoTime();
        try {
            Class<?> api = NeiRenderReflection.optional("com.gtnewhorizons.angelica.rendering.tesr.TemplateBuffer");
            int[] raw = (int[]) api.getField("data").get(template); int vertices = ((Number) api.getField("vertexCount").get(template)).intValue();
            int mode = ((Number) api.getField("drawMode").get(template)).intValue();
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_tesr_template"); entry.addProperty("texture", JsonData.bounded(String.valueOf(texture), 1024));
            entry.addProperty("vertexCount", vertices); entry.addProperty("drawMode", mode); entry.addProperty("nativeIntCount", raw == null ? 0 : raw.length);
            entry.addProperty("templateClass", template.getClass().getName()); entry.addProperty("materialClass", material == null ? null : material.getClass().getName());
            entry.add("state", NeiRenderState.snapshot());
            entry.addProperty("source", "Actual native TesrBatchRenderer.queue CPU template, including cache replay; no builder or draw is invoked by the bridge.");
            boolean layout = templateLayout() && raw != null && vertices >= 0 && (long) vertices * 8 == raw.length;
            entry.addProperty("geometryStatus", layout ? "native_template_vertices" : "native_template_summary_layout_unverified");
            entry.addProperty("geometryGap", "Template vertices precede queued material/instance transforms and later GPU batching; not final pixels.");
            if (material != null) {
                Class<?> materialApi = NeiRenderReflection.optional("com.gtnewhorizons.angelica.api.tesr.TesrMaterial"); JsonObject values = new JsonObject();
                for (String method : new String[] {"isNoCull", "isUnlit", "isNoDepthWrite", "isDepthOnly", "isStream", "isDepthEqual", "isUnfilteredAtlas"})
                    values.addProperty(method, (Boolean) NeiRenderReflection.call(materialApi, material, method));
                values.addProperty("transparency", String.valueOf(NeiRenderReflection.call(materialApi, material, "transparency")));
                Object shader = NeiRenderReflection.call(materialApi, material, "shader"); values.addProperty("shaderClass", shader == null ? null : shader.getClass().getName());
                values.addProperty("shaderSourceStatus", shader == null ? "none_published_by_material" : "native_material_shader_object_source_not_extracted"); entry.add("material", values);
            }
            add(entry); request.submissionObserved = true;
            if (layout) {
                int width = mode == 7 ? 4 : mode == 4 ? 3 : mode == 1 ? 2 : 1;
                for (int cursor = 0; cursor < vertices && budget(started); cursor += width) {
                    int count = Math.min(width, vertices - cursor);
                    if (request.vertices + count > MAX_VERTICES) { request.limited = true; gap("vertex_cap"); break; }
                    JsonObject primitive = vanillaPrimitive(raw, cursor * 8, count, mode, true, true, true, true);
                    primitive.addProperty("kind", "native_tesr_primitive"); primitive.addProperty("source", "Existing Angelica TemplateBuffer.data passed to actual TesrBatchRenderer.queue");
                    primitive.addProperty("texture", JsonData.bounded(String.valueOf(texture), 1024));
                    primitive.addProperty("layoutProvenance", "Installed Angelica 2.2.19 TemplateCapture -> VertexTransform.decode native ModelQuadUtil layout; public constant offsets and data.length=vertexCount*8 are checked at runtime. Source array is never changed.");
                    add(primitive); request.vertices += count;
                }
            } else gap("native_tesr_template_layout_unverified");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { gap("tesr_template_api_unavailable"); }
        finally { charge(started); }
    }

    private static boolean templateLayout() {
        if (templateLayoutVerified != null) return templateLayoutVerified;
        Class<?> api = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.client.renderer.cel.util.ModelQuadUtil");
        try {
            String[] names = {"VERTEX_SIZE", "X_INDEX", "Y_INDEX", "Z_INDEX", "TEX_X_INDEX", "TEX_Y_INDEX", "COLOR_INDEX", "NORMAL_INDEX", "LIGHT_INDEX"};
            int[] expected = {8, 0, 1, 2, 3, 4, 5, 6, 7};
            for (int i = 0; i < names.length; i++) if (api.getField(names[i]).getInt(null) != expected[i]) { templateLayoutVerified = false; return false; }
            templateLayoutVerified = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { templateLayoutVerified = false; }
        return templateLayoutVerified;
    }

    public static void drawCommand(String command, int mode, int first, int count, int type, long indices, int instances) {
        if (!accepting() || !budget()) return;
        long started = System.nanoTime();
        try {
            JsonObject entry = backendCommand(command, mode, first, count, type, indices, instances);
            entry.add("state", NeiRenderState.snapshot());
            add(entry); request.submissionObserved = true;
        } catch (RuntimeException | LinkageError error) { gap("native_draw_command_api_unavailable"); }
        finally { charge(started); }
    }

    static JsonObject backendCommand(String command, int mode, int first, int count, int type, long indices, int instances) {
        JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_draw_command"); entry.addProperty("command", command);
        entry.addProperty("drawMode", mode); entry.addProperty("first", first); entry.addProperty("count", count); entry.addProperty("instanceCount", instances);
        if (type >= 0) { entry.addProperty("indexType", type); entry.addProperty("nativeIndicesArgument", indices); }
        entry.addProperty("source", "actual_backend_draw_entry"); entry.addProperty("backendClass", "com.gtnewhorizons.angelica.lwjgl3.Lwjgl3GLRenderBackend");
        entry.addProperty("observedStage", "HEAD_before_original_GL_delegate"); entry.addProperty("gpuDrawSuccessObserved", false); entry.addProperty("gpuBufferReadback", false);
        entry.addProperty("modeProvenance", "Actual native backend argument after any GLSM conversion; original requested GLSM primitive mode is not inferred.");
        entry.addProperty("geometryGap", "This entry records one routed LWJGL3 backend call under the selected owner. Direct native bypasses, other overloads, later GPU processing and GPU-only buffer contents are not observed.");
        return entry;
    }

    static void add(JsonObject entry) {
        if (request == null || request.done || !budget()) return;
        entry.addProperty("entryIndex", request.entries.size());
        entry.add("ownerPosition", NeiPreviewProvider.coordinates(request.position, 0, 64, 0).get("position"));
        if (owner != null) entry.addProperty("ownerKind", owner.kind);
        int bytes = entry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 2;
        if (request.storedBytes + bytes > MAX_STORED_BYTES) { request.limited = true; gap("transient_render_snapshot_byte_cap"); return; }
        request.storedBytes += bytes;
        request.entries.add(entry);
    }
    static void gap(String reason) { if (request != null && request.gaps.size() < 32) request.gaps.add(reason); }
}
