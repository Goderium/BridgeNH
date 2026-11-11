package com.Goderium.BridgeNH.common.inventory;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Enumeration;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Real unchanged Forge registry bytecode and adapter, with headless static-initialization fixtures. */
final class NativeFluidRegistrySelfTest {
    private static final String REGISTRY = "net.minecraftforge.fluids.FluidContainerRegistry";
    private static final String ADAPTER = "com.Goderium.BridgeNH.common.inventory.FluidContainerDataAdapter";
    private static int checks;

    static int run() {
        checks = 0;
        try {
            ClassLoader parent = NativeFluidRegistrySelfTest.class.getClassLoader();
            String resource = REGISTRY.replace('.', '/') + ".class";
            Enumeration<URL> candidates = parent.getResources(resource); URL nativeClass = null;
            while (candidates.hasMoreElements()) {
                URL candidate = candidates.nextElement();
                if (candidate.openConnection() instanceof JarURLConnection || candidate.toExternalForm().contains("/patchedMc/")) {
                    check(nativeClass == null, "Only one actual Forge registry artifact may back the native regression");
                    nativeClass = candidate;
                }
            }
            check(nativeClass != null, "Native Forge registry bytes are required, never substituted by the test mock");
            URL nativeRoot = new URL(nativeClass.toExternalForm().substring(0, nativeClass.toExternalForm().length() - resource.length()));
            NativeLoader loader = new NativeLoader(parent, nativeRoot);
            Class<?> registry = loader.loadClass(REGISTRY), adapter = loader.loadClass(ADAPTER);
            check(registry != net.minecraftforge.fluids.FluidContainerRegistry.class && registry.getClassLoader() == loader,
                    "Public registrations and capacity queries execute the actual Forge registry class, not the mock");
            check(adapter != FluidContainerDataAdapter.class && adapter.getClassLoader() == loader,
                    "The current production adapter links directly against the actual Forge registry in the isolated fixture");
            Method register = registry.getMethod("registerFluidContainer", FluidStack.class, ItemStack.class, ItemStack.class);
            Method capacity = registry.getMethod("getContainerCapacity", FluidStack.class, ItemStack.class);
            Method read = adapter.getMethod("read", ItemStack.class, String.class);
            String name = "bridge_native_registry_fixture.fluid", otherName = "bridge_native_registry_fixture.other";
            Fluid first = registered(name), other = registered(otherName), absent = registered("bridge_native_registry_fixture.no_tuple");
            Item item = new Item(); ItemStack empty = new ItemStack(item, 64, 13);
            NBTTagCompound original = new NBTTagCompound(), nested = new NBTTagCompound();
            nested.setString("marker", "original"); original.setTag("nested", nested); empty.setTagCompound(original);
            NBTTagCompound expected = (NBTTagCompound) original.copy();
            ItemStack filledFirst = new ItemStack(item, 1, 11), filledOther = new ItemStack(item, 1, 12);
            check((Boolean) register.invoke(null, new FluidStack(first, 4321), filledFirst, empty), "Actual public registry accepts the first controlled tuple");
            check((Boolean) register.invoke(null, new FluidStack(other, 8765), filledOther, empty), "The same actual empty item accepts a different-fluid, different-capacity tuple");
            check((Integer) capacity.invoke(null, new FluidStack(first, 1), empty) == 4321
                    && (Integer) capacity.invoke(null, new FluidStack(other, 1), empty) == 8765,
                    "Native two-argument public capacity reads return exact distinct per-fluid amounts");
            check((Integer) registry.getMethod("getContainerCapacity", ItemStack.class).invoke(null, empty) == 0,
                    "The real one-argument getter supplies no arbitrary capacity for an empty item");
            JsonObject firstRead = invoke(read, empty, name), otherRead = invoke(read, empty, otherName);
            requested(firstRead, name, 4321); requested(otherRead, otherName, 8765);
            check(empty.stackSize == 64 && empty.getMetadata() == 13 && empty.getTagCompound().equals(expected)
                    && !firstRead.get("probeMutatedCopy").getAsBoolean(), "Native registration query preserves original count, metadata and nested tag");
            unavailable(invoke(read, empty, null), "registered_empty_capacity_requires_fluid_identity");
            unavailable(invoke(read, empty, name + " "), "requested_container_fluid_unknown");
            JsonObject zero = invoke(read, empty, absent.getName());
            unavailable(zero, "registered_empty_capacity_unavailable_for_fluid");
            check(zero.getAsJsonObject("capacity").get("fluidName").getAsString().equals(absent.getName()),
                    "A resolved fluid without a native empty tuple retains its exact identity and no numeric capacity");
            JsonObject filled = invoke(read, filledFirst, otherName);
            check(!filled.get("empty").getAsBoolean() && filled.getAsJsonObject("fluid").get("fluidName").getAsString().equals(name)
                    && filled.getAsJsonObject("capacity").get("value").getAsInt() == 4321
                    && !filled.getAsJsonObject("capacity").has("fluidName") && !filled.getAsJsonObject("capacity").has("source"),
                    "The actual filled native tuple cannot be replaced by a requested fluid");
            check(!invoke(read, new ItemStack(new Item(), 1, 13), name).get("available").getAsBoolean()
                    && !invoke(read, new ItemStack(item, 1, 14), name).get("available").getAsBoolean(),
                    "Native registry identity checks require the same actual item and metadata");
            Fluid one = registered("q"); char[] chars = new char[128]; java.util.Arrays.fill(chars, 'z');
            Fluid longest = registered(new String(chars));
            check((Boolean) register.invoke(null, new FluidStack(one, 19), new ItemStack(item, 1, 15), empty)
                    && (Boolean) register.invoke(null, new FluidStack(longest, Integer.MAX_VALUE), new ItemStack(item, 1, 16), empty),
                    "Native public registry accepts exact names at both query length boundaries");
            requested(invoke(read, empty, one.getName()), one.getName(), 19);
            requested(invoke(read, empty, longest.getName()), longest.getName(), Integer.MAX_VALUE);
            JsonObject fixture = new JsonObject(); fixture.add("first", firstRead); fixture.add("other", otherRead);
            fixture.add("zeroTuple", zero); fixture.add("filledUnchanged", filled);
            fixture.addProperty("registryClassSha256", sha256(bytes(nativeClass)));
            fixture.addProperty("registryClassResource", nativeClass.toString());
            fixture.addProperty("registryClassUnmodified", true);
            fixture.addProperty("environment", "headless Items/FluidRegistry/MinecraftForge initialization fixtures; no client or server acceptance");
            System.out.println("EMPTY_CAPACITY_NATIVE_FIXTURE_JSON " + fixture);
            System.out.println("Native Forge fluid registry regression passed: " + checks + " assertions; registry class bytes unchanged, environment headless.");
            return checks;
        } catch (Exception error) { throw new AssertionError("Actual Forge fluid registry regression failed", error); }
    }

    private static JsonObject invoke(Method read, ItemStack stack, String name) throws Exception {
        return (JsonObject) read.invoke(null, stack, name);
    }
    private static Fluid registered(String name) {
        if (FluidRegistry.getFluid(name) == null) FluidRegistry.registerFluid(new Fluid(name));
        return FluidRegistry.getFluid(name);
    }
    private static void requested(JsonObject result, String name, int value) {
        JsonObject capacity = result.getAsJsonObject("capacity");
        check(result.get("available").getAsBoolean() && result.get("empty").getAsBoolean() && result.get("fluid").isJsonNull()
                && capacity.get("available").getAsBoolean() && capacity.get("value").getAsInt() == value
                && capacity.get("fluidName").getAsString().equals(name)
                && capacity.get("source").getAsString().equals("public_fluid_container_registry_getContainerCapacity"),
                "Native adapter returns exact requested registration evidence with actual contents still empty");
    }
    private static void unavailable(JsonObject result, String reason) {
        JsonObject capacity = result.getAsJsonObject("capacity");
        check(result.get("available").getAsBoolean() && result.get("empty").getAsBoolean() && result.get("fluid").isJsonNull()
                && !capacity.get("available").getAsBoolean() && !capacity.has("value") && capacity.get("reason").getAsString().equals(reason),
                "Native query explicitly preserves empty state without a known capacity: " + reason);
    }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static byte[] bytes(URL url) throws Exception {
        try (InputStream input = url.openStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }
    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x", value & 255));
        return text.toString();
    }
    private static final class NativeLoader extends ClassLoader {
        final URL nativeRoot;
        NativeLoader(ClassLoader parent, URL nativeRoot) { super(parent); this.nativeRoot = nativeRoot; }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            boolean nativeRegistry = name.equals(REGISTRY) || name.startsWith(REGISTRY + "$"),
                    bridge = name.equals(ADAPTER) || name.startsWith(ADAPTER + "$")
                            || name.equals("com.Goderium.BridgeNH.util.BoundedNbt") || name.startsWith("com.Goderium.BridgeNH.util.BoundedNbt$");
            if (!nativeRegistry && !bridge) return super.loadClass(name, resolve);
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    String path = name.replace('.', '/') + ".class";
                    URL resource = nativeRegistry ? new URL(nativeRoot, path) : getParent().getResource(path);
                    byte[] bytes = bytes(resource); loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (Exception error) { throw new ClassNotFoundException(name, error); }
            }
            if (resolve) resolveClass(loaded);
            return loaded;
        }
    }
}
