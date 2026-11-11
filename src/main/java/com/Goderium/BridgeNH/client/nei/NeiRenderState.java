package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/** Native Angelica state/events only, no GL mutation, recorder, screenshot, or draw. */
final class NeiRenderState {
    private static final String GLSM = "com.gtnewhorizons.angelica.glsm.GLStateManager";
    private static final String HOOKS = "com.gtnewhorizons.angelica.glsm.hooks.GLSMHooks";
    private static final String BUS = "com.gtnewhorizons.angelica.shadow.net.minecraftforge.eventbus.api.bus.EventBus";
    private static final List<Object> LISTENERS = new ArrayList<>();
    private static boolean initialized;
    private static String eventStatus = "not_initialized";

    static void initialize() {
        if (initialized) return;
        initialized = true;
        Class<?> hooks = NeiRenderReflection.optional(HOOKS), bus = NeiRenderReflection.optional(BUS);
        if (hooks == null || bus == null) { eventStatus = "angelica_event_api_absent"; return; }
        String[] names = {"TEXTURE_BIND", "PROGRAM_CHANGE", "BLEND_FUNC_CHANGE", "LIGHTMAP_COORDS", "ALPHA_STATE_CHANGE", "SHADER_COLOR_CHANGE"};
        int registered = 0;
        for (String name : names) {
            try {
                Object eventBus = hooks.getField(name).get(null);
                Object listener = NeiRenderReflection.call(bus, eventBus, "addListener", new Class<?>[] {Consumer.class}, (Consumer<Object>) event -> observe(name, event));
                LISTENERS.add(listener); registered++;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { }
        }
        eventStatus = registered + "_native_state_event_listeners";
    }

    private static void observe(String name, Object event) {
        if (!NeiNaturalRenderCapture.accepting()) return;
        long started = System.nanoTime();
        try {
            JsonObject entry = new JsonObject(); entry.addProperty("kind", "native_state_event"); entry.addProperty("event", name);
            String[] fields;
            if ("TEXTURE_BIND".equals(name)) fields = new String[] {"textureId"};
            else if ("PROGRAM_CHANGE".equals(name)) fields = new String[] {"previousProgram", "newProgram", "postBind"};
            else if ("BLEND_FUNC_CHANGE".equals(name)) fields = new String[] {"srcRgb", "dstRgb", "srcAlpha", "dstAlpha"};
            else fields = new String[0];
            for (String field : fields) {
                Object value = event.getClass().getField(field).get(event);
                if (value instanceof Number) entry.addProperty(field, (Number) value);
                else if (value instanceof Boolean) entry.addProperty(field, (Boolean) value);
            }
            if (fields.length == 0) entry.add("state", snapshot());
            entry.addProperty("eventProvenance", "Angelica native GLSMHooks event; copied during requested owner scope. Shared mutable event is never retained or modified.");
            NeiNaturalRenderCapture.add(entry);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { NeiNaturalRenderCapture.gap("native_state_event_api_unavailable"); }
        finally { NeiNaturalRenderCapture.charge(started); }
    }

    static JsonObject runtime() {
        JsonObject data = new JsonObject(); data.addProperty("eventObserverStatus", eventStatus);
        data.addProperty("pixelDataStored", false);
        JsonArray packs = new JsonArray();
        try {
            Object settings = Minecraft.getMinecraft().gameSettings;
            Object value = NeiRenderReflection.field(net.minecraft.client.settings.GameSettings.class, settings, "resourcePacks", "field_151453_l");
            if (value instanceof List<?>) for (Object pack : (List<?>) value) {
                if (packs.size() >= 64) break;
                packs.add(new com.google.gson.JsonPrimitive(JsonData.bounded(String.valueOf(pack), 512)));
            }
            data.addProperty("resourcePackSource", "Current GameSettings.resourcePacks names only. Built-in mod resources can supply CTM even with this list empty.");
        } catch (RuntimeException | LinkageError error) { data.addProperty("resourcePackStatus", "unavailable"); }
        data.add("enabledExternalResourcePacks", packs);
        Class<?> api = NeiRenderReflection.optional("net.irisshaders.iris.api.v0.IrisApi");
        if (api != null) {
            try {
                Object iris = NeiRenderReflection.call(api, null, "getInstance");
                data.addProperty("externalShaderPackInUse", (Boolean) NeiRenderReflection.call(api, iris, "isShaderPackInUse"));
                data.addProperty("shadowPass", (Boolean) NeiRenderReflection.call(api, iris, "isRenderingShadowPass"));
                Class<?> irisClass = NeiRenderReflection.optional("net.coderbot.iris.Iris");
                data.addProperty("externalShaderPackName", JsonData.bounded(String.valueOf(NeiRenderReflection.call(irisClass, null, "getCurrentPackName")), 512));
                data.addProperty("externalShaderPackLoadId", ((Number) NeiRenderReflection.call(irisClass, null, "getShaderPackLoadId")).intValue());
            } catch (RuntimeException | LinkageError error) { data.addProperty("shaderPackApiStatus", "unavailable"); }
        } else data.addProperty("shaderPackApiStatus", "iris_api_absent");
        data.addProperty("shaderCoverage", "Native requested program IDs, transforms, textures and render state; mod GLSL and Angelica fixed-function emulation may run independently of an external shaderpack. Program IDs alone do not identify source files or reconstruct shader pixels.");
        return data;
    }

    static JsonObject snapshot() {
        JsonObject data = new JsonObject(); Class<?> api = NeiRenderReflection.optional(GLSM);
        if (api == null) { data.addProperty("status", "angelica_state_api_absent"); return data; }
        try {
            data.addProperty("requestedProgram", number(api, "getActiveProgram"));
            data.addProperty("boundVBO", number(api, "getBoundVBO")); data.addProperty("boundVAO", number(api, "getBoundVAO"));
            data.addProperty("drawFramebuffer", number(api, "getDrawFramebuffer"));
            data.addProperty("textureUnit0", ((Number) NeiRenderReflection.call(api, null, "getBoundTextureForServerState", new Class<?>[] {int.class}, 0)).intValue());
            data.add("modelView", matrix(NeiRenderReflection.call(api, null, "getModelViewMatrix")));
            data.add("projection", matrix(NeiRenderReflection.call(api, null, "getProjectionMatrix")));
            Object context = NeiRenderReflection.call(api, null, "ctx"); Class<?> owner = NeiRenderReflection.optional("com.gtnewhorizons.angelica.glsm.GLContextState");
            for (String name : new String[] {"mvGeneration", "projGeneration", "programGeneration", "drawFramebufferGeneration"})
                data.addProperty(name, ((Number) owner.getField(name).get(context)).intValue());
            data.addProperty("status", "native_glsm_state");
            data.addProperty("stateProvenance", "Angelica public cached context/getters at actual owner/primitive boundary. requestedProgram=0 can use internal fixed-function shader; not proof of no shader.");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { data.addProperty("status", "partial_native_glsm_state"); }
        return data;
    }

    private static int number(Class<?> owner, String method) { return ((Number) NeiRenderReflection.call(owner, null, method)).intValue(); }

    private static JsonArray matrix(Object matrix) {
        JsonArray values = new JsonArray(); Class<?> api = NeiRenderReflection.optional("org.joml.Matrix4fc");
        if (matrix == null || api == null || !api.isInstance(matrix)) return values;
        float[] elements = new float[16]; NeiRenderReflection.call(api, matrix, "get", new Class<?>[] {float[].class}, (Object) elements);
        for (float value : elements) values.add(Float.isInfinite(value) || Float.isNaN(value) ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(value));
        return values;
    }
}
