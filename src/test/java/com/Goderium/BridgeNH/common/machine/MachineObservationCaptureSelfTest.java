package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Real native NBT codec and retained-page checks with a headless world clock; no live game acceptance. */
public final class MachineObservationCaptureSelfTest {
    private static int checks;

    public static int run() throws Exception {
        checks = 0;
        Class<?> captureClass = Class.forName(MachineObservationCapture.class.getName() + "$Capture");
        Constructor<?> constructor = captureClass.getDeclaredConstructor(World.class, String.class, String.class);
        constructor.setAccessible(true);
        LoadedWorld world = allocate(LoadedWorld.class);
        Field provider = World.class.getDeclaredField("provider"); provider.setAccessible(true);
        WorldProviderSurface dimension = new WorldProviderSurface(); dimension.dimensionId = 180; provider.set(world, dimension);
        Object capture = constructor.newInstance(world, "session", "Debug");
        Method flatten = MachineObservationCapture.class.getDeclaredMethod("flatten", captureClass, int.class, JsonArray.class, NBTBase.class, int.class);
        flatten.setAccessible(true);
        NBTTagCompound original = new NBTTagCompound();
        original.setLong("long", Long.MAX_VALUE); original.setByte("byte", (byte) -128);
        original.setIntArray("ints", new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE});
        byte[] bytes = new byte[129]; bytes[128] = -1; original.setByteArray("bytes", bytes);
        NBTTagList list = new NBTTagList(); list.appendTag(new NBTTagString("first")); list.appendTag(new NBTTagString("second"));
        original.setTag("list", list); original.setTag("empty", new NBTTagCompound());
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 4095; i++) text.append('x');
        text.append("\uD83D\uDE00tail"); original.setString("text", text.toString());
        String before = original.toString();
        flatten.invoke(null, capture, 0, new JsonArray(), original.copy(), 0);
        check(before.equals(original.toString()) && list.tagCount() == 2, "Detached flatten must never remove or modify live child NBT");
        Field entriesField = captureClass.getDeclaredField("entries"); entriesField.setAccessible(true);
        List<JsonObject> entries = (List<JsonObject>) entriesField.get(capture);
        StringBuilder restored = new StringBuilder(); int chunks = 0; boolean exactLong = false, arrayEnd = false, second = false;
        for (JsonObject row : entries) {
            String path = row.getAsJsonArray("path").toString();
            if (path.equals("[\"long\"]")) exactLong = row.get("nbtType").getAsInt() == 4 && row.get("valueSnbt").getAsString().equals(Long.MAX_VALUE + "L");
            if (path.equals("[\"bytes\"]") && row.has("arrayOffset") && row.get("arrayOffset").getAsInt() == 128)
                arrayEnd = row.getAsJsonArray("values").size() == 1 && row.getAsJsonArray("values").get(0).getAsInt() == -1;
            if (path.equals("[\"list\",1]")) second = row.get("value").getAsString().equals("second");
            if (path.equals("[\"text\"]")) {
                check(row.get("stringOffset").getAsInt() == restored.length(), "String chunks retain native UTF16 offsets");
                String part = row.get("value").getAsString();
                check(part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)), "No split surrogate pair");
                restored.append(part); chunks++;
            }
        }
        check(exactLong && arrayEnd && second, "Exact signed long, byte chunks and native list indexes survive flattening");
        check(chunks == 2 && restored.toString().equals(text.toString()), "Long Unicode string reconstructs exactly");
        Field tick = captureClass.getDeclaredField("endTick"); tick.setAccessible(true); tick.setLong(capture, 77);
        Field targets = captureClass.getDeclaredField("targets"); targets.setAccessible(true);
        ((JsonArray) targets.get(capture)).add(new JsonObject());
        MachineObservationCapture adapter = new MachineObservationCapture();
        Field store = MachineObservationCapture.class.getDeclaredField("captures"); store.setAccessible(true);
        Field id = captureClass.getDeclaredField("id"); id.setAccessible(true); String captureId = (String) id.get(capture);
        ((Map<String, Object>) store.get(adapter)).put(captureId, capture);
        JsonObject args = new JsonObject(); args.addProperty("captureId", captureId); args.addProperty("limit", 1);
        JsonObject first = adapter.read(world, "session", "Debug", args);
        check(first.get("worldTick").getAsLong() == 77 && first.get("hasMore").getAsBoolean(), "Retained page publishes frozen tick and continuation");
        first.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("tampered", true);
        JsonObject reread = adapter.read(world, "session", "Debug", args);
        check(!reread.getAsJsonArray("entries").get(0).getAsJsonObject().has("tampered"), "Returned JSON cannot mutate retained capture");
        args.addProperty("offset", entries.size() + 1);
        try { adapter.read(world, "session", "Debug", args); throw new AssertionError("Bad offset accepted"); }
        catch (BridgeException expected) { check(expected.code.equals("invalid_args"), "Bad continuation rejected"); }
        args.remove("offset");
        try { adapter.read(world, "other-session", "Debug", args); throw new AssertionError("Foreign capture accepted"); }
        catch (BridgeException expected) { check(expected.code.equals("stale_capture"), "Capture ownership retained"); }
        Field created = captureClass.getDeclaredField("created"); created.setAccessible(true);
        created.setLong(capture, System.nanoTime() - 31000000000L); adapter.expire();
        check(((Map<?, ?>) store.get(adapter)).isEmpty(), "TTL frees retained values without another read");
        fluids();
        return checks;
    }

    public static final class PublicFluids {
        final FluidStack[] fluids;
        PublicFluids(FluidStack[] fluids) { this.fluids = fluids; }
        public FluidStack[] getFluidList() { return fluids; }
    }

    private static void fluids() {
        Fluid a = new Fluid("bridge_capture_a"), b = new Fluid("bridge_capture_b");
        FluidRegistry.registerFluid(a); FluidRegistry.registerFluid(b);
        PublicFluids fixture = new PublicFluids(new FluidStack[] {new FluidStack(a, 1000), null, new FluidStack(b, 2000)});
        JsonObject args = new JsonObject(); args.addProperty("inputLimit", 2);
        JsonObject first = MachineDataAdapter.fluidInputs(fixture, args);
        check(first.get("available").getAsBoolean() && first.get("configuredFluids").getAsInt() == 2
                && first.get("totalSlots").getAsInt() == 3 && first.get("hasMore").getAsBoolean(), "Public input enumerates all templates including later fluids");
        check(first.getAsJsonArray("entries").get(1).getAsJsonObject().get("fluid").isJsonNull(), "Sparse slot is retained");
        args.addProperty("inputOffset", 2); JsonObject last = MachineDataAdapter.fluidInputs(fixture, args);
        check(last.getAsJsonArray("entries").get(0).getAsJsonObject().get("index").getAsInt() == 2
                && !last.get("hasMore").getAsBoolean(), "Continuation keeps original slot index");
        check(!MachineDataAdapter.fluidInputs(new Object(), new JsonObject()).get("available").getAsBoolean(), "Unavailable public API has no first-fluid fallback");
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), type));
    }

    private static final class LoadedWorld extends World {
        private LoadedWorld() { super(null, "capture", (WorldSettings) null, null, null); }
        @Override public long getTotalWorldTime() { return 77; }
        @Override protected IChunkProvider createChunkProvider() { throw new AssertionError("No chunk creation"); }
        @Override protected int getRenderDistanceChunks() { throw new AssertionError("No render"); }
        @Override public Entity getEntityByID(int id) { throw new AssertionError("No entity query"); }
    }

    private static void check(boolean passed, String message) { if (!passed) throw new AssertionError(message); checks++; }
}
