package com.Goderium.BridgeNH.common.recipe;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/** Read-only public provider lookups for explicitly requested untagged resource identities. */
public final class OutputResourceQueryAdapter {
    private static final String PROVIDER = "gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase";
    private static final String ENVIRONMENT = PROVIDER + "$Environment";
    private static final String STACK = "appeng.api.storage.data.IAEStack";
    private static final String ITEM_STACK = "appeng.api.storage.data.IAEItemStack";
    private static final String FLUID_STACK = "appeng.api.storage.data.IAEFluidStack";

    public static JsonObject read(Object meta, JsonObject args, JsonObject observation) {
        Query[] queries = parse(args);
        JsonObject result = new JsonObject(); result.addProperty("schemaVersion", 1);
        result.addProperty("source", "public_provider_cached_amount");
        result.addProperty("scope", "local_output_provider_cache_only");
        result.addProperty("requestedCount", queries.length);
        JsonObject owner = new JsonObject();
        for (String key : new String[] {"metaTileId", "metaClass", "x", "y", "z", "dimension"})
            if (observation.has(key)) owner.add(key, observation.get(key));
        result.add("owner", owner);
        Api api = null; String apiFailure = null;
        try { api = new Api(meta); }
        catch (Unavailable error) { apiFailure = error.reason; }
        catch (RuntimeException | LinkageError error) { apiFailure = "public_output_query_api_unavailable"; }
        JsonArray entries = new JsonArray(); boolean complete = true;
        for (int i = 0; i < queries.length; i++) {
            Query query = queries[i]; JsonObject entry = new JsonObject();
            entry.addProperty("queryIndex", i); entry.addProperty("kind", query.kind); entry.addProperty("available", false);
            try {
                if (apiFailure != null) throw new Unavailable(apiFailure);
                JsonObject identity = new JsonObject(); Object key = api.key(query, identity);
                Object quantity = AdapterAccess.invoke(api.providerType, api.provider, "getCachedAmount", new Class<?>[] {api.stackType}, false, key);
                if (!(quantity instanceof Long)) throw new Unavailable("public_cached_amount_not_long");
                long amount = (Long) quantity;
                if (amount < 0) throw new Unavailable("public_cached_amount_negative");
                entry.addProperty("available", true); entry.add(query.kind, identity);
                entry.addProperty("cachedAmount", Long.toString(amount));
            } catch (Unavailable error) { entry.addProperty("reason", error.reason); complete = false; }
            catch (RuntimeException | LinkageError error) { entry.addProperty("reason", "public_output_query_failed"); complete = false; }
            entries.add(entry);
        }
        result.addProperty("complete", complete); result.add("entries", entries);
        return result;
    }

    private static Query[] parse(JsonObject args) {
        JsonElement requested = args.get("outputQueries");
        if (requested == null || !requested.isJsonArray() || requested.getAsJsonArray().size() < 1 || requested.getAsJsonArray().size() > 8)
            throw new BridgeException("invalid_args", "outputQueries must contain 1 to 8 resource identity objects.");
        JsonArray values = requested.getAsJsonArray(); Query[] queries = new Query[values.size()];
        for (int i = 0; i < values.size(); i++) {
            if (!values.get(i).isJsonObject()) throw new BridgeException("invalid_args", "Each output query must be an identity object.");
            JsonObject value = values.get(i).getAsJsonObject(); String kind = JsonData.string(value, "kind");
            if ("item".equals(kind)) {
                keys(value, "kind", "registryName", "metadata");
                queries[i] = new Query(kind, text(value, "registryName", 256), JsonData.integer(value, "metadata", 0, 32766));
            } else if ("fluid".equals(kind)) {
                keys(value, "kind", "fluidName"); queries[i] = new Query(kind, text(value, "fluidName", 128), 0);
            } else throw new BridgeException("invalid_args", "Output query kind must be item or fluid.");
        }
        return queries;
    }

    private static String text(JsonObject value, String key, int limit) {
        String name = JsonData.string(value, key);
        if (name.isEmpty() || name.length() > limit) throw new BridgeException("invalid_args", key + " is outside its bounded identity length.");
        return name;
    }
    private static void keys(JsonObject value, String... allowed) {
        for (Map.Entry<String, JsonElement> entry : value.entrySet()) {
            boolean known = false;
            for (String key : allowed) if (key.equals(entry.getKey())) { known = true; break; }
            if (!known) throw new BridgeException("invalid_args", "Unexpected output query field: " + JsonData.bounded(entry.getKey(), 64));
        }
    }
    private static final class Query {
        final String kind, name; final int metadata;
        Query(String kind, String name, int metadata) { this.kind = kind; this.name = name; this.metadata = metadata; }
    }
    private static final class Unavailable extends RuntimeException {
        final String reason; Unavailable(String reason) { this.reason = reason; }
    }

    private static final class Api {
        final Class<?> environmentType, providerType, stackType, itemType, fluidType, storageType, channelType;
        final Object provider, storage, channel;
        Api(Object meta) {
            if (meta == null) throw new Unavailable("public_output_environment_unavailable");
            ClassLoader loader = meta.getClass().getClassLoader();
            environmentType = load(ENVIRONMENT, loader); providerType = load(PROVIDER, loader); stackType = load(STACK, loader);
            itemType = load(ITEM_STACK, loader); fluidType = load(FLUID_STACK, loader);
            storageType = load("appeng.api.storage.IStorageHelper", loader); channelType = load("appeng.api.storage.StorageChannel", loader);
            if (!environmentType.isInstance(meta)) throw new Unavailable("public_output_environment_unavailable");
            provider = AdapterAccess.invoke(environmentType, meta, "getProvider", new Class<?>[0], false);
            if (!providerType.isInstance(provider)) throw new Unavailable("public_output_provider_unavailable");
            channel = AdapterAccess.invoke(environmentType, meta, "getChannel", new Class<?>[0], false);
            if (!channelType.isInstance(channel)) throw new Unavailable("public_output_channel_unavailable");
            Class<?> appType = load("appeng.api.IAppEngApi", loader);
            Object app = AdapterAccess.invoke(load("appeng.api.AEApi", loader), null, "instance", new Class<?>[0], false);
            if (!appType.isInstance(app)) throw new Unavailable("public_ae_api_unavailable");
            storage = AdapterAccess.invoke(appType, app, "storage", new Class<?>[0], false);
            if (!storageType.isInstance(storage)) throw new Unavailable("public_ae_storage_api_unavailable");
        }
        Object key(Query query, JsonObject identity) {
            Object key;
            if ("item".equals(query.kind)) {
                Object registered = Item.itemRegistry.getObject(query.name);
                if (!(registered instanceof Item) || !query.name.equals(Item.itemRegistry.getNameForObject(registered)))
                    throw new Unavailable("item_identity_not_registered");
                ItemStack physical = new ItemStack((Item) registered, 1, query.metadata);
                key = AdapterAccess.invoke(storageType, storage, "createItemStack", new Class<?>[] {ItemStack.class}, false, physical.copy());
                member(key, itemType, true);
                Object value = AdapterAccess.invoke(itemType, key, "getItemStack", new Class<?>[0], false);
                if (!(value instanceof ItemStack)) throw new Unavailable("public_item_roundtrip_unavailable");
                ItemStack roundtrip = (ItemStack) value;
                if (physical.hasTagCompound() || physical.stackSize != 1 || physical.getMetadata() != query.metadata
                        || roundtrip.getItem() != physical.getItem() || roundtrip.getMetadata() != query.metadata
                        || roundtrip.hasTagCompound() || !query.name.equals(Item.itemRegistry.getNameForObject(roundtrip.getItem())))
                    throw new Unavailable("public_item_roundtrip_identity_mismatch");
                identity.addProperty("registryName", (String) Item.itemRegistry.getNameForObject(roundtrip.getItem()));
                identity.addProperty("metadata", roundtrip.getMetadata());
            } else {
                Fluid registered = FluidRegistry.getFluid(query.name);
                if (registered == null || !query.name.equals(registered.getName())) throw new Unavailable("fluid_identity_not_registered");
                FluidStack physical = new FluidStack(registered, 1);
                key = AdapterAccess.invoke(storageType, storage, "createFluidStack", new Class<?>[] {FluidStack.class}, false, physical.copy());
                member(key, fluidType, false);
                Object value = AdapterAccess.invoke(fluidType, key, "getFluidStack", new Class<?>[0], false);
                if (!(value instanceof FluidStack)) throw new Unavailable("public_fluid_roundtrip_unavailable");
                FluidStack roundtrip = (FluidStack) value;
                if (physical.tag != null || physical.amount != 1 || roundtrip.getFluid() != registered || roundtrip.tag != null
                        || !query.name.equals(roundtrip.getFluid().getName()) || FluidRegistry.getFluid(query.name) != roundtrip.getFluid())
                    throw new Unavailable("public_fluid_roundtrip_identity_mismatch");
                identity.addProperty("fluidName", roundtrip.getFluid().getName());
            }
            identity.addProperty("hasTag", false);
            return key;
        }
        private void member(Object key, Class<?> typed, boolean item) {
            if (!stackType.isInstance(key) || !typed.isInstance(key)) throw new Unavailable("public_typed_stack_unavailable");
            Object isItem = AdapterAccess.invoke(stackType, key, "isItem", new Class<?>[0], false);
            Object isFluid = AdapterAccess.invoke(stackType, key, "isFluid", new Class<?>[0], false);
            if (!Boolean.valueOf(item).equals(isItem) || !Boolean.valueOf(!item).equals(isFluid)) throw new Unavailable("public_typed_stack_kind_mismatch");
            Object keyChannel = AdapterAccess.invoke(stackType, key, "getChannel", new Class<?>[0], false);
            if (!channelType.isInstance(keyChannel) || keyChannel != channel) throw new Unavailable("public_output_channel_mismatch");
        }
        private static Class<?> load(String name, ClassLoader loader) {
            try { return Class.forName(name, false, loader); }
            catch (ClassNotFoundException error) { throw new Unavailable("public_output_query_api_unavailable"); }
        }
    }
}
