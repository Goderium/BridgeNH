package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;

/** Incremental geometry only: no descriptions, world observations, candidate getters or element checks. */
final class PieceBounds {
    final Object definition;
    final Object[] elements;
    final String piece;
    final String geometryContext;
    long lastAccess;
    static final int NAVIGATION_CACHE_LIMIT = 256;
    private final IdentityHashMap<Object, Navigation> navigationCache = new IdentityHashMap<Object, Navigation>();
    private final IdentityHashMap<Class<?>, Boolean> memoEligibility = new IdentityHashMap<Class<?>, Boolean>();
    private final Class<?>[] nativeNavigationTypes;
    private final Method navigating, resetA, resetB, resetC, stepA, stepB, stepC;
    private final long[] cursor = new long[3], minimum = new long[3], maximum = new long[3];
    private int scanned, physical;
    private String error;
    private long getterCalls;
    private boolean allNavigationFixed = true;

    PieceBounds(Object definition, String piece, Object[] elements, Class<?> api) {
        this(definition, piece, elements, api, "");
    }

    PieceBounds(Object definition, String piece, Object[] elements, Class<?> api, String geometryContext) {
        this.definition = definition; this.piece = piece; this.elements = elements;
        this.geometryContext = geometryContext;
        navigating = method(api, "isNavigating"); resetA = method(api, "resetA"); resetB = method(api, "resetB"); resetC = method(api, "resetC");
        stepA = method(api, "getStepA"); stepB = method(api, "getStepB"); stepC = method(api, "getStepC");
        nativeNavigationTypes = fixedNativeNavigationTypes(api);
    }

    void advance(long started) {
        if (error != null) return;
        int visited = 0;
        try {
            while (scanned < elements.length && visited < StructureAdapter.GEOMETRY_SCAN_LIMIT) {
                if (System.nanoTime() - started >= StructureAdapter.SOFT_BUDGET_NANOS) break;
                Object element = elements[scanned];
                Navigation navigation = navigationCache.get(element);
                if (navigation == null) {
                    getterCalls++;
                    if ((Boolean) call(navigating, element)) {
                        getterCalls += 6;
                        navigation = new Navigation(true, (Boolean) call(resetA, element), (Boolean) call(resetB, element), (Boolean) call(resetC, element),
                                ((Number) call(stepA, element)).intValue(), ((Number) call(stepB, element)).intValue(), ((Number) call(stepC, element)).intValue());
                    } else navigation = Navigation.PHYSICAL;
                    boolean fixed = immutableNavigation(element);
                    if (!fixed) allNavigationFixed = false;
                    if (navigationCache.size() < NAVIGATION_CACHE_LIMIT && fixed) navigationCache.put(element, navigation);
                }
                if (navigation.navigating) {
                    long a = (navigation.resetA ? 0 : cursor[0]) + navigation.stepA;
                    long b = (navigation.resetB ? 0 : cursor[1]) + navigation.stepB;
                    long c = (navigation.resetC ? 0 : cursor[2]) + navigation.stepC;
                    cursor[0] = a; cursor[1] = b; cursor[2] = c;
                } else {
                    for (int axis = 0; axis < 3; axis++) {
                        if (physical == 0 || cursor[axis] < minimum[axis]) minimum[axis] = cursor[axis];
                        if (physical == 0 || cursor[axis] > maximum[axis]) maximum[axis] = cursor[axis];
                    }
                    physical++;
                    cursor[0]++;
                }
                scanned++; visited++;
            }
        } catch (RuntimeException | LinkageError failure) { error = failure.getClass().getSimpleName() + ": " + JsonData.bounded(failure.getMessage(), 256); }
    }

    JsonObject describe(int[] offsets, int x, int y, int z, Transform transform) {
        JsonObject result = new JsonObject(); boolean complete = error == null && scanned == elements.length;
        result.addProperty("available", error == null); result.addProperty("complete", complete); result.addProperty("partial", !complete);
        result.addProperty("scannedEntries", scanned); result.addProperty("compiledEntries", elements.length); result.addProperty("physicalPositions", physical);
        result.addProperty("navigationTuplesCached", navigationCache.size()); result.addProperty("navigationTupleLimit", NAVIGATION_CACHE_LIMIT);
        result.addProperty("navigationGetterCalls", getterCalls); result.addProperty("auxiliaryByteEstimate", auxiliaryBytes());
        result.addProperty("navigationSnapshotStable", complete && allNavigationFixed); result.addProperty("completeSummaryReusable", complete && allNavigationFixed);
        result.addProperty("dynamicNavigationCoverage", allNavigationFixed ? "Only audited fixed navigation has been observed." : "Unknown overridden navigation getters are read at every occurrence; traversal is non-atomic and a completed summary is freshly rescanned on the next query.");
        result.addProperty("navigationMemoScope", "Only inherited IStructureElement.isNavigating default false or the exact three audited immutable classes returned by native StructureUtility.step are memoized. Unknown/dynamic overriding getters are called for every compiled occurrence; no world, candidate or validation state is cached.");
        result.addProperty("source", "public_compiled_piece_navigation");
        result.addProperty("scope", "All compiled non-navigation positions, independent of query and position pagination; skipped spaces and controller markers, original padding and dynamic piece instances are unavailable.");
        result.addProperty("fullOriginalShapeBoundsAvailable", false);
        result.addProperty("controllerOffsetsAvailable", offsets != null);
        result.addProperty("worldBoundsAvailable", false);
        if (error != null) { result.addProperty("reason", "public_navigation_failed"); result.addProperty("error", error); }
        if (physical == 0) {
            result.add("originABC", JsonNull.INSTANCE); result.add("controllerRelativeABC", JsonNull.INSTANCE); result.add("worldXYZ", JsonNull.INSTANCE);
            return result;
        }
        result.add("originABC", box(minimum, maximum, new String[] {"a", "b", "c"}));
        if (offsets == null) {
            result.add("controllerRelativeABC", JsonNull.INSTANCE); result.add("worldXYZ", JsonNull.INSTANCE);
            result.addProperty("worldBoundsUnavailableReason", "explicit_controller_offsets_required");
            return result;
        }
        long[] relativeMin = new long[3], relativeMax = new long[3];
        for (int axis = 0; axis < 3; axis++) { relativeMin[axis] = minimum[axis] - offsets[axis]; relativeMax[axis] = maximum[axis] - offsets[axis]; }
        result.add("controllerRelativeABC", box(relativeMin, relativeMax, new String[] {"a", "b", "c"}));
        if (transform == null) { result.add("worldXYZ", JsonNull.INSTANCE); result.addProperty("worldBoundsUnavailableReason", "public_alignment_unavailable"); return result; }
        for (int axis = 0; axis < 3; axis++) if (relativeMin[axis] < Integer.MIN_VALUE || relativeMax[axis] > Integer.MAX_VALUE) {
            result.add("worldXYZ", JsonNull.INSTANCE); result.addProperty("worldBoundsUnavailableReason", "coordinates_exceed_public_transform_range"); return result;
        }
        long[] worldMin = new long[3], worldMax = new long[3]; int[] controller = {x, y, z};
        try {
            for (int corner = 0; corner < 8; corner++) {
                int[] abc = new int[3]; for (int axis = 0; axis < 3; axis++) abc[axis] = (int) ((corner & (1 << axis)) == 0 ? relativeMin[axis] : relativeMax[axis]);
                int[] xyz = transform.apply(abc);
                if (xyz == null || xyz.length != 3) throw new BridgeException("unsupported", "Public alignment returned no XYZ coordinates.");
                for (int axis = 0; axis < 3; axis++) {
                    long value = (long) controller[axis] + xyz[axis];
                    if (corner == 0 || value < worldMin[axis]) worldMin[axis] = value;
                    if (corner == 0 || value > worldMax[axis]) worldMax[axis] = value;
                }
            }
            result.add("worldXYZ", box(worldMin, worldMax, new String[] {"x", "y", "z"})); result.addProperty("worldBoundsAvailable", true);
        } catch (RuntimeException | LinkageError failure) {
            result.add("worldXYZ", JsonNull.INSTANCE); result.addProperty("worldBoundsUnavailableReason", "public_alignment_failed");
        }
        return result;
    }

    private static JsonObject box(long[] min, long[] max, String[] axes) {
        JsonObject result = new JsonObject(), low = new JsonObject(), high = new JsonObject(), size = new JsonObject();
        for (int axis = 0; axis < 3; axis++) { low.addProperty(axes[axis], min[axis]); high.addProperty(axes[axis], max[axis]); size.addProperty(axes[axis], max[axis] - min[axis] + 1); }
        result.add("min", low); result.add("max", high); result.add("size", size); return result;
    }

    private static Method method(Class<?> api, String name) {
        try { Method method = api.getMethod(name); if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) throw new NoSuchMethodException(name); return method; }
        catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Public compiled navigation API is unavailable: " + name); }
    }
    private static Object call(Method method, Object target) {
        try { return method.invoke(target); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Public navigation getter failed: " + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException error) { throw new BridgeException("unsupported", "Public navigation getter unavailable: " + method.getName()); }
    }
    interface Transform { int[] apply(int[] abc); }

    long auxiliaryBytes() { return 512L + navigationCache.size() * 96L + memoEligibility.size() * 64L; }
    boolean complete() { return error == null && scanned == elements.length; }
    boolean reusable() { return allNavigationFixed; }

    private boolean immutableNavigation(Object element) {
        Class<?> type = element.getClass(); Boolean cached = memoEligibility.get(type);
        if (cached != null) return cached;
        boolean fixed = false;
        try { fixed = type.getMethod("isNavigating").getDeclaringClass() == navigating.getDeclaringClass() && navigating.isDefault(); }
        catch (ReflectiveOperationException | LinkageError ignored) { }
        for (Class<?> nativeType : nativeNavigationTypes) if (type == nativeType) fixed = true;
        if (memoEligibility.size() < 64) memoEligibility.put(type, fixed);
        return fixed;
    }

    /** StructureLib 1.4.45 sources: stepA/B/C anonymous classes capture only final primitive offsets and constant resets. */
    private static Class<?>[] fixedNativeNavigationTypes(Class<?> api) {
        try {
            Class<?> utility = Class.forName("com.gtnewhorizon.structurelib.structure.StructureUtility", false, api.getClassLoader());
            Method step = utility.getMethod("step", int.class, int.class, int.class);
            if (!Modifier.isPublic(utility.getModifiers()) || !Modifier.isStatic(step.getModifiers())) return new Class<?>[0];
            return new Class<?>[] {step.invoke(null, 1, 0, 0).getClass(), step.invoke(null, 0, 1, 0).getClass(), step.invoke(null, 0, 0, 1).getClass()};
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return new Class<?>[0]; }
    }

    private static final class Navigation {
        static final Navigation PHYSICAL = new Navigation(false, false, false, false, 0, 0, 0);
        final boolean navigating, resetA, resetB, resetC;
        final int stepA, stepB, stepC;
        Navigation(boolean navigating, boolean resetA, boolean resetB, boolean resetC, int stepA, int stepB, int stepC) {
            this.navigating = navigating; this.resetA = resetA; this.resetB = resetB; this.resetC = resetC;
            this.stepA = stepA; this.stepB = stepB; this.stepC = stepC;
        }
    }
}
