package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.item.ItemStack;

/** Fixed public description branches; never queries keyboard state or invokes structure actions. */
public final class MachineDescriptionAdapter {
    private static final String CONSTRUCTABLE = "com.gtnewhorizon.structurelib.alignment.constructable.IConstructable";
    private static final String META = "gregtech.api.interfaces.metatileentity.IMetaTileEntity";
    private static final String SECONDARY = "gregtech.api.interfaces.ISecondaryDescribable";
    static final int LINE_LIMIT = 64;
    static final int LINE_CHAR_LIMIT = 512;
    static final int TOTAL_CHAR_LIMIT = 8192;

    public static void add(JsonObject result, Object meta) {
        result.add("structureDescription", structure(meta));
        result.add("machineDescription", fixedDescription(meta, "getPrimaryDescription"));
        result.add("structureTooltip", fixedDescription(meta, "getSecondaryDescription"));
    }

    static JsonObject structure(Object meta) {
        JsonObject result = empty(CONSTRUCTABLE + ".getStructureDescription(ItemStack)");
        result.addProperty("scope", "Public StructureLib construction hints for this controller's count-1 stack; not a structure validation result.");
        try {
            Class<?> constructable = publicInterface(meta, CONSTRUCTABLE);
            Class<?> stackApi = publicInterface(meta, META);
            if (constructable == null) return unavailable(result, "public_constructable_interface_unavailable_or_not_implemented", null);
            if (stackApi == null) return unavailable(result, "public_stack_form_interface_unavailable_or_not_implemented", null);
            Object value = invoke(stackApi, meta, "getStackForm", new Class<?>[] {long.class}, 1L);
            if (!(value instanceof ItemStack) || ((ItemStack) value).getItem() == null)
                return unavailable(result, "public_stack_form_returned_no_item", null);
            ItemStack copy = ((ItemStack) value).copy();
            copy.stackSize = 1;
            result.add("controllerStack", JsonData.item(copy));
            result.addProperty("stackSource", META + ".getStackForm(1L).copy");
            return lines(result, invoke(constructable, meta, "getStructureDescription", new Class<?>[] {ItemStack.class}, copy));
        } catch (RuntimeException | LinkageError error) { return unavailable(result, "public_getter_failed", error); }
    }

    static JsonObject fixedDescription(Object meta, String method) {
        if (!"getPrimaryDescription".equals(method) && !"getSecondaryDescription".equals(method))
            throw new BridgeException("unsupported", "Only fixed primary and secondary public descriptions are supported.");
        JsonObject result = empty(SECONDARY + "." + method + "()");
        result.addProperty("scope", "getPrimaryDescription".equals(method) ? "Fixed public machine tooltip branch." : "Fixed public secondary structure tooltip branch; separate from StructureLib construction hints.");
        try {
            Class<?> api = publicInterface(meta, SECONDARY);
            if (api == null) return unavailable(result, "public_secondary_description_interface_unavailable_or_not_implemented", null);
            return lines(result, invoke(api, meta, method, new Class<?>[0]));
        } catch (RuntimeException | LinkageError error) { return unavailable(result, "public_getter_failed", error); }
    }

    private static JsonObject empty(String api) {
        JsonObject result = new JsonObject();
        result.addProperty("api", api); result.addProperty("available", false);
        result.add("entries", new JsonArray()); result.addProperty("total", 0); result.addProperty("truncated", false);
        JsonObject bounds = new JsonObject(); bounds.addProperty("maxEntries", LINE_LIMIT);
        bounds.addProperty("maxEntryChars", LINE_CHAR_LIMIT); bounds.addProperty("maxTotalChars", TOTAL_CHAR_LIMIT);
        result.add("bounds", bounds);
        return result;
    }

    private static JsonObject lines(JsonObject result, Object value) {
        if (!(value instanceof String[])) return unavailable(result, value == null ? "public_getter_returned_null" : "public_getter_returned_wrong_type", null);
        String[] source = (String[]) value;
        JsonArray entries = new JsonArray(); int chars = 0; boolean clipped = false;
        for (int i = 0; i < Math.min(source.length, LINE_LIMIT); i++) {
            String line = source[i];
            if (line == null) { entries.add(JsonNull.INSTANCE); continue; }
            int allowed = Math.min(LINE_CHAR_LIMIT, TOTAL_CHAR_LIMIT - chars);
            if (allowed == 0 && !line.isEmpty()) break;
            String text = JsonData.bounded(line, allowed);
            clipped |= text.length() != line.length(); chars += text.length();
            entries.add(new JsonPrimitive(text));
        }
        result.addProperty("available", true); result.add("entries", entries); result.addProperty("total", source.length);
        result.addProperty("truncated", clipped || entries.size() < source.length);
        result.addProperty("returnedChars", chars);
        return result;
    }

    private static JsonObject unavailable(JsonObject result, String reason, Throwable failure) {
        result.addProperty("available", false); result.addProperty("reason", reason);
        if (failure != null) result.addProperty("error", JsonData.bounded(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(), 512));
        return result;
    }

    private static Class<?> publicInterface(Object target, String name) {
        if (target == null) return null;
        try {
            Class<?> api = Class.forName(name, false, target.getClass().getClassLoader());
            return api.isInterface() && Modifier.isPublic(api.getModifiers()) && api.isInstance(target) ? api : null;
        } catch (ClassNotFoundException | LinkageError error) { return null; }
    }

    private static Object invoke(Class<?> api, Object target, String name, Class<?>[] signature, Object... args) {
        try {
            Method method = api.getMethod(name, signature);
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) throw new BridgeException("unsupported", "Public description method is unavailable.");
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            throw new BridgeException("adapter_error", "Public description getter failed: " + cause.getClass().getSimpleName());
        } catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Public description method is unavailable: " + name); }
    }
}
