package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Read public enum transforms and this real controller's limits without changing alignment. */
public final class StructureAlignmentFrames {
    public static final int VERSION = 1;
    static JsonObject read(Object meta, Object facing) {
        if (facing == null) throw new BridgeException("unsupported", "No actual public ExtendedFacing is available.");
        try {
            ClassLoader loader = meta.getClass().getClassLoader();
            Class<?> extended = Class.forName("com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing", false, loader);
            Class<?> alignmentApi = Class.forName("com.gtnewhorizon.structurelib.alignment.IAlignment", false, loader);
            Class<?> limitsApi = Class.forName("com.gtnewhorizon.structurelib.alignment.IAlignmentLimits", false, loader);
            if (!extended.isEnum() || !extended.isInstance(facing) || !alignmentApi.isInstance(meta))
                throw new BridgeException("unsupported", "The actual controller must expose the public IAlignment contract.");
            Object limits = alignmentApi.getMethod("getAlignmentLimits").invoke(meta);
            if (!limitsApi.isInstance(limits)) throw new BridgeException("unsupported", "The actual controller exposes no public alignment limits.");
            Method valid = limitsApi.getMethod("isNewExtendedFacingValid", extended);
            Method offset = extended.getMethod("getWorldOffset", int[].class, int[].class);
            Object[] constants = extended.getEnumConstants();
            if (constants.length < 1 || constants.length > 128) throw new BridgeException("unsupported", "Public alignment enumeration exceeds the bounded view.");
            JsonArray frames = new JsonArray();
            for (Object candidate : constants) {
                JsonObject row = new JsonObject();
                row.add("alignment", alignment(candidate));
                int[][] columns = new int[3][];
                for (int column = 0; column < 3; column++) {
                    int[] unit = new int[3], out = new int[3]; unit[column] = 1;
                    offset.invoke(candidate, unit, out); columns[column] = out;
                }
                JsonArray matrix = new JsonArray();
                for (int axis = 0; axis < 3; axis++) {
                    JsonArray values = new JsonArray();
                    for (int column = 0; column < 3; column++) values.add(new com.google.gson.JsonPrimitive(columns[column][axis]));
                    matrix.add(values);
                }
                row.add("axisTransform", matrix);
                row.addProperty("sourceControllerAlignmentAllowed", (Boolean) valid.invoke(limits, candidate));
                frames.add(row);
            }
            JsonObject result = new JsonObject();
            result.addProperty("mode", "alignment_frames"); result.addProperty("version", VERSION);
            result.add("alignment", alignment(facing)); result.add("frames", frames);
            result.addProperty("complete", true); result.addProperty("readOnly", true);
            result.addProperty("worldEffectsDispatched", false); result.addProperty("placementVerified", false);
            result.addProperty("source", "public ExtendedFacing enum/getWorldOffset and actual IAlignment.getAlignmentLimits");
            result.addProperty("limitsScope", "This existing controller's current public limits; no proposed unplaced target, complete structure, runtime mode or gravity proof.");
            return result;
        } catch (InvocationTargetException error) {
            throw new BridgeException("adapter_error", "Public alignment getter failed: " + error.getCause().getClass().getSimpleName());
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new BridgeException("unsupported", "Public StructureLib alignment frame APIs are unavailable.");
        }
    }
    private static JsonObject alignment(Object facing) throws ReflectiveOperationException {
        JsonObject result = new JsonObject();
        for (String key : new String[] {"Direction", "Rotation", "Flip"})
            result.addProperty(key.toLowerCase(java.util.Locale.ROOT), String.valueOf(facing.getClass().getMethod("get" + key).invoke(facing)));
        return result;
    }
}
