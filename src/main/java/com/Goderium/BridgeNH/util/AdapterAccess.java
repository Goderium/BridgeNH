package com.Goderium.BridgeNH.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Internal fixed-call helper; callers supply constant API names, never user-provided Java symbols. */
public final class AdapterAccess {
    private AdapterAccess() { }
    public static Class<?> type(Object target, String name) {
        for (Class<?> current = target == null ? null : target.getClass(); current != null; current = current.getSuperclass())
            if (name.equals(current.getName())) return current;
        return null;
    }
    public static Object field(Object target, String owner, String name, boolean declared) {
        Class<?> api = type(target, owner);
        if (api == null) throw new BridgeException("unsupported", "API owner unavailable: " + owner);
        try {
            Field field = declared ? api.getDeclaredField(name) : api.getField(name);
            if (declared) field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Fixed field unavailable: " + name); }
    }
    public static Object call(Object target, String name) { return call(target, name, new Class<?>[0]); }
    public static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) return null;
        try {
            Method method = publicMethod(target.getClass(), name, types);
            if (method == null) throw new BridgeException("unsupported", "Fixed API unavailable: " + name);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Fixed API " + name + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) {
            if (error instanceof BridgeException) throw (BridgeException) error;
            throw new BridgeException("unsupported", "Fixed API unavailable: " + name);
        }
    }
    private static Method publicMethod(Class<?> type, String name, Class<?>[] arguments) {
        if (type == null) return null;
        try {
            Method method = type.getMethod(name, arguments);
            if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        } catch (NoSuchMethodException ignored) { }
        for (Class<?> iface : type.getInterfaces()) { Method method = publicMethod(iface, name, arguments); if (method != null) return method; }
        return publicMethod(type.getSuperclass(), name, arguments);
    }
    public static Object callOn(Object target, String owner, String name, Class<?>[] types, Object... args) {
        try { return invoke(Class.forName(owner, false, target.getClass().getClassLoader()), target, name, types, false, args); }
        catch (ClassNotFoundException error) { throw new BridgeException("unsupported", "Fixed API unavailable: " + owner); }
    }
    public static Object invoke(Class<?> api, Object target, String name, Class<?>[] types, boolean declared, Object... args) {
        try {
            Method method = declared ? api.getDeclaredMethod(name, types) : api.getMethod(name, types);
            if (declared) method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Fixed API " + name + " failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Fixed API unavailable: " + name); }
    }
}
