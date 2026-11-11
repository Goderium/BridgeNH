package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

/** Public baked models and loaded CTM rules; optional finite natural-frame data is armed separately. */
public final class NeiNativeRenderProvider {
    private static final List<String> TARGETS = Arrays.asList("preview_native_model", "preview_ctm_context", "preview_render_state", "preview_render_geometry");
    private static final long SOFT_NANOS = 4000000L;
    private static final int MAX_MODEL_QUADS = 512;
    private static final String MODEL_API = "com.gtnewhorizon.gtnhlib.client.model.baked.BakedModel";
    private static final String CONTEXT_API = "com.gtnewhorizon.gtnhlib.client.model.BakedModelQuadContext";
    private static final String QUAD_API = "com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.ModelQuadView";
    private static ModelSnapshot model;

    private static final class ModelSnapshot {
        final WeakReference<Object> screen, world;
        final long position, created;
        final String marker, resources;
        final List<JsonElement> entries = new ArrayList<>();
        final JsonObject metadata = new JsonObject();
        boolean limited;
        ModelSnapshot(Object screen, Object world, long position, String marker) {
            this.screen = new WeakReference<>(screen); this.world = new WeakReference<>(world); this.position = position;
            this.marker = marker; created = System.nanoTime(); resources = NeiPreviewResources.context();
        }
    }

    static boolean requested(JsonObject args) {
        return args.has("target") && args.get("target").isJsonPrimitive() && TARGETS.contains(args.get("target").getAsString());
    }

    static JsonObject read(Object screen, JsonObject args, int offset, int limit, long started) {
        NeiPreviewProvider.Scene scene = NeiPreviewProvider.scene(screen);
        if (scene == null || !scene.built) return NeiPreviewDetailProvider.absent("No existing built preview render model.");
        return readModel(screen, NeiRenderCapture.marker(screen), scene, args, offset, limit, started);
    }

    public static JsonObject capture(Object screen, JsonObject args) {
        for (Map.Entry<String, JsonElement> field : args.entrySet()) if (!Arrays.asList("screenId", "contextToken", "action", "previewBlockSource", "previewPosition").contains(field.getKey()))
            throw new BridgeException("invalid_args", "Field is not allowed for finite preview capture: " + field.getKey());
        if (!"preview_render_capture".equals(JsonData.string(args, "action"))) throw new BridgeException("invalid_args", "Invalid finite preview capture action.");
        NeiPreviewProvider.Scene scene = NeiPreviewProvider.scene(screen);
        if (scene == null || !scene.built) throw new BridgeException("not_present", "No existing built preview for finite render capture.");
        long packed = selected(scene, args);
        if (!scene.rendered.contains(packed) && !scene.translucent.contains(packed)) throw new BridgeException("not_present", "Requested position is not currently in the native visible render sets.");
        return NeiNaturalRenderCapture.arm(screen, NeiRenderCapture.marker(screen), scene, packed);
    }

    static JsonObject readModel(Object screen, String marker, NeiPreviewProvider.Scene scene, JsonObject args, int offset, int limit, long started) {
        String target = JsonData.string(args, "target");
        if (!TARGETS.contains(target) || offset < 0 || limit < 1 || limit > 64) throw new BridgeException("invalid_args", "Native render detail page is limited to 1..64 entries.");
        long packed = selected(scene, args);
        if ("preview_native_model".equals(target)) return baked(screen, marker, scene, packed, offset, limit, started);
        if ("preview_ctm_context".equals(target)) return ctm(screen, marker, scene, packed, offset, limit, started);
        return NeiNaturalRenderCapture.read(screen, marker, scene, packed, target, offset, limit, started);
    }

    private static long selected(NeiPreviewProvider.Scene scene, JsonObject args) {
        long packed = NeiPreviewDetailProvider.selectedPosition(scene, args);
        if (!scene.blocks.containsKey(packed)) throw new BridgeException("not_found", "Position is absent from the original existing preview model.");
        return packed;
    }

    private static JsonObject baked(Object screen, String marker, NeiPreviewProvider.Scene scene, long position, int offset, int limit, long started) {
        boolean matches = model != null && model.screen.get() == screen && model.world.get() == scene.world && model.position == position
                && model.marker.equals(marker) && model.resources.equals(NeiPreviewResources.context());
        if (offset == 0) { model = new ModelSnapshot(screen, scene.world, position, marker); collectBaked(model, scene); }
        else if (!matches) throw new BridgeException("stale_context", "Native model page context changed; restart at offset zero.");
        JsonObject result = NeiPreviewDetailProvider.base("preview_native_model"); JsonObject data = result.getAsJsonObject("data");
        for (Map.Entry<String, JsonElement> entry : model.metadata.entrySet()) data.add(entry.getKey(), entry.getValue());
        data.add("position", NeiPreviewProvider.coordinates(position, 0, 64, 0).get("position"));
        data.add("ownerPosition", data.get("position"));
        data.addProperty("resourceContext", model.resources); data.addProperty("modelSnapshotNanos", model.created);
        data.addProperty("pixelDataStored", false); data.addProperty("modelLimited", model.limited);
        data.addProperty("maximumModelQuads", MAX_MODEL_QUADS); data.addProperty("modelCallSoftBudgetNanos", SOFT_NANOS);
        data.addProperty("coordinateFrame", "Native model block-local coordinates before renderer translation, CTM, lighting, culling and GPU transforms.");
        data.addProperty("graphicsComplete", false);
        data.addProperty("modelCoverage", "Public native static BakedModel quad data sampled for this one actual preview position; dynamic models and non-model custom renderers use explicit natural-frame capture. No renderer/model-bake registration mutation or screenshot.");
        NeiPreviewDetailProvider.page(result, model.entries.size(), offset, limit, started, index -> model.entries.get(index));
        if (model.limited) result.addProperty("complete", false);
        return result;
    }

    private static void collectBaked(ModelSnapshot output, NeiPreviewProvider.Scene scene) {
        long started = System.nanoTime(); Object nativeContext = null; Class<?> worldContext = null;
        try {
            Object actual = scene.blocks.get(output.position);
            if (!(actual instanceof Block) || !(scene.world instanceof IBlockAccess)) { output.metadata.addProperty("modelStatus", "existing_native_block_access_unavailable"); return; }
            Block block = (Block) actual; Class<?> modelInfo = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.api.BlockModelInfo");
            Class<?> custom = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.api.IBlockModelProvider");
            boolean provider = custom != null && custom.isInstance(block), modeled = modelInfo != null && modelInfo.isInstance(block)
                    && (Boolean) NeiRenderReflection.call(modelInfo, block, "nhlib$isModeled");
            output.metadata.addProperty("nativeModelProvider", provider); output.metadata.addProperty("nativeModelFlag", modeled);
            if (!provider && !modeled) { output.metadata.addProperty("modelStatus", "block_has_no_published_baked_model"); return; }
            worldContext = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.client.model.WorldContext");
            Class<?> contextApi = NeiRenderReflection.optional(CONTEXT_API), modelApi = NeiRenderReflection.optional(MODEL_API);
            Class<?> registry = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.client.model.loading.ModelRegistry");
            if (worldContext == null || contextApi == null || modelApi == null || registry == null) { output.metadata.addProperty("modelStatus", "native_model_api_absent"); return; }
            nativeContext = worldContext.getConstructor().newInstance();
            int x = (int) (output.position >> 38), y = (int) (output.position << 52 >> 52), z = (int) (output.position << 26 >> 38);
            Class<?> randomType = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.util.StdLCG");
            if (randomType == null) { output.metadata.addProperty("modelStatus", "native_rng_api_absent"); return; }
            Random random = (Random) randomType.getConstructor().newInstance();
            NeiRenderReflection.call(worldContext, nativeContext, "set", new Class<?>[] {IBlockAccess.class, int.class, int.class, int.class, Random.class}, scene.world, x, y, z, random);
            Object baked = NeiRenderReflection.call(registry, null, "getBakedModel", new Class<?>[] {contextApi}, nativeContext);
            if (baked == null) { output.metadata.addProperty("modelStatus", "native_model_null"); return; }
            output.metadata.addProperty("modelClass", baked.getClass().getName());
            boolean dynamic = (Boolean) NeiRenderReflection.call(modelApi, baked, "isDynamic"); output.metadata.addProperty("dynamic", dynamic);
            if (dynamic) { output.metadata.addProperty("modelStatus", "dynamic_model_use_actual_natural_frame_capture"); return; }
            Object facings = NeiRenderReflection.optional("com.gtnewhorizon.gtnhlib.client.renderer.cel.model.quad.properties.ModelQuadFacing").getField("VALUES").get(null);
            Field facing = worldContext.getField("quadFacing"); Class<?> quadApi = NeiRenderReflection.optional(QUAD_API);
            for (int face = 0; face < java.lang.reflect.Array.getLength(facings); face++) {
                if (face > 0 && System.nanoTime() - started >= SOFT_NANOS) { output.limited = true; break; }
                Object direction = java.lang.reflect.Array.get(facings, face); facing.set(nativeContext, direction);
                NeiRenderReflection.call(worldContext, nativeContext, "seedRNG", new Class<?>[] {int.class, int.class, int.class}, x, y, z);
                Object quads = NeiRenderReflection.call(modelApi, baked, "getQuads", new Class<?>[] {contextApi}, nativeContext);
                if (!(quads instanceof List<?>)) { output.metadata.addProperty("modelStatus", "native_quad_list_unavailable"); return; }
                List<?> list = (List<?>) quads;
                for (int index = 0; index < list.size(); index++) {
                    if (output.entries.size() >= MAX_MODEL_QUADS || !output.entries.isEmpty() && System.nanoTime() - started >= SOFT_NANOS) { output.limited = true; break; }
                    output.entries.add(quad(quadApi, list.get(index), String.valueOf(direction), output.entries.size()));
                }
                if (output.limited) break;
            }
            output.metadata.addProperty("modelStatus", "published_static_baked_model");
            output.metadata.addProperty("modelSource", "Existing block's public IBlockModelProvider/ModelRegistry.getBakedModel(native WorldContext) and BakedModel.getQuads; native context retains actual preview world/property state and native coordinate RNG seeding.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            output.metadata.addProperty("modelStatus", "native_model_api_unavailable"); output.metadata.addProperty("modelError", error.getClass().getSimpleName());
        } finally {
            if (nativeContext != null && worldContext != null) try { NeiRenderReflection.call(worldContext, nativeContext, "reset"); } catch (RuntimeException | LinkageError ignored) { }
            output.metadata.addProperty("nativeGetterNanos", Math.max(0L, System.nanoTime() - started));
            output.metadata.addProperty("nativeGetterBudgetNote", "Soft budget bounds subsequent calls and returned entries; an individual third-party model getter cannot be preempted safely.");
        }
    }

    private static JsonObject quad(Class<?> api, Object quad, String facing, int index) {
        JsonObject result = new JsonObject(); result.addProperty("kind", "native_baked_quad"); result.addProperty("quadIndex", index); result.addProperty("queryFacing", facing);
        result.addProperty("quadClass", quad.getClass().getName());
        for (String name : new String[] {"getFlags", "getColorIndex", "getEmissiveness", "getShaderBlockId", "getComputedFaceNormal"})
            result.addProperty(name, ((Number) NeiRenderReflection.call(api, quad, name)).intValue());
        result.addProperty("transparent", (Boolean) NeiRenderReflection.call(api, quad, "isTransparent"));
        result.addProperty("directionalShading", (Boolean) NeiRenderReflection.call(api, quad, "hasDirectionalShading"));
        Object sprite = NeiRenderReflection.call(api, quad, "celeritas$getSprite"); result.add("sprite", NeiTextureProvider.icon(sprite, "blocks"));
        JsonArray vertices = new JsonArray();
        for (int vertex = 0; vertex < 4; vertex++) {
            JsonObject value = new JsonObject();
            for (String name : new String[] {"getX", "getY", "getZ", "getTexU", "getTexV"}) {
                float number = ((Number) NeiRenderReflection.call(api, quad, name, new Class<?>[] {int.class}, vertex)).floatValue();
                if (Float.isInfinite(number) || Float.isNaN(number)) value.add(name, com.google.gson.JsonNull.INSTANCE); else value.addProperty(name, number);
            }
            for (String name : new String[] {"getColor", "getLight", "getForgeNormal"})
                value.addProperty(name, ((Number) NeiRenderReflection.call(api, quad, name, new Class<?>[] {int.class}, vertex)).intValue());
            vertices.add(value);
        }
        result.add("vertices", vertices); return result;
    }

    private static JsonObject ctm(Object screen, String marker, NeiPreviewProvider.Scene scene, long position, int offset, int limit, long started) {
        JsonObject result = NeiPreviewDetailProvider.base("preview_ctm_context"); JsonObject data = result.getAsJsonObject("data");
        data.add("position", NeiPreviewProvider.coordinates(position, 0, 64, 0).get("position"));
        data.add("ownerPosition", data.get("position")); data.addProperty("resourceContext", NeiPreviewResources.context());
        data.addProperty("pixelDataStored", false); data.addProperty("rulesSource", "Actual already-loaded Angelica CTM override map and native BlockStateMatcher; only rules for this block are visited. No registration, reload, resolver replay, or renderer callback.");
        data.addProperty("graphicsComplete", false); data.addProperty("compactCoverage", "Rule and actual 26-neighbor context, plus independently captured native resolver returns and emitted split primitives. Rules/neighbors alone are not a reimplementation of CTM.");
        List<JsonElement> entries = new ArrayList<>();
        if (!(scene.world instanceof IBlockAccess) || !(scene.blocks.get(position) instanceof Block)) data.addProperty("ctmStatus", "native_block_access_unavailable");
        else {
            IBlockAccess world = (IBlockAccess) scene.world; Block block = (Block) scene.blocks.get(position);
            int x = (int) (position >> 38), y = (int) (position << 52 >> 52), z = (int) (position << 26 >> 38);
            try {
                Class<?> api = NeiRenderReflection.optional("com.prupe.mcpatcher.ctm.CTMUtils"), overridesApi = NeiRenderReflection.optional("com.prupe.mcpatcher.ctm.CTMUtils$Overrides");
                Object overrides = NeiRenderReflection.field(api, null, "overrides"); Object raw = NeiRenderReflection.call(overridesApi, overrides, "getBlock");
                Object blockRules = raw instanceof Map<?, ?> ? ((Map<?, ?>) raw).get(block) : null;
                Class<?> matcherApi = NeiRenderReflection.optional("com.prupe.mcpatcher.mal.block.BlockStateMatcher");
                if (blockRules instanceof List<?>) {
                    List<?> rules = (List<?>) blockRules;
                    for (int i = 0; i < rules.size() && i < 64; i++) {
                        if (i > 0 && System.nanoTime() - started >= SOFT_NANOS) { data.addProperty("ruleSoftBudgetPartial", true); break; }
                        Object matcher = rules.get(i); boolean match = (Boolean) NeiRenderReflection.call(matcherApi, matcher, "match", new Class<?>[] {IBlockAccess.class, int.class, int.class, int.class}, world, x, y, z);
                        if (match) { JsonObject rule = ctmRule(NeiRenderReflection.call(matcherApi, matcher, "getData")); rule.addProperty("kind", "native_loaded_ctm_rule"); entries.add(rule); }
                    }
                    data.addProperty("loadedBlockRuleCount", rules.size()); if (rules.size() > 64) data.addProperty("ruleCapPartial", true);
                }
                data.addProperty("ctmStatus", "native_loaded_rules");
            } catch (RuntimeException | LinkageError error) { data.addProperty("ctmStatus", "native_ctm_api_unavailable"); }
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                JsonObject neighbor = new JsonObject(); neighbor.addProperty("kind", "actual_ctm_neighbor");
                neighbor.addProperty("dx", dx); neighbor.addProperty("dy", dy); neighbor.addProperty("dz", dz);
                Block other = world.getBlock(x + dx, y + dy, z + dz);
                neighbor.addProperty("block", other == null ? null : String.valueOf(Block.blockRegistry.getNameForObject(other)));
                neighbor.addProperty("metadata", world.getBlockMetadata(x + dx, y + dy, z + dz)); neighbor.addProperty("sameBlockIdentity", other == block); entries.add(neighbor);
            }
        }
        data.add("actualRenderSnapshot", NeiNaturalRenderCapture.read(screen, marker, scene, position, "preview_render_geometry", 0, 1, started));
        data.addProperty("actualResolutionTarget", "preview_render_geometry"); data.addProperty("actualResolutionRecordKind", "native_ctm_resolution");
        NeiPreviewDetailProvider.page(result, entries.size(), offset, limit, started, index -> entries.get(index));
        if (data.has("ruleSoftBudgetPartial") || data.has("ruleCapPartial")) result.addProperty("complete", false);
        return result;
    }

    static JsonObject ctmRule(Object override) {
        JsonObject rule = new JsonObject();
        if (override == null) { rule.addProperty("ruleStatus", "null_override"); return rule; }
        rule.addProperty("overrideClass", override.getClass().getName());
        try {
            Class<?> tileApi = NeiRenderReflection.optional("com.prupe.mcpatcher.ctm.TileOverride");
            Object props = tileApi.getField("properties").get(override); Class<?> propertiesApi = NeiRenderReflection.optional("com.prupe.mcpatcher.mal.resource.PropertiesFile");
            rule.addProperty("resource", JsonData.bounded(String.valueOf(NeiRenderReflection.call(propertiesApi, props, "getResource")), 2048));
            for (String key : new String[] {"method", "connect", "matchBlocks", "metadata", "matchTiles", "faces", "tiles", "renderPass", "innerSeams", "weights"})
                rule.addProperty(key, JsonData.bounded(String.valueOf(NeiRenderReflection.call(propertiesApi, props, "getString", new Class<?>[] {String.class, String.class}, key, "")), 2048));
            rule.addProperty("ruleStatus", "native_stored_properties");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { rule.addProperty("ruleStatus", "native_properties_unavailable"); }
        return rule;
    }
}
