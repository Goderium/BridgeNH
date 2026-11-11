package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Array;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Internal fixed-name compatibility calls. No name, class, field or method is supplied by RPC callers. */
public final class ExpandedGuiApi {
    private ExpandedGuiApi() {}
    public static Class<?> named(Class<?> type, String name) {
        if (type == null) return null;
        if (name.equals(type.getName())) return type;
        for (Class<?> api : type.getInterfaces()) { Class<?> found = named(api, name); if (found != null) return found; }
        return named(type.getSuperclass(), name);
    }
    public static boolean is(Object target, String name) { return target != null && named(target.getClass(), name) != null; }
    public static Class<?> load(Object context, String name) {
        try { return Class.forName(name, false, context.getClass().getClassLoader()); }
        catch (ClassNotFoundException | LinkageError e) { throw new BridgeException("unsupported", "Required fixed GUI API unavailable: " + name); }
    }
    public static Object field(Object target, String owner, String name) {
        Class<?> api = named(target.getClass(), owner);
        if (api == null) throw new BridgeException("unsupported", "Current GUI lacks " + owner);
        return field(target, api, name);
    }
    public static Object field(Object target, Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch (ReflectiveOperationException | RuntimeException e) { throw new BridgeException("unsupported", "Fixed GUI field unavailable: " + owner.getName() + "." + name); }
    }
    static Object optional(Object target, String owner, String name) {
        if (!is(target, owner)) return null;
        try { return field(target, owner, name); } catch (BridgeException e) { return null; }
    }
    /** Read-only, one-level native GUI discovery; never traverses model/machine fields or calls suppliers. */
    static NativeTextControls nativeTextControls(Object screen) {
        NativeTextControls result = new NativeTextControls();
        Map<Object, String> controls = new java.util.IdentityHashMap<Object, String>();
        if (!(screen instanceof net.minecraft.client.gui.GuiScreen)) return result;
        int examined = 0, members = 0;
        for (Class<?> owner = screen.getClass(); owner != null && examined < 512; owner = owner.getSuperclass()) {
            Field[] declared;
            try { declared = owner.getDeclaredFields(); }
            catch (RuntimeException | LinkageError failure) { result.error = failure.getClass().getSimpleName(); continue; }
            for (Field field : declared) {
                if (++examined > 512) { result.truncated = true; break; }
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (!nativeTextType(type) && !List.class.isAssignableFrom(type) && !type.isArray()) continue;
                try {
                    field.setAccessible(true); Object value = field.get(screen);
                    if (value == null) continue;
                    String name = owner.getSimpleName() + "." + field.getName();
                    if (nativeTextType(value.getClass())) controls.put(value, name);
                    else if (value instanceof List<?>) {
                        List<?> list = (List<?>) value;
                        if (list.size() > 256) result.truncated = true;
                        for (int i = 0; i < Math.min(256, list.size()); i++) {
                            if (++members > 2048) { result.truncated = true; break; }
                            Object child = list.get(i);
                            if (child != null && nativeTextType(child.getClass())) controls.put(child, name + "[" + i + "]");
                        }
                    } else if (value.getClass().isArray()) {
                        if (Array.getLength(value) > 256) result.truncated = true;
                        for (int i = 0; i < Math.min(256, Array.getLength(value)); i++) {
                            if (++members > 2048) { result.truncated = true; break; }
                            Object child = Array.get(value, i);
                            if (child != null && nativeTextType(child.getClass())) controls.put(child, name + "[" + i + "]");
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { result.error = failure.getClass().getSimpleName(); }
            }
        }
        // Field order is stable for a loaded class; sort names rather than IdentityHashMap's hash traversal.
        java.util.ArrayList<Map.Entry<Object, String>> ordered = new java.util.ArrayList<Map.Entry<Object, String>>(controls.entrySet());
        java.util.Collections.sort(ordered, (left, right) -> left.getValue().compareTo(right.getValue()));
        for (Map.Entry<Object, String> entry : ordered) result.controls.put(entry.getKey(), entry.getValue());
        return result;
    }
    static final class NativeTextControls {
        final Map<Object, String> controls = new LinkedHashMap<Object, String>();
        boolean truncated;
        String error;
    }
    private static boolean nativeTextType(Class<?> type) {
        return named(type, "net.minecraft.client.gui.GuiTextField") != null
                || named(type, "net.minecraft.client.gui.GuiLabel") != null
                || named(type, "appeng.client.gui.widgets.MEGuiTextField") != null;
    }
    public static Object call(Object target, String name, Object... args) {
        if (target == null) throw new BridgeException("unsupported", "Fixed GUI API target is unavailable: " + name);
        Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Method method : type.getMethods()) if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
            try { if (!java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) method.setAccessible(true); return method.invoke(target instanceof Class<?> ? null : target, args); }
            catch (InvocationTargetException e) { throw new BridgeException("adapter_error", "GUI API " + name + " failed: " + e.getCause().getClass().getSimpleName()); }
            catch (ReflectiveOperationException | RuntimeException e) { throw new BridgeException("adapter_error", "GUI API invocation failed: " + name); }
        }
        throw new BridgeException("unsupported", "Fixed public GUI method unavailable: " + name);
    }
    public static Object create(Object context, String name, Object... args) {
        for (Constructor<?> ctor : load(context, name).getConstructors()) if (matches(ctor.getParameterTypes(), args)) {
            try { return ctor.newInstance(args); }
            catch (InvocationTargetException e) { throw new BridgeException("adapter_error", "Native packet construction failed: " + e.getCause().getClass().getSimpleName()); }
            catch (ReflectiveOperationException | RuntimeException e) { throw new BridgeException("adapter_error", "Native packet construction failed: " + name); }
        }
        throw new BridgeException("unsupported", "Fixed native constructor unavailable: " + name);
    }
    public static Object declaredCall(Object target, String owner, String name, Object... args) {
        Class<?> api = named(target.getClass(), owner);
        if (api == null) throw new BridgeException("unsupported", "Required fixed GUI API is unavailable.");
        for (Method method : api.getDeclaredMethods()) if (method.getName().equals(name) && matches(method.getParameterTypes(), args)) {
            try { method.setAccessible(true); return method.invoke(target, args); }
            catch (InvocationTargetException e) { throw new BridgeException("adapter_error", "Native GUI action failed: " + e.getCause().getClass().getSimpleName()); }
            catch (ReflectiveOperationException | RuntimeException e) { throw new BridgeException("adapter_error", "Native GUI action invocation failed."); }
        }
        throw new BridgeException("unsupported", "Fixed native GUI action is unavailable: " + name);
    }
    static void protectedCall(Object target, Class<?> owner, String[] names, Class<?> parameter, Object argument) {
        for (String name : names) {
            try { Method method = owner.getDeclaredMethod(name, parameter); method.setAccessible(true); method.invoke(target, argument); return; }
            catch (NoSuchMethodException e) { /* Fixed deobfuscated / production mapping fallback. */ }
            catch (InvocationTargetException e) { throw new BridgeException("adapter_error", "Native GUI action failed: " + e.getCause().getClass().getSimpleName()); }
            catch (ReflectiveOperationException | RuntimeException e) { throw new BridgeException("adapter_error", "Native GUI action unavailable."); }
        }
        throw new BridgeException("unsupported", "Native GUI action method unavailable.");
    }
    public @SuppressWarnings({"unchecked", "rawtypes"})
    static Object enumeration(Object context, String name, String value) {
        Class<?> type = load(context, name);
        try { return Enum.valueOf((Class) type, value); }
        catch (IllegalArgumentException e) { throw new BridgeException("invalid_args", "Unknown native enum option: " + value); }
    }
    public static String string(JsonObject args, String name, int max) {
        String text = JsonData.string(args, name);
        if (text.length() > max) throw new BridgeException("invalid_args", name + " exceeds " + max + " characters.");
        return text;
    }
    public static String option(JsonObject args, String name, String fallback, String... choices) {
        String value = args.has(name) ? string(args, name, 128) : fallback;
        if (!Arrays.asList(choices).contains(value)) throw new BridgeException("invalid_args", "Invalid " + name + ".");
        return value;
    }
    public static long amount(JsonObject args, String name) {
        String value = string(args, name, 19);
        try { if (!value.matches("[1-9][0-9]*")) throw new NumberFormatException(); return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new BridgeException("invalid_args", name + " must be a positive decimal string no greater than Long.MAX_VALUE."); }
    }
    public static void keys(JsonObject args, String... allowed) {
        Set<String> keys = new HashSet<String>(Arrays.asList(allowed));
        keys.add("screenId"); keys.add("action");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : args.entrySet())
            if (!keys.contains(entry.getKey())) throw new BridgeException("invalid_args", "Unexpected argument: " + entry.getKey());
    }
    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> type = types[i];
            if (type.isPrimitive()) {
                if (type == int.class) type = Integer.class; else if (type == long.class) type = Long.class;
                else if (type == boolean.class) type = Boolean.class; else if (type == double.class) type = Double.class;
                else if (type == float.class) type = Float.class; else if (type == char.class) type = Character.class;
                else if (type == byte.class) type = Byte.class; else if (type == short.class) type = Short.class;
            }
            if (!type.isInstance(args[i])) return false;
        }
        return true;
    }
}
