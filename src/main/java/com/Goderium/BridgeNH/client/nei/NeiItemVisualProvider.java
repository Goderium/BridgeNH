package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.client.nei.NeiPreviewProvider.Scene;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

/** Inventory visual data only: native GT six-face models or ordinary Item icons/passes, without drawing. */
final class NeiItemVisualProvider {
    private static final String ITEM_MACHINES = "gregtech.common.blocks.ItemMachines";
    private static final String MTE_API = "gregtech.api.interfaces.metatileentity.IMetaTileEntity";
    private static final String COMMON_MTE = "gregtech.api.metatileentity.CommonMetaTileEntity";
    private static final String BASE_TILE_API = "gregtech.api.interfaces.tileentity.IGregTechTileEntity";
    private static final String INVENTORY_CONTEXT = "gregtech.api.render.ISBRInventoryContext";
    private static final String BLOCK_TEXTURE_API = "gregtech.api.interfaces.IBlockWithTextures";
    private static final String POSITIONED_STACK = "codechicken.nei.PositionedStack";

    static JsonObject readCandidate(Object screen, JsonObject args, int offset, int limit, long started) {
        Scene scene = NeiPreviewProvider.scene(screen);
        if (scene == null || !scene.built) return missing("preview_candidate_visuals", "No existing built preview for this original recipe.");
        return readCandidateModel(scene, args, offset, limit, started);
    }

    static JsonObject readCandidateModel(Scene scene, JsonObject args, int offset, int limit, long started) {
        JsonObject selected = NeiPreviewProvider.selection(scene);
        if (!selected.get("present").getAsBoolean()) return missing("preview_candidate_visuals", "No selected preview position; candidate visuals only read its existing native cache.");
        if (scene.candidates == null) return missing("preview_candidate_visuals", scene.candidateStatus == null ? "Existing native candidate cache unavailable." : scene.candidateStatus);
        List<?> groups = scene.candidates; int group = JsonData.integer(args, "groupIndex", 0, Integer.MAX_VALUE);
        if (group >= groups.size()) throw new BridgeException("not_found", "groupIndex is absent from the existing native candidate cache.");
        Object alternatives = NeiPreviewProvider.field(groups.get(group), POSITIONED_STACK, "items", true);
        if (alternatives == null || !alternatives.getClass().isArray()) throw new BridgeException("unsupported", "Existing candidate items are not an array.");
        int alternative = JsonData.integer(args, "alternativeIndex", 0, Integer.MAX_VALUE);
        if (alternative >= Array.getLength(alternatives)) throw new BridgeException("not_found", "alternativeIndex is absent from the stored candidate group.");
        Object value = Array.get(alternatives, alternative);
        if (!(value instanceof ItemStack)) return missing("preview_candidate_visuals", "Existing candidate is not an ItemStack.");
        JsonObject result = readItem((ItemStack) value, args, offset, limit, started); JsonObject data = result.getAsJsonObject("data");
        data.addProperty("groupIndex", group); data.addProperty("alternativeIndex", alternative); data.add("candidateSourcePosition", selected.get("position"));
        data.addProperty("candidateSource", "Already-generated selected-position RecipeCacher.positionedResults/PositionedStack.items; no cycle, permutation, scan or build callback.");
        data.addProperty("candidateRulesCoverage", "Inventory visuals for this actual cached candidate stack; not legal-hatch acceptance proof and not the original preview casing's texture.");
        return result;
    }

    static JsonObject readItem(ItemStack stack, JsonObject args, int offset, int limit, long started) {
        String target = args.has("target") ? JsonData.string(args, "target") : "item_visuals";
        if (offset < 0 || limit < 1 || limit > 64) throw new BridgeException("invalid_args", "Inventory visuals require nonnegative offset and an entry limit from 1 to 64.");
        JsonObject result = NeiPreviewDetailProvider.base(target), data = result.getAsJsonObject("data");
        data.addProperty("visualSource", "Actual cached ItemStack and its public inventory visual getters; not a scene-casing replacement, screenshot, tile construction or draw operation.");
        data.addProperty("resourceContext", NeiPreviewResources.context()); data.addProperty("pixelDataStored", false);
        data.addProperty("visualCoverage", "Native icon names/atlas/UV, ordered passes or six-face GT texture layers and tint. Geometry, projection, glint pixels, lighting, shader effects and arbitrary custom-renderer internals are not rendered.");
        data.addProperty("inventoryRenderContext", "Inventory UI item model; no player-world coordinate or actual placed-tile state is implied.");
        data.addProperty("textureModelComplete", true); data.addProperty("textureModelLimitReason", (String) null); data.addProperty("unresolvedTextureEntries", 0);
        data.addProperty("inventoryModelStatus", "not_queried");
        if (stack == null || stack.getItem() == null) return missing(target, "Requested stored ItemStack is absent.");
        data.add("item", NeiPreviewProvider.stack(stack)); Item item = stack.getItem();
        IItemRenderer custom;
        try {
            custom = MinecraftForgeClient.getItemRenderer(stack, IItemRenderer.ItemRenderType.INVENTORY);
            data.addProperty("itemRendererClass", custom == null ? null : custom.getClass().getName());
            data.addProperty("itemRendererStatus", custom == null ? "no_custom_inventory_renderer" : "custom_inventory_renderer");
        } catch (RuntimeException | LinkageError error) {
            data.addProperty("itemRendererClass", (String) null); data.addProperty("itemRendererStatus", "query_unavailable:" + error.getClass().getSimpleName());
            return unsupported(result, "The actual Forge inventory renderer query is unavailable; ordinary item icons cannot be asserted to match the displayed renderer.");
        }
        if (custom != null) return unsupported(result, "Actual Forge IItemRenderer has no general data-only texture/geometry API; only handleRenderType, shouldUseRenderHelper and renderItem are public. No renderItem call is made.");
        Class<?> itemMachines = NeiPreviewProvider.type(item, ITEM_MACHINES);
        if (itemMachines != null) {
            try {
                Object meta = invoke(itemMachines, null, "getMetaTileEntity", new Class<?>[] {ItemStack.class}, stack);
                Class<?> metaApi = api(meta, MTE_API);
                if (metaApi == null) return unsupported(result, "ItemMachines has no existing valid IMetaTileEntity for this runtime stack metadata; no tile is created.");
                data.addProperty("itemVisualKind", "gt_inventory_texture_model");
                boolean standard = standardInventory(meta); data.addProperty("inventoryModelStatus", standard ? "standard_native_inventory_path" : "published_model_with_custom_inventory_render_gap");
                Object textures = invoke(metaApi, meta, "getInventoryTextures", new Class<?>[0]);
                if (textures == null) {
                    if (!standard) return unsupported(result, "MTE inventory renderer is custom and publishes no stable inventory texture model; renderInInventory is not called.");
                    Object base = invoke(metaApi, meta, "getBaseMetaTileEntity", new Class<?>[0]);
                    Class<?> baseApi = load(meta, BASE_TILE_API); Object[] directions = net.minecraftforge.common.util.ForgeDirection.values();
                    NeiTextureProvider.Collector collector = new NeiTextureProvider.Collector();
                    for (int side = 0; side < 6; side++) collector.roots(invoke(metaApi, meta, "getTexture", new Class<?>[] {baseApi, net.minecraftforge.common.util.ForgeDirection.class, net.minecraftforge.common.util.ForgeDirection.class, int.class, boolean.class, boolean.class}, base, directions[side], net.minecraftforge.common.util.ForgeDirection.WEST, -1, true, false), side, "inventory/native_fallback");
                    data.addProperty("inventoryTextureSource", "Standard GTRendererBlock fallback: existing MTE.getTexture(existingBase,side,WEST,-1,true,false), six faces; no tile construction or renderer call.");
                    data.addProperty("inventoryRenderContext", "Native GT inventory context: facing WEST, uncolored=-1, active=true, redstone=false; independent of a placed world tile or the candidate NBT.");
                    return layers(result, collector, offset, limit, started);
                }
                data.addProperty("inventoryTextureSource", "Existing ItemMachines.getMetaTileEntity(actualStack) -> public IMetaTileEntity.getInventoryTextures(); the same stable model used by native GTRendererBlock.");
                data.addProperty("inventoryRenderContext", "API-published GT inventory six-face model. Standard hatch/basic-machine cache uses facing WEST, color -1, active=true, redstone=false; candidate NBT is not installed into a tile.");
                if (!standard) data.addProperty("visualCoverage", "Published six-face texture model is read, but this MTE also overrides renderInInventory; custom geometry/effects and exact final pixels remain unsupported. No renderInInventory invocation.");
                return matrix(result, textures, offset, limit, started);
            } catch (BridgeException error) { return unsupported(result, "Native GT inventory visual API unavailable: " + error.getMessage()); }
            catch (RuntimeException | LinkageError error) { return unsupported(result, "Native GT inventory visual getter failed: " + error.getClass().getSimpleName()); }
        }
        Block block = item instanceof ItemBlock ? Block.getBlockFromItem(item) : null;
        if (block != null) {
            int renderType = block.getRenderType(); data.addProperty("blockRenderType", renderType); data.addProperty("blockRenderPass", block.getRenderBlockPass());
            boolean native3d;
            try { native3d = RenderBlocks.renderItemIn3d(renderType); data.addProperty("nativeBlockInventory3d", native3d); }
            catch (RuntimeException | LinkageError error) { return unsupported(result, "Native block inventory 3D classification is unavailable: " + error.getClass().getSimpleName()); }
            if (item.getSpriteNumber() == 0 && native3d) {
                Class<?> blockApi = api(block, BLOCK_TEXTURE_API);
                if (blockApi != null) {
                    Object textures = invoke(blockApi, block, "getInventoryTextures", new Class<?>[] {int.class}, stack.getMetadata());
                    if (textures != null) { data.addProperty("itemVisualKind", "block_inventory_texture_model"); data.addProperty("inventoryModelStatus", "published_block_inventory_model"); data.addProperty("inventoryTextureSource", "IBlockWithTextures.getInventoryTextures(actualStack.metadata), not world-state textures."); return matrix(result, textures, offset, limit, started); }
                }
                return readBlockInventory(result, stack, block, offset, limit, started);
            }
        }
        boolean multiple = item.requiresMultipleRenderPasses(); int total = multiple ? item.getRenderPasses(stack.getMetadata()) : 1;
        if (total < 0) return unsupported(result, "Native item getter returned a negative render-pass count.");
        int sprite = item.getSpriteNumber();
        data.addProperty("itemVisualKind", "ordinary_item_icons"); data.addProperty("inventoryModelStatus", "native_item_icon_getters");
        data.addProperty("inventoryTextureSource", multiple ? "Item.getRenderPasses(meta), getIcon(actualStack,pass), getColorFromItemStack(actualStack,pass), getSpriteNumber and stack.hasEffect(pass)." : "Actual ItemStack.getIconIndex(), Item.getColorFromItemStack(actualStack,0), getSpriteNumber and stack.hasEffect(0).");
        data.addProperty("multipleRenderPasses", multiple); data.addProperty("renderPasses", total);
        data.addProperty("visualCoverage", "Native Item icon getters and tint/glint flags only. A block item may use a separate 3D block rendering path; these icon getters are not a universal geometry model.");
        final int[] unresolved = {0};
        NeiPreviewDetailProvider.page(result, total, offset, limit, started, pass -> {
            JsonObject entry = new JsonObject(); entry.addProperty("renderPass", pass); entry.addProperty("spriteNumber", sprite);
            entry.addProperty("atlasResource", sprite == 0 ? "minecraft:textures/atlas/blocks.png" : sprite == 1 ? "minecraft:textures/atlas/items.png" : null);
            try {
                entry.add("icon", NeiTextureProvider.icon(multiple ? item.getIcon(stack, pass) : stack.getIconIndex(), sprite == 0 ? "blocks" : "items"));
                entry.addProperty("colorRGB", item.getColorFromItemStack(stack, pass)); entry.addProperty("glint", stack.hasEffect(pass));
                entry.addProperty("iconSource", multiple ? "Item.getIcon(actualStack,pass)" : "ItemStack.getIconIndex()"); entry.addProperty("status", "ok");
            } catch (RuntimeException | LinkageError error) { entry.addProperty("status", "unsupported"); entry.addProperty("reason", "Actual item visual getter failed: " + error.getClass().getSimpleName()); unresolved[0]++; }
            return entry;
        });
        data.addProperty("unresolvedTextureEntries", unresolved[0]); return result;
    }

    static JsonObject readBlockInventory(JsonObject result, ItemStack stack, Block block, int offset, int limit, long started) {
        JsonObject data = result.getAsJsonObject("data"); int meta = stack.getMetadata();
        data.addProperty("itemVisualKind", "block_inventory_icons"); data.addProperty("inventoryModelStatus", "native_block_inventory_surface_icons");
        data.addProperty("inventoryTextureSource", "Actual Block.getBlockFromItem, getRenderType and RenderBlocks.renderItemIn3d classification, then Block.getIcon(side,actualStack.metadata), without renderBlockAsItem.");
        data.addProperty("inventoryRenderContext", "Native 3D block inventory path with stack metadata; six face icon data only, without placing a block or resolving world adjacency.");
        data.addProperty("visualCoverage", "Actual six-face Block icons and native render type/tint. Block shape, special block renderer geometry, lighting and pixel projection remain unresolved; a non-cube model is not described as a full cube.");
        data.addProperty("blockRenderType", block.getRenderType()); data.addProperty("blockRenderPass", block.getRenderBlockPass());
        data.addProperty("itemTintColorRGB", stack.getItem().getColorFromItemStack(stack, 0)); data.addProperty("blockRenderColorRGB", block.getRenderColor(meta));
        NeiPreviewDetailProvider.page(result, 6, offset, limit, started, side -> {
            JsonObject entry = new JsonObject(); entry.addProperty("side", side); entry.addProperty("face", net.minecraftforge.common.util.ForgeDirection.getOrientation(side).name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("metadata", meta); entry.addProperty("iconSource", "Block.getIcon(side,actualStack.metadata)");
            try { entry.add("baseIcon", NeiTextureProvider.icon(block.getIcon(side, meta))); entry.add("overlayIcon", com.google.gson.JsonNull.INSTANCE); entry.addProperty("status", "ok"); }
            catch (RuntimeException | LinkageError error) { entry.addProperty("status", "unsupported"); entry.addProperty("reason", "Native block icon getter failed: " + error.getClass().getSimpleName()); }
            return entry;
        }); return result;
    }

    private static JsonObject matrix(JsonObject result, Object matrix, int offset, int limit, long started) {
        if (!matrix.getClass().isArray()) return unsupported(result, "Published inventory texture model is not an array.");
        NeiTextureProvider.Collector collector = new NeiTextureProvider.Collector();
        for (int side = 0; side < 6; side++) collector.roots(side < Array.getLength(matrix) ? Array.get(matrix, side) : null, side, "inventory/model");
        return layers(result, collector, offset, limit, started);
    }

    private static JsonObject layers(JsonObject result, NeiTextureProvider.Collector collector, int offset, int limit, long started) {
        JsonObject data = result.getAsJsonObject("data");
        data.addProperty("maximumTraversedNodes", 512); data.addProperty("maximumTextureEntries", 256);
        data.addProperty("textureModelComplete", !collector.limited); data.addProperty("textureModelLimitReason", collector.limited ? "Stored inventory texture graph exceeds its bounded depth/node/entry schema." : null);
        data.addProperty("unresolvedTextureEntries", collector.unresolved);
        NeiPreviewDetailProvider.page(result, collector.entries.size(), offset, limit, started, index -> collector.entries.get(index));
        if (collector.limited) result.addProperty("complete", false); return result;
    }
    private static JsonObject unsupported(JsonObject result, String reason) {
        JsonObject data = result.getAsJsonObject("data"); data.addProperty("textureModelComplete", false); data.addProperty("textureModelLimitReason", reason); data.addProperty("inventoryModelStatus", "unsupported");
        data.add("entries", new JsonArray());
        result.addProperty("complete", false); return result;
    }
    private static JsonObject missing(String target, String reason) { JsonObject result = NeiPreviewDetailProvider.base(target); result.addProperty("status", "not_present"); result.addProperty("reason", reason); return result; }
    private static boolean standardInventory(Object meta) {
        try { return COMMON_MTE.equals(meta.getClass().getMethod("renderInInventory", load(meta, INVENTORY_CONTEXT)).getDeclaringClass().getName()); }
        catch (NoSuchMethodException | BridgeException error) { return false; }
    }
    private static Class<?> load(Object value, String name) {
        try { return Class.forName(name, false, value.getClass().getClassLoader()); }
        catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "Public inventory API class unavailable: " + name); }
    }
    private static Class<?> api(Object value, String name) {
        if (value == null) return null;
        try { Class<?> type = load(value, name); return type.isInstance(value) ? type : null; }
        catch (BridgeException error) { return null; }
    }
    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] signature, Object... args) {
        try { return owner.getMethod(name, signature).invoke(target, args); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", name + '/' + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public inventory API unavailable: " + name); }
    }
}
