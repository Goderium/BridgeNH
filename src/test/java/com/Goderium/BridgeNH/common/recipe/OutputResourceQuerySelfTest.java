package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
import com.Goderium.BridgeNH.common.machine.MachineDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.api.AEApi;
import appeng.api.IAppEngApi;
import appeng.api.storage.IStorageHelper;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Public typed-key lookup, lossless identity, fail-closed partial results and non-mutating provider boundary. */
public final class OutputResourceQuerySelfTest {
    private static int checks;
    private static final String ITEM = "bridge_output_query_fixture:item", FLUID = "bridge_output_query_fixture.fluid";
    public static int run() {
        checks = 0;
        Item.itemRegistry.addObject(32220, ITEM, new Item());
        if (FluidRegistry.getFluid(FLUID) == null) FluidRegistry.registerFluid(new Fluid(FLUID));
        Factory factory = new Factory(); AEApi.api = new App(factory);
        try { successes(factory); failures(factory); validation(factory); }
        finally { AEApi.api = null; }
        return checks;
    }
    private static void successes(Factory factory) {
        Provider provider = new Provider(); Output output = new Output(provider, StorageChannel.ITEMS);
        JsonObject observation = owner(output); JsonObject result = read(output, request(item(7)), observation);
        JsonObject entry = entry(result, 0);
        check(result.get("schemaVersion").getAsInt() == 1 && result.get("source").getAsString().equals("public_provider_cached_amount")
                && result.get("scope").getAsString().equals("local_output_provider_cache_only") && result.get("requestedCount").getAsInt() == 1
                && result.get("complete").getAsBoolean(), "Versioned result covers exactly one requested cache identity");
        check(entry.get("available").getAsBoolean() && entry.get("queryIndex").getAsInt() == 0
                && entry.get("cachedAmount").getAsString().equals(Long.toString(Long.MAX_VALUE)), "Typed public long lookup retains Long.MAX_VALUE decimal precision");
        check(entry.getAsJsonObject("item").get("registryName").getAsString().equals(ITEM)
                && entry.getAsJsonObject("item").get("metadata").getAsInt() == 7
                && !entry.getAsJsonObject("item").get("hasTag").getAsBoolean(), "Item result exports the actual tagless round-trip identity");
        check(result.getAsJsonObject("owner").equals(observation) && provider.calls == 1 && provider.last.getStackSize() == 1
                && provider.cached == Long.MAX_VALUE, "Current owner binding and fresh unit key do not change provider contents");
        observation.addProperty("x", 999);
        check(result.getAsJsonObject("owner").get("x").getAsInt() == -260, "Changing later observation properties cannot rewrite captured owner coordinates");

        provider.cached = 0;
        result = read(output, request(item(0)), owner(output));
        check(result.get("complete").getAsBoolean() && entry(result, 0).get("cachedAmount").getAsString().equals("0"), "A real zero lookup is complete rather than unavailable");
        output.channel = StorageChannel.FLUIDS; provider.cached = 9007199254740993L;
        result = read(output, request(fluid(FLUID)), owner(output));
        check(result.get("complete").getAsBoolean() && entry(result, 0).get("cachedAmount").getAsString().equals("9007199254740993")
                && entry(result, 0).getAsJsonObject("fluid").get("fluidName").getAsString().equals(FLUID)
                && !entry(result, 0).getAsJsonObject("fluid").get("hasTag").getAsBoolean(), "Fluid identity and a quantity above JavaScript exact-integer precision remain exact");

        output.channel = StorageChannel.ITEMS; provider.cached = 42;
        result = read(output, request(item(3), fluid(FLUID)), owner(output));
        check(!result.get("complete").getAsBoolean() && result.get("requestedCount").getAsInt() == 2
                && entry(result, 0).get("available").getAsBoolean() && !entry(result, 1).get("available").getAsBoolean()
                && entry(result, 1).get("queryIndex").getAsInt() == 1, "Mixed-channel requests retain successful entries and mark the requested subset incomplete");
        check(entry(result, 1).get("reason").getAsString().equals("public_output_channel_mismatch")
                && !entry(result, 1).has("fluid") && !entry(result, 1).has("cachedAmount"), "A channel mismatch cannot manufacture a fluid identity or zero cache quantity");
        BaseMetaTileEntity tile = new BaseMetaTileEntity(); tile.meta = output;
        JsonObject current = MachineDataAdapter.read(null, tile, new JsonObject(), generousStart());
        check(!current.getAsJsonObject("outputStorage").has("resourceQuery") && current.getAsJsonObject("outputStorage").get("cachedAmount").getAsString().equals("42"),
                "Default machine diagnostics preserve aggregate output storage without optional queries");
        current = MachineDataAdapter.read(null, tile, request(item(4)), generousStart());
        JsonObject integrated = current.getAsJsonObject("outputStorage").getAsJsonObject("resourceQuery");
        check(integrated.get("complete").getAsBoolean()
                && integrated.getAsJsonObject("owner").get("metaClass").equals(current.get("metaClass"))
                && integrated.getAsJsonObject("owner").get("metaTileId").equals(current.get("metaTileId")), "Fresh machine.inspect integrates the optional result against its actual observation owner");
    }
    private static void failures(Factory factory) {
        Provider provider = new Provider(); Output output = new Output(provider, StorageChannel.ITEMS);
        negative(read(new SimilarEnvironment(), request(item(0)), owner(output)), "public_output_environment_unavailable");
        output.provider = new SimilarProvider(); negative(read(output, request(item(0)), owner(output)), "public_output_provider_unavailable");
        output.provider = provider; output.channel = null; negative(read(output, request(item(0)), owner(output)), "public_output_channel_unavailable");
        output.channel = StorageChannel.ITEMS;
        factory.mode = "null"; negative(read(output, request(item(0)), owner(output)), "public_typed_stack_unavailable");
        factory.mode = "metadata"; negative(read(output, request(item(0)), owner(output)), "public_item_roundtrip_identity_mismatch");
        factory.mode = "tag"; negative(read(output, request(item(0)), owner(output)), "public_item_roundtrip_identity_mismatch");
        factory.mode = "kind"; negative(read(output, request(item(0)), owner(output)), "public_typed_stack_kind_mismatch");
        factory.mode = "channel"; negative(read(output, request(item(0)), owner(output)), "public_output_channel_mismatch");
        factory.mode = "throw"; negative(read(output, request(item(0)), owner(output)), "public_output_query_failed");
        factory.mode = "normal";
        JsonObject unknown = item(0); unknown.addProperty("registryName", "bridge_output_query_fixture:unknown");
        negative(read(output, request(unknown), owner(output)), "item_identity_not_registered");
        output.channel = StorageChannel.FLUIDS;
        negative(read(output, request(fluid("bridge_output_query_fixture.unknown")), owner(output)), "fluid_identity_not_registered");
        factory.mode = "tag"; negative(read(output, request(fluid(FLUID)), owner(output)), "public_fluid_roundtrip_identity_mismatch");
        factory.mode = "normal"; provider.cached = -1;
        negative(read(output, request(fluid(FLUID)), owner(output)), "public_cached_amount_negative");
        provider.cached = 42; provider.fail = true;
        negative(read(output, request(fluid(FLUID)), owner(output)), "public_output_query_failed");
        provider.fail = false;
        AEApi.api = null; negative(read(output, request(fluid(FLUID)), owner(output)), "public_ae_api_unavailable"); AEApi.api = new App(factory);
        check(provider.cached == 42, "Failures and rejected conversions do not alter provider cached resources");
    }
    private static void validation(Factory factory) {
        Output output = new Output(new Provider(), StorageChannel.ITEMS);
        JsonObject empty = new JsonObject(); empty.add("outputQueries", new JsonArray()); expect(() -> read(output, empty, owner(output)));
        JsonObject nonArray = new JsonObject(); nonArray.addProperty("outputQueries", "item"); expect(() -> read(output, nonArray, owner(output)));
        JsonObject nine = request(item(0), item(1), item(2), item(3), item(4), item(5), item(6), item(7), item(8)); expect(() -> read(output, nine, owner(output)));
        for (String field : new String[] {"nbt", "hasTag", "count", "fluidName"}) {
            JsonObject extra = item(0); extra.addProperty(field, "forbidden"); expect(() -> read(output, request(extra), owner(output)));
        }
        for (int metadata : new int[] {-1, 32767}) { JsonObject invalid = item(metadata); expect(() -> read(output, request(invalid), owner(output))); }
        JsonObject fractional = item(0); fractional.addProperty("metadata", 0.5); expect(() -> read(output, request(fractional), owner(output)));
        JsonObject missing = item(0); missing.remove("metadata"); expect(() -> read(output, request(missing), owner(output)));
        JsonObject missingName = item(0); missingName.remove("registryName"); expect(() -> read(output, request(missingName), owner(output)));
        JsonObject invalidKind = item(0); invalidKind.addProperty("kind", "network"); expect(() -> read(output, request(invalidKind), owner(output)));
        expect(() -> read(output, request(fluid("")), owner(output)));
        StringBuilder longName = new StringBuilder(); for (int i = 0; i < 129; i++) longName.append('a');
        expect(() -> read(output, request(fluid(longName.toString())), owner(output)));
        check(((Provider) output.provider).calls == 0, "Malformed sealed requests reject before any provider cache lookup");
    }
    private static void negative(JsonObject result, String reason) {
        JsonObject entry = entry(result, 0);
        check(!result.get("complete").getAsBoolean() && !entry.get("available").getAsBoolean()
                && entry.get("reason").getAsString().equals(reason) && !entry.has("cachedAmount") && !entry.has("item") && !entry.has("fluid"),
                "Unavailable result is explicit without fabricated resource identity or quantity: " + reason);
    }
    private static JsonObject read(Object output, JsonObject args, JsonObject owner) { return OutputResourceQueryAdapter.read(output, args, owner); }
    private static JsonObject entry(JsonObject result, int index) { return result.getAsJsonArray("entries").get(index).getAsJsonObject(); }
    private static JsonObject request(JsonObject... queries) { JsonObject result = new JsonObject(); JsonArray values = new JsonArray(); for (JsonObject query : queries) values.add(query); result.add("outputQueries", values); return result; }
    private static JsonObject item(int metadata) { JsonObject value = new JsonObject(); value.addProperty("kind", "item"); value.addProperty("registryName", ITEM); value.addProperty("metadata", metadata); return value; }
    private static JsonObject fluid(String name) { JsonObject value = new JsonObject(); value.addProperty("kind", "fluid"); value.addProperty("fluidName", name); return value; }
    private static JsonObject owner(Object meta) {
        JsonObject result = new JsonObject(); result.addProperty("metaTileId", 84); result.addProperty("metaClass", meta.getClass().getName());
        result.addProperty("x", -260); result.addProperty("y", 119); result.addProperty("z", -120); result.addProperty("dimension", 180); return result;
    }
    private static long generousStart() { return System.nanoTime() + 60000000000L; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(Runnable action) { try { action.run(); throw new AssertionError("Expected invalid_args"); } catch (BridgeException error) { check(error.code.equals("invalid_args"), "Malformed request has invalid_args result"); } }

    public static class App implements IAppEngApi { private final Factory factory; App(Factory value) { factory = value; } public IStorageHelper storage() { return factory; } }
    public static class Factory implements IStorageHelper {
        String mode = "normal";
        public IAEItemStack createItemStack(ItemStack stack) {
            if (mode.equals("throw")) throw new IllegalStateException("fixture factory");
            if (mode.equals("null")) return null;
            if (mode.equals("metadata")) stack = new ItemStack(stack.getItem(), 1, stack.getMetadata() + 1);
            if (mode.equals("tag")) stack.setTagCompound(new NBTTagCompound());
            return new ItemKey(stack, mode);
        }
        public IAEFluidStack createFluidStack(FluidStack stack) {
            if (mode.equals("tag")) stack.tag = new NBTTagCompound();
            return new FluidKey(stack);
        }
    }
    public static class ItemKey extends AeGuiFixtures.Stack implements IAEItemStack {
        private final String mode;
        ItemKey(ItemStack physical, String mode) { super(new AeGuiFixtures.Type("item"), physical, "unit key", 1); this.mode = mode; }
        public ItemStack getItemStack() { return item.copy(); }
        @Override public Object getChannel() { return mode.equals("channel") ? StorageChannel.FLUIDS : StorageChannel.ITEMS; }
        @Override public boolean isItem() { return !mode.equals("kind"); }
        @Override public Object setStackSize(long value) { throw new AssertionError("No live typed-key setter"); }
    }
    public static class FluidKey extends AeGuiFixtures.Stack implements IAEFluidStack {
        private final FluidStack physical;
        FluidKey(FluidStack value) { super(new AeGuiFixtures.Type("fluid"), null, "unit key", 1); physical = value; }
        public FluidStack getFluidStack() { return physical.copy(); }
        @Override public Object getChannel() { return StorageChannel.FLUIDS; }
        @Override public Object setStackSize(long value) { throw new AssertionError("No live typed-key setter"); }
    }
    public static class Provider extends MTEHatchOutputMEBase {
        long cached = Long.MAX_VALUE; int calls; boolean fail; IAEStack last;
        @Override public long getCachedAmount(IAEStack key) { calls++; last = key; if (fail) throw new IllegalStateException("fixture quantity"); return cached; }
        public long getCachedAmount() { return cached; } public long getCacheCapacity() { return Long.MAX_VALUE; }
        public Object getCacheList() { throw new AssertionError("No cache enumeration"); }
        public void flushCachedStack() { throw new AssertionError("No cache flushing"); }
        public void cellToCacheTransfer() { throw new AssertionError("No cell transfer"); }
    }
    public static class Output implements MTEHatchOutputMEBase.Environment {
        Object provider; StorageChannel channel;
        Output(Object provider, StorageChannel channel) { this.provider = provider; this.channel = channel; }
        public Object getProvider() { return provider; } public Object getChannel() { return channel; }
        public Object getNetworkInvtory() { throw new AssertionError("No network inventory"); }
        public Object getCellStack() { throw new AssertionError("No inserted-cell access"); }
    }
    public static class SimilarProvider { public long getCachedAmount(IAEStack key) { throw new AssertionError("Lookalike provider getter must not be invoked"); } }
    public static class SimilarEnvironment {
        public Object getProvider() { throw new AssertionError("Lookalike environment getter must not be invoked"); }
        public Object getChannel() { return StorageChannel.ITEMS; }
    }
}
