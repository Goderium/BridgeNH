package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Fixed optional rendering APIs only. Caller arguments never select a class, field, or method. */
public final class NeiRenderReflection {
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    public static Class<?> optional(String name) {
        try { return Class.forName(name, false, NeiRenderReflection.class.getClassLoader()); }
        catch (ClassNotFoundException | LinkageError error) { return null; }
    }

    public static Object call(Class<?> owner, Object instance, String name, Class<?>[] signature, Object... args) {
        if (owner == null) throw new BridgeException("unsupported", "Optional native render API is absent.");
        String key = owner.getName() + '#' + name + Arrays.toString(signature);
        try {
            Method method = METHODS.get(key);
            if (method == null || !method.getDeclaringClass().isAssignableFrom(owner)) {
                method = owner.getMethod(name, signature); METHODS.put(key, method);
            }
            return method.invoke(instance, args);
        } catch (InvocationTargetException error) {
            throw new BridgeException("adapter_error", "Native render data getter failed: " + name + '/' + error.getCause().getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new BridgeException("unsupported", "Native render data API unavailable: " + owner.getName() + '.' + name);
        }
    }

    static Object call(Class<?> owner, Object instance, String name) {
        return call(owner, instance, name, new Class<?>[0]);
    }

    static Object publicField(Class<?> owner, Object instance, String name) {
        String key = owner.getName() + "#public#" + name;
        try {
            Field field = FIELDS.get(key);
            if (field == null) { field = owner.getField(name); FIELDS.put(key, field); }
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new BridgeException("unsupported", "Public native render field unavailable: " + owner.getName() + '.' + name);
        }
    }

    static Object field(Class<?> owner, Object instance, String... names) {
        if (owner == null) throw new BridgeException("unsupported", "Native render field owner is absent.");
        String key = owner.getName() + '#' + Arrays.toString(names);
        try {
            Field field = FIELDS.get(key);
            if (field == null || field.getDeclaringClass() != owner) {
                for (String name : names) {
                    try { field = owner.getDeclaredField(name); break; }
                    catch (NoSuchFieldException absent) { }
                }
                if (field == null) throw new NoSuchFieldException(Arrays.toString(names));
                field.setAccessible(true); FIELDS.put(key, field);
            }
            return field.get(instance);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new BridgeException("unsupported", "Fixed native render field unavailable: " + owner.getName() + '/' + names[0]);
        }
    }

    static int integer(Class<?> owner, Object instance, String... names) {
        return ((Number) field(owner, instance, names)).intValue();
    }

    static boolean bool(Class<?> owner, Object instance, String... names) {
        return (Boolean) field(owner, instance, names);
    }

    static double decimal(Class<?> owner, Object instance, String... names) {
        return ((Number) field(owner, instance, names)).doubleValue();
    }
}
