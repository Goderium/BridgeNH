package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.client.nei.NeiPreviewProvider.Scene;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

/** Existing preview tiles and texture layers, through getters and fixed known layer fields; never renders. */
final class NeiTextureProvider {
    private static final String TILE_API = "gregtech.api.interfaces.tileentity.ITexturedTileEntity";
    private static final String BLOCK_API = "gregtech.api.interfaces.IBlockWithTextures";
    private static final String TEXTURE_API = "gregtech.api.interfaces.ITexture";
    private static final String ICON_API = "gregtech.api.interfaces.IIconContainer";
    private static final String RENDERED = "gregtech.common.render.GTRenderedTexture";
    private static final String MULTI = "gregtech.common.render.GTMultiTextureRender";
    private static final String SIDED = "gregtech.common.render.GTSidedTextureRender";
    private static final String COPIED = "gregtech.common.render.GTCopiedBlockTextureRender";
    private static final String COPIED_CTM = "gregtech.common.render.GTCopiedCTMBlockTexture";
    private static final int MAX_NODES = 512;
    private static final int MAX_ENTRIES = 256;

    static JsonObject read(Scene scene, long packed, int requestedSide, int offset, int limit, long started, JsonObject data) {
        JsonObject result = NeiPreviewDetailProvider.base("preview_textures"); result.add("data", data);
        Object value = scene.blocks.get(packed);
        if (!(value instanceof Block)) throw new BridgeException("unsupported", "Existing preview entry is not a Block.");
        Block block = (Block) value;
        Object metadata = scene.metadata.get(packed), tile = scene.tiles.get(packed);
        int meta = metadata instanceof Number ? ((Number) metadata).intValue() : 0;
        JsonObject xyz = NeiPreviewProvider.coordinates(packed, 0, 64, 0).getAsJsonObject("position");
        int x = xyz.get("x").getAsInt(), y = xyz.get("y").getAsInt(), z = xyz.get("z").getAsInt();
        data.addProperty("metadata", meta); data.addProperty("tileClass", tile == null ? null : tile.getClass().getName());
        data.addProperty("textureReadSource", "Existing fake-world Block/TileEntity, real six-face public texture getters, and fixed known ITexture layer fields; no prototype/meta-ID substitution or renderer calls.");
        data.addProperty("textureGraphicsCoverage", "Actual base/overlay icons, atlas resources/UV and stored tint/orientation context. CTM world-context remapping, TESR/custom geometry, shaders and lighting are not rendered by this reader; unresolved layers are explicit.");
        data.addProperty("resourceContext", NeiPreviewResources.context());
        data.addProperty("textureSide", requestedSide); data.addProperty("maximumTraversedNodes", MAX_NODES); data.addProperty("maximumTextureEntries", MAX_ENTRIES);
        data.add("tileContext", tileContext(tile));
        Collector collector = new Collector();
        Class<?> tileApi = api(tile, TILE_API), blockApi = api(block, BLOCK_API);
        Object blockTextures = null;
        if (tileApi == null && blockApi != null) {
            blockTextures = scene.world instanceof IBlockAccess
                ? invoke(blockApi, block, "getTextures", new Class<?>[] {IBlockAccess.class, int.class, int.class, int.class}, scene.world, x, y, z)
                : invoke(blockApi, block, "getTextures", new Class<?>[] {int.class}, meta);
            data.addProperty("textureRootSource", scene.world instanceof IBlockAccess ? "IBlockWithTextures.getTextures(existingWorld,x,y,z)" : "IBlockWithTextures.getTextures(metadata); world interface unavailable");
        } else data.addProperty("textureRootSource", tileApi != null ? "Existing ITexturedTileEntity.getTexture(Block,ForgeDirection), including its actual active/facing/color/cover context" : "Block.getIcon(existingWorld,x,y,z,side), with metadata fallback when world interface is unavailable");
        for (int side = requestedSide < 0 ? 0 : requestedSide; side <= (requestedSide < 0 ? 5 : requestedSide); side++) {
            if (tileApi != null) {
                Object roots = invoke(tileApi, tile, "getTexture", new Class<?>[] {Block.class, ForgeDirection.class}, block, ForgeDirection.getOrientation(side));
                collector.roots(roots, side, "tile");
            } else if (blockApi != null && blockTextures != null && blockTextures.getClass().isArray()) {
                collector.roots(side < Array.getLength(blockTextures) ? Array.get(blockTextures, side) : null, side, "block");
            } else {
                JsonObject entry = collector.entry(side, "block", block.getClass().getName());
                IIcon icon = scene.world instanceof IBlockAccess ? block.getIcon((IBlockAccess) scene.world, x, y, z, side) : block.getIcon(side, meta);
                entry.add("baseIcon", icon(icon)); entry.add("overlayIcon", JsonNull.INSTANCE);
                entry.addProperty("status", icon == null ? "not_present" : "ok");
                entry.addProperty("iconSource", scene.world instanceof IBlockAccess ? "Block.getIcon(existingWorld,x,y,z,side)" : "Block.getIcon(side,metadata)");
                if (scene.world instanceof IBlockAccess) entry.addProperty("blockColorMultiplier", block.colorMultiplier((IBlockAccess) scene.world, x, y, z));
                collector.add(entry);
            }
        }
        data.addProperty("textureModelComplete", !collector.limited);
        data.addProperty("textureModelLimitReason", collector.limited ? "Known layer traversal exceeded its bounded depth/node/entry schema; omitted custom tails require another supported provider." : null);
        data.addProperty("unresolvedTextureEntries", collector.unresolved);
        data.addProperty("pixelDataStored", false);
        NeiPreviewDetailProvider.page(result, collector.entries.size(), offset, limit, started, index -> collector.entries.get(index));
        if (collector.limited) result.addProperty("complete", false);
        return result;
    }

    private static JsonObject tileContext(Object tile) {
        JsonObject result = new JsonObject();
        Class<?> base = NeiPreviewProvider.type(tile, "gregtech.api.metatileentity.BaseMetaTileEntity");
        if (base == null) { result.addProperty("status", tile == null ? "not_present" : "non_gt_tile"); return result; }
        for (String getter : new String[] {"getFrontFacing", "isActive", "getColorization"}) {
            try {
                Object value = invoke(base, tile, getter, new Class<?>[0]);
                String key = "getFrontFacing".equals(getter) ? "frontFacing" : "isActive".equals(getter) ? "active" : "colorization";
                if (value instanceof ForgeDirection) result.addProperty(key, ((ForgeDirection) value).ordinal());
                else if (value instanceof Number) result.addProperty(key, (Number) value);
                else if (value instanceof Boolean) result.addProperty(key, (Boolean) value);
                else result.addProperty(key + "Status", "unsupported_getter_value");
            } catch (BridgeException error) { result.addProperty(getter + "Status", error.code); }
        }
        result.addProperty("source", "Existing tile public getters; no changes to orientation, active state or color."); return result;
    }

    static final class Collector {
        final List<JsonObject> entries = new ArrayList<>();
        final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        int nodes, unresolved;
        boolean limited;

        void roots(Object roots, int side, String path) {
            if (roots == null) { JsonObject entry = entry(side, path, null); entry.addProperty("status", "not_present"); add(entry); return; }
            if (!roots.getClass().isArray()) { unsupported(roots, side, path, "Texture root is not an array."); return; }
            for (int i = 0; i < Array.getLength(roots); i++) {
                if (nodes >= MAX_NODES || entries.size() >= MAX_ENTRIES) { limited = true; break; }
                layer(Array.get(roots, i), side, path + '/' + i, 0);
            }
        }

        void layer(Object texture, int side, String path, int depth) {
            if (nodes++ >= MAX_NODES || entries.size() >= MAX_ENTRIES || depth > 16) { limited = true; return; }
            if (texture == null) { JsonObject entry = entry(side, path, null); entry.addProperty("status", "null_layer"); add(entry); return; }
            if (!active.add(texture)) { unsupported(texture, side, path, "Cyclic stored texture graph."); return; }
            try {
                if (NeiPreviewProvider.type(texture, MULTI) != null) {
                    Object children = NeiPreviewProvider.field(texture, MULTI, "mTextures", false);
                    if (children == null || !children.getClass().isArray()) { unsupported(texture, side, path, "Multi texture has no stored child array."); return; }
                    for (int i = 0; i < Array.getLength(children); i++) {
                        if (nodes >= MAX_NODES || entries.size() >= MAX_ENTRIES) { limited = true; break; }
                        layer(Array.get(children, i), side, path + "/multi/" + i, depth + 1);
                    }
                    return;
                }
                if (NeiPreviewProvider.type(texture, SIDED) != null) {
                    Object children = NeiPreviewProvider.field(texture, SIDED, "mTextures", false);
                    if (children != null && children.getClass().isArray() && side < Array.getLength(children)) layer(Array.get(children, side), side, path + "/sided/" + side, depth + 1);
                    else unsupported(texture, side, path, "Sided texture has no entry for this face.");
                    return;
                }
                JsonObject entry = entry(side, path, texture.getClass().getName());
                Class<?> validity = api(texture, TEXTURE_API);
                if (validity != null) entry.addProperty("valid", (Boolean) invoke(validity, texture, "isValidTexture", new Class<?>[0]));
                if (NeiPreviewProvider.type(texture, RENDERED) != null) {
                    Object container = NeiPreviewProvider.field(texture, RENDERED, "mIconContainer", false);
                    Class<?> containerApi = api(container, ICON_API);
                    if (containerApi == null) { unsupported(texture, side, path, "Rendered layer lacks an existing IIconContainer."); return; }
                    entry.addProperty("status", "ok"); entry.addProperty("kind", "rendered_icon_container"); entry.addProperty("containerClass", container.getClass().getName());
                    entry.add("rgba", numbers(invoke(NeiPreviewProvider.type(texture, RENDERED), texture, "getRGBA", new Class<?>[0])));
                    entry.addProperty("glow", (Boolean) NeiPreviewProvider.field(texture, RENDERED, "glow", false));
                    entry.addProperty("useExtendedFacing", (Boolean) NeiPreviewProvider.field(texture, RENDERED, "useExtFacing", false));
                    Object baseIcon = invoke(containerApi, container, "getIcon", new Class<?>[0]);
                    Object overlayIcon = invoke(containerApi, container, "getOverlayIcon", new Class<?>[0]);
                    entry.add("baseIcon", icon(baseIcon)); entry.add("overlayIcon", icon(overlayIcon));
                    entry.addProperty("atlasResource", bounded(invoke(containerApi, container, "getTextureFile", new Class<?>[0])));
                    resourceFields(entry, container, baseIcon, overlayIcon);
                    int renderPass = ((Number) invoke(containerApi, container, "getRenderIconPass", new Class<?>[0])).intValue();
                    entry.addProperty("renderIconPass", renderPass);
                    int passes = ((Number) invoke(containerApi, container, "getIconPasses", new Class<?>[0])).intValue();
                    entry.addProperty("iconPasses", passes);
                    JsonArray passData = new JsonArray();
                    int passExtent = Math.max(Math.max(passes, 0), renderPass + 1);
                    for (int pass = 0; pass < Math.min(passExtent, 8); pass++) {
                        JsonObject p = new JsonObject(); p.addProperty("pass", pass);
                        p.addProperty("canRender", (Boolean) invoke(containerApi, container, "canRenderInPass", new Class<?>[] {int.class}, pass));
                        p.addProperty("colorModulation", (Boolean) invoke(containerApi, container, "isUsingColorModulation", new Class<?>[] {int.class}, pass));
                        p.add("rgba", numbers(invoke(containerApi, container, "getIconColor", new Class<?>[] {int.class}, pass))); passData.add(p);
                    }
                    entry.add("passes", passData); entry.addProperty("passesComplete", passExtent <= 8);
                    entry.addProperty("ctmStatus", "Base/overlay from actual existing container; context-dependent CTM remapping is not evaluated.");
                    add(entry); return;
                }
                String copiedOwner = NeiPreviewProvider.type(texture, COPIED) != null ? COPIED : NeiPreviewProvider.type(texture, COPIED_CTM) != null ? COPIED_CTM : null;
                if (copiedOwner != null) {
                    Object copied = NeiPreviewProvider.field(texture, copiedOwner, "mBlock", false);
                    int copySide = ((Number) NeiPreviewProvider.field(texture, copiedOwner, "mSide", false)).intValue();
                    int copyMeta = ((Number) NeiPreviewProvider.field(texture, copiedOwner, "mMeta", false)).intValue();
                    if (!(copied instanceof Block)) { unsupported(texture, side, path, "Copied texture has no existing Block."); return; }
                    entry.addProperty("status", "ok"); entry.addProperty("kind", "copied_block_icon");
                    entry.addProperty("copiedSide", copySide); entry.addProperty("copiedMetadata", copyMeta);
                    entry.addProperty("copiedBlockRegistryName", bounded(Block.blockRegistry.getNameForObject(copied)));
                    entry.add("baseIcon", icon(((Block) copied).getIcon(copySide == 6 ? side : copySide, copyMeta))); entry.add("overlayIcon", JsonNull.INSTANCE);
                    entry.addProperty("ctmStatus", "Copied base icon only; no private CTM getter or global metadata override is invoked."); add(entry); return;
                }
                unsupported(texture, side, path, "Custom ITexture has no verified data-only icon schema; render callbacks are not invoked.");
            } catch (RuntimeException | LinkageError error) { unsupported(texture, side, path, "Known texture getter/schema unavailable: " + error.getClass().getSimpleName()); }
            finally { active.remove(texture); }
        }

        JsonObject entry(int side, String path, String type) {
            JsonObject entry = new JsonObject(); entry.addProperty("side", side); entry.addProperty("face", ForgeDirection.getOrientation(side).name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("layerPath", path); entry.addProperty("textureClass", type); return entry;
        }
        void unsupported(Object texture, int side, String path, String reason) {
            JsonObject entry = entry(side, path, texture == null ? null : texture.getClass().getName()); entry.addProperty("status", "unsupported"); entry.addProperty("reason", reason); unresolved++; add(entry);
        }
        void add(JsonObject entry) { if (entries.size() < MAX_ENTRIES) { entry.addProperty("textureIndex", entries.size()); entries.add(entry); } else limited = true; }
    }

    static JsonElement icon(Object value) { return icon(value, "blocks"); }

    static JsonElement icon(Object value, String folder) {
        if (!(value instanceof IIcon)) return JsonNull.INSTANCE;
        IIcon icon = (IIcon) value; JsonObject result = new JsonObject(); String name = icon.getIconName();
        result.addProperty("iconName", JsonData.bounded(name, 2048)); result.addProperty("iconNameTruncated", name != null && name.length() > 2048);
        result.addProperty("pixelWidth", icon.getIconWidth()); result.addProperty("pixelHeight", icon.getIconHeight());
        result.addProperty("minU", icon.getMinU()); result.addProperty("maxU", icon.getMaxU()); result.addProperty("minV", icon.getMinV()); result.addProperty("maxV", icon.getMaxV());
        if (name != null && name.length() <= 2048) {
            int colon = name.indexOf(':'); String domain = colon < 0 ? "minecraft" : name.substring(0, colon), path = colon < 0 ? name : name.substring(colon + 1);
            result.addProperty("derivedSourceResource", domain + ":textures/" + folder + '/' + path + ".png");
            result.addProperty("derivedSourceStatus", "Conventional icon-name path only; actual resource selection/fallback is reported separately when stored by the native container.");
        }
        return result;
    }

    private static void resourceFields(JsonObject entry, Object container, Object baseIcon, Object overlayIcon) {
        String owner = "gregtech.client.iconContainers.blocks.GTTextureSetBlockIconContainer";
        if (NeiPreviewProvider.type(container, owner) != null) {
            entry.addProperty("storedBaseResource", bounded(NeiPreviewProvider.field(container, owner, "iconResource", false)));
            entry.addProperty("storedBaseFallbackResource", bounded(NeiPreviewProvider.field(container, owner, "iconFallbackResource", false)));
            entry.addProperty("storedOverlayResource", bounded(NeiPreviewProvider.field(container, owner, "iconOverlayResource", false)));
            entry.addProperty("storedOverlayFallbackResource", bounded(NeiPreviewProvider.field(container, owner, "iconOverlayFallbackResource", false)));
            entry.addProperty("baseUsesFallback", baseIcon instanceof IIcon && ((IIcon) baseIcon).getIconName().equals(NeiPreviewProvider.field(container, owner, "fallbackIconName", false)));
            entry.addProperty("overlayUsesFallback", overlayIcon instanceof IIcon && ((IIcon) overlayIcon).getIconName().equals(NeiPreviewProvider.field(container, owner, "fallbackIconOverlayName", false)));
            return;
        }
        owner = "gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer";
        if (NeiPreviewProvider.type(container, owner) != null) {
            entry.addProperty("storedBaseResource", bounded(NeiPreviewProvider.field(container, owner, "iconResource", false)));
            entry.addProperty("storedOverlayResource", bounded(NeiPreviewProvider.field(container, owner, "overlayResource", false))); return;
        }
        owner = "gregtech.client.iconContainers.blocks.GTBlockIconContainer";
        if (NeiPreviewProvider.type(container, owner) != null) entry.addProperty("storedBaseResource", bounded(NeiPreviewProvider.field(container, owner, "iconResource", false)));
    }

    private static JsonArray numbers(Object array) {
        JsonArray values = new JsonArray();
        if (array != null && array.getClass().isArray()) for (int i = 0; i < Math.min(Array.getLength(array), 8); i++) {
            Object value = Array.get(array, i); if (value instanceof Number) values.add(new com.google.gson.JsonPrimitive((Number) value));
        }
        return values;
    }
    private static String bounded(Object value) { return value == null ? null : JsonData.bounded(value.toString(), 2048); }
    private static Class<?> api(Object value, String name) {
        if (value == null) return null;
        try { Class<?> api = Class.forName(name, false, value.getClass().getClassLoader()); return api.isInstance(value) ? api : null; }
        catch (ClassNotFoundException | LinkageError error) { return null; }
    }
    private static Object invoke(Class<?> owner, Object target, String name, Class<?>[] signature, Object... args) {
        try { Method method = owner.getMethod(name, signature); return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw new BridgeException("adapter_error", "Native public texture getter failed: " + name + '/' + error.getCause().getClass().getSimpleName()); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Native public texture API is unavailable: " + name); }
    }
}
