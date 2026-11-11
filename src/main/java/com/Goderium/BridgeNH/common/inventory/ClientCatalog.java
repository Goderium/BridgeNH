package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.nei.NeiRenderReflection;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;
import com.Goderium.BridgeNH.util.LoadedModEvidence;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

/** Public client registry access with no creative-variant scan of the whole registry. */
public class ClientCatalog {
    private List<JsonObject> itemCatalog;
    private boolean catalogTruncated;

    public JsonObject mods(JsonObject args) {
        String query = CatalogData.text(args, "query", 128, "");
        boolean evidence = GuiDataAdapter.booleanArg(args, "includeEvidence", false);
        boolean candidates = GuiDataAdapter.booleanArg(args, "includeCandidates", false);
        if (candidates && query.isEmpty()) throw new BridgeException("invalid_args", "Duplicate candidate inspection requires an explicit mod query.");
        List<JsonObject> mods = new ArrayList<JsonObject>();
        java.util.Map<String, ModContainer> containers = new java.util.HashMap<String, ModContainer>();
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            JsonObject entry = new JsonObject();
            modField(entry, "modId", 256, mod::getModId);
            modField(entry, "name", 512, mod::getName);
            modField(entry, "version", 256, mod::getVersion);
            containers.put(mod.getModId(), mod);
            mods.add(entry);
        }
        Collections.sort(mods, Comparator.comparing(entry -> entry.get("modId").getAsString()));
        List<JsonObject> matching = CatalogData.matching(mods, query, "modId", "name", "version");
        if (candidates && matching.size() != 1) throw new BridgeException("invalid_args", "Duplicate candidate inspection requires a query matching exactly one loaded mod.");
        JsonObject result = CatalogData.page("mods", matching, args, 10000);
        for (com.google.gson.JsonElement entry : result.getAsJsonArray("mods")) {
            ModContainer mod = containers.get(JsonData.string(entry.getAsJsonObject(), "modId"));
            if (mod != null) try { LoadedModEvidence.add(entry.getAsJsonObject(), mod, evidence, candidates); }
            catch (RuntimeException | LinkageError error) { fieldError(entry.getAsJsonObject(), "loadEvidence", error); }
        }
        result.addProperty("query", query);
        result.addProperty("totalLoaded", mods.size());
        return result;
    }

    public JsonObject items(JsonObject args) {
        String query = CatalogData.text(args, "query", 128, "");
        if (itemCatalog == null) {
            List<String> keys = new ArrayList<String>();
            for (Object key : Item.itemRegistry.getKeys()) keys.add(String.valueOf(key));
            Collections.sort(keys);
            catalogTruncated = keys.size() > 65536;
            List<JsonObject> catalog = new ArrayList<JsonObject>();
            for (int i = 0; i < Math.min(keys.size(), 65536); i++) {
                Item item = (Item) Item.itemRegistry.getObject(keys.get(i));
                if (item == null) continue;
                JsonObject entry = new JsonObject();
                entry.addProperty("registryName", JsonData.bounded(keys.get(i), 256));
                entry.addProperty("itemClass", item.getClass().getName());
                try { entry.addProperty("unlocalizedName", JsonData.bounded(item.getUnlocalizedName(), 512)); }
                catch (RuntimeException | LinkageError error) { fieldError(entry, "unlocalizedName", error); }
                catalog.add(entry);
            }
            itemCatalog = catalog;
        }
        JsonObject result = CatalogData.page("items", CatalogData.matching(itemCatalog, query, "registryName", "unlocalizedName"), args, 100000);
        result.addProperty("query", query);
        result.addProperty("catalogSize", itemCatalog.size());
        result.addProperty("catalogLimit", 65536);
        result.addProperty("catalogTruncated", catalogTruncated);
        result.addProperty("source", "Sorted item registry and base unlocalized names; no global creative-variant enumeration.");
        return result;
    }

    public JsonObject item(JsonObject args, EntityPlayer player) {
        Item item = requireItem(args);
        int metadata = args.has("metadata") ? JsonData.integer(args, "metadata", 0, 32767) : 0;
        ItemStack stack = new ItemStack(item, 1, metadata);
        JsonObject fluidContainer = FluidContainerDataAdapter.read(stack);
        JsonObject result = describe(stack, player, true);
        result.addProperty("source", "registry_count_one_template");
        result.addProperty("countOneTemplate", true);
        result.add("fluidContainer", fluidContainer);
        result.add("nativeStructureChannel", nativeStructureChannel(stack));
        return result;
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject nativeStructureChannel(ItemStack stack) {
        JsonObject result = new JsonObject(); result.addProperty("channel", "glass");
        result.addProperty("source", "gregtech.api.util.GlassTier.getGlassChannelValue(Block,int)/getGlassBlockTier(Block,int); existing native cached mapping only.");
        result.addProperty("initializationInvoked", false); result.add("value", com.google.gson.JsonNull.INSTANCE); result.add("voltageTier", com.google.gson.JsonNull.INSTANCE);
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) { result.addProperty("status", "not_block_item"); return result; }
        Block block = Block.getBlockFromItem(stack.getItem());
        if (block == null) { result.addProperty("status", "native_block_unavailable"); return result; }
        Class<?> api = NeiRenderReflection.optional("gregtech.api.util.GlassTier");
        try {
            int channel = ((Number) NeiRenderReflection.call(api, null, "getGlassChannelValue", new Class<?>[] {Block.class, int.class}, block, stack.getMetadata())).intValue();
            Integer tier = (Integer) NeiRenderReflection.call(api, null, "getGlassBlockTier", new Class<?>[] {Block.class, int.class}, block, stack.getMetadata());
            result.addProperty("rawValue", channel); result.addProperty("rawVoltageTier", tier);
            result.addProperty("status", tier == null || channel == 0 ? "not_mapped" : channel < 0 ? "not_initialized" : "native_cached_mapping");
            if (channel > 0) result.addProperty("value", channel);
            if (tier != null) result.addProperty("voltageTier", tier);
        } catch (RuntimeException | LinkageError error) { result.addProperty("status", "native_api_unavailable"); }
        return result;
    }

    public JsonObject variants(JsonObject args, EntityPlayer player) {
        Item item = requireItem(args);
        String query = CatalogData.text(args, "query", 128, "");
        // Validate pagination before invoking another mod's creative-list callbacks.
        CatalogData.page("items", Collections.<JsonObject>emptyList(), args, 100000);
        LimitedVariants stacks = new LimitedVariants();
        CreativeTabs[] tabs;
        try { tabs = item.getCreativeTabs(); }
        catch (RuntimeException | LinkageError error) { throw new BridgeException("unsupported", "Creative tabs unavailable: " + error.getClass().getSimpleName()); }
        if (tabs == null || tabs.length == 0) throw new BridgeException("unsupported", "Item exposes no public creative tabs.");
        boolean truncated = tabs.length > 32;
        boolean invoked = false;
        Set<CreativeTabs> visited = Collections.newSetFromMap(new IdentityHashMap<CreativeTabs, Boolean>());
        for (int i = 0; i < Math.min(tabs.length, 32); i++) {
            if (tabs[i] == null || !visited.add(tabs[i])) continue;
            invoked = true;
            try { item.getSubItems(item, tabs[i], stacks); }
            catch (VariantLimitReached error) { truncated = true; break; }
            catch (RuntimeException | LinkageError error) {
                throw new BridgeException("unsupported", "Item creative variants failed: " + error.getClass().getSimpleName() + "; no partial result is presented as complete.");
            }
        }
        if (!invoked) throw new BridgeException("unsupported", "Item exposes no non-null public creative tab.");
        List<JsonObject> variants = new ArrayList<JsonObject>();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.getItem() == null) continue;
            JsonObject entry = describe(stack, player, false);
            entry.addProperty("variantIndex", i);
            variants.add(entry);
        }
        // Preserve the public creative-list ordering, including same-meta NBT variants.
        JsonObject result = CatalogData.page("items", CatalogData.matching(variants, query, "registryName", "displayName", "unlocalizedName"), args, 100000);
        result.addProperty("registryName", String.valueOf(Item.itemRegistry.getNameForObject(item)));
        result.addProperty("query", query);
        result.addProperty("enumerated", variants.size());
        result.addProperty("variantLimit", 8192);
        result.addProperty("enumerationTruncated", truncated);
        result.addProperty("source", "This item's public creative tabs/getSubItems only; variants are descriptive, no item is granted.");
        return result;
    }

    private static Item requireItem(JsonObject args) {
        String name = CatalogData.text(args, "registryName", 256, null);
        if (name.isEmpty()) throw new BridgeException("invalid_args", "registryName must not be empty.");
        if (!Item.itemRegistry.containsKey(name)) throw new BridgeException("not_found", "No item is registered as " + name);
        Object item = Item.itemRegistry.getObject(name);
        if (!(item instanceof Item)) throw new BridgeException("not_found", "Registry entry is not an item.");
        return (Item) item;
    }

    private static JsonObject describe(ItemStack stack, EntityPlayer player, boolean tooltip) {
        JsonObject result = new JsonObject();
        result.addProperty("registryName", JsonData.bounded(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())), 256));
        result.addProperty("itemClass", stack.getItem().getClass().getName());
        result.addProperty("metadata", stack.getMetadata());
        result.addProperty("count", stack.stackSize);
        result.addProperty("hasTag", stack.hasTagCompound());
        try { result.addProperty("displayName", JsonData.bounded(stack.getDisplayName(), 512)); }
        catch (RuntimeException | LinkageError error) { fieldError(result, "displayName", error); }
        try { result.addProperty("unlocalizedName", JsonData.bounded(stack.getUnlocalizedName(), 512)); }
        catch (RuntimeException | LinkageError error) { fieldError(result, "unlocalizedName", error); }
        try { result.addProperty("stackLimit", stack.getMaxStackSize()); }
        catch (RuntimeException | LinkageError error) { fieldError(result, "stackLimit", error); }
        if (tooltip) {
            if (player == null) fieldError(result, "tooltip", "world_unavailable: tooltip requires a loaded client player");
            else try {
                List<?> lines = stack.getTooltip(player, false);
                JsonArray text = new JsonArray();
                int count = lines == null ? 0 : lines.size();
                boolean shortened = count > 32;
                for (int i = 0; i < Math.min(count, 32); i++) {
                    String line = String.valueOf(lines.get(i));
                    if (line.length() > 512) shortened = true;
                    text.add(new JsonPrimitive(JsonData.bounded(line, 512)));
                }
                result.add("tooltip", text);
                result.addProperty("tooltipTruncated", shortened);
                result.addProperty("tooltipTotalLines", count);
            } catch (RuntimeException | LinkageError error) { fieldError(result, "tooltip", error); }
        }
        return result;
    }

    private static void fieldError(JsonObject result, String field, Throwable error) { fieldError(result, field, error.getClass().getName()); }

    private static void modField(JsonObject result, String field, int maximum, Supplier<String> getter) {
        try { result.addProperty(field, JsonData.bounded(getter.get(), maximum)); }
        catch (RuntimeException | LinkageError error) {
            result.addProperty(field, "");
            fieldError(result, field, error);
        }
    }

    private static void fieldError(JsonObject result, String field, String error) {
        JsonObject errors = result.has("fieldErrors") ? result.getAsJsonObject("fieldErrors") : new JsonObject();
        errors.addProperty(field, JsonData.bounded(error, 256));
        result.add("fieldErrors", errors);
    }

    @com.google.common.annotations.VisibleForTesting
    public static class VariantLimitReached extends RuntimeException { }

    @com.google.common.annotations.VisibleForTesting
    public static class LimitedVariants extends ArrayList<ItemStack> {
        @Override public boolean add(ItemStack value) {
            if (size() >= 8192) throw new VariantLimitReached();
            return super.add(value);
        }
        @Override public void add(int index, ItemStack value) {
            if (size() >= 8192) throw new VariantLimitReached();
            super.add(index, value);
        }
        @Override public boolean addAll(Collection<? extends ItemStack> values) {
            boolean changed = false;
            for (ItemStack value : values) changed |= add(value);
            return changed;
        }
        @Override public boolean addAll(int index, Collection<? extends ItemStack> values) {
            for (ItemStack value : values) add(index++, value);
            return !values.isEmpty();
        }
    }
}
