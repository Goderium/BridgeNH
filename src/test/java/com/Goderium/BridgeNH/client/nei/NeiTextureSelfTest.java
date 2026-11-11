package com.Goderium.BridgeNH.client.nei;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.IBlockWithTextures;
import gregtech.api.interfaces.tileentity.ITexturedTileEntity;
import gregtech.common.render.GTCopiedBlockTextureRender;
import gregtech.common.render.GTMultiTextureRender;
import gregtech.common.render.GTRenderedTexture;
import gregtech.common.render.GTSidedTextureRender;
import java.util.Collections;
import java.util.LinkedHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.IIcon;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.ForgeDirection;

/** Existing tile, six faces, layered icons/colors and bounded custom-render gaps without renderer callbacks. */
public final class NeiTextureSelfTest {
    private static int checks;
    public static int run() { checks = 0; verify(); return checks; }
    public static void main(String[] args) { System.out.println("NEI texture fixtures passed: " + run()); }
    private static JsonObject read(NeiPreviewProvider.Scene scene, int side, int offset, int limit) { JsonObject data = new JsonObject(); data.addProperty("target", "preview_textures"); return NeiTextureProvider.read(scene, 0L, side, offset, limit, Long.MAX_VALUE, data); }
    private static void verify() {
        FixtureBlock block = new FixtureBlock(); TextureTile tile = new TextureTile();
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.blocks = Collections.singletonMap(0L, block); scene.metadata = Collections.singletonMap(0L, 7); scene.tiles = Collections.singletonMap(0L, tile); scene.world = new Object();
        JsonObject result = read(scene, -1, 0, 64); JsonArray entries = result.getAsJsonObject("data").getAsJsonArray("entries");
        check(entries.size() == 12 && tile.calls == 6, "six-face tile getters read the existing actual tile, preserving two ordered layers per face");
        for (int side = 0; side < 6; side++) {
            JsonObject first = entries.get(side * 2).getAsJsonObject();
            check(first.get("side").getAsInt() == side && first.getAsJsonObject("baseIcon").get("iconName").getAsString().equals("fixture:face" + side + "_active"), "real face icon follows actual existing tile state");
            check(first.getAsJsonObject("overlayIcon").get("iconName").getAsString().equals("fixture:overlay" + side), "overlay icon is retained separately");
            check(first.getAsJsonArray("rgba").get(0).getAsInt() == 21 && first.get("glow").getAsBoolean() && first.get("useExtendedFacing").getAsBoolean(), "layer colors and glow/facing flags are read from the real layer");
        }
        check(result.getAsJsonObject("data").get("unresolvedTextureEntries").getAsInt() == 0 && result.getAsJsonObject("data").get("textureModelComplete").getAsBoolean(), "known texture tree is completely represented");
        tile.active = false; result = read(scene, 3, 0, 1);
        check(result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("baseIcon").get("iconName").getAsString().equals("fixture:face3_idle"), "no stale registry/meta-only texture cache hides tile state changes");
        check(result.get("hasMore").getAsBoolean() && result.get("nextOffset").getAsInt() == 1, "texture pagination preserves real ordered layer indices");
        result = read(scene, 3, 1, 64); check(result.get("complete").getAsBoolean() && result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("textureIndex").getAsInt() == 1, "second layer continues without replacing it with a base icon");
        ITexture[] sided = new ITexture[6]; for (int side = 0; side < 6; side++) sided[side] = rendered("fixture:sided" + side, null);
        tile.override = new GTSidedTextureRender(sided); result = read(scene, 4, 0, 64);
        check(result.getAsJsonObject("data").getAsJsonArray("entries").size() == 1 && result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("baseIcon").get("iconName").getAsString().equals("fixture:sided4"), "sided wrapper uses only the queried face child");
        tile.override = new GTCopiedBlockTextureRender(block, 6, 17); result = read(scene, 2, 0, 64);
        check(result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("baseIcon").get("iconName").getAsString().equals("fixture:block2_meta17"), "copied block layer uses copied metadata and side6 current-face semantics");
        tile.override = new UnknownTexture(); result = read(scene, 0, 0, 64);
        check(result.getAsJsonObject("data").get("unresolvedTextureEntries").getAsInt() == 1 && result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().get("status").getAsString().equals("unsupported"), "unknown custom render layer is explicit, not invented");
        ITexture[] loop = new ITexture[1]; GTMultiTextureRender cyclic = new GTMultiTextureRender(loop); loop[0] = cyclic; tile.override = cyclic; result = read(scene, 0, 0, 64);
        check(result.getAsJsonObject("data").get("unresolvedTextureEntries").getAsInt() == 1, "cyclic texture graphs stop without renderer calls or stack overflow");
        ITexture[] wide = new ITexture[300]; for (int i = 0; i < wide.length; i++) wide[i] = rendered("fixture:wide" + i, null); tile.override = new GTMultiTextureRender(wide); result = read(scene, 0, 0, 64);
        check(result.get("total").getAsInt() == 256 && !result.getAsJsonObject("data").get("textureModelComplete").getAsBoolean() && !result.get("complete").getAsBoolean(), "oversized custom trees retain bounded output and explicit incomplete model");
        scene.tiles = Collections.emptyMap(); result = read(scene, -1, 0, 64);
        check(result.getAsJsonObject("data").getAsJsonArray("entries").size() == 6 && block.lastMeta == 7, "ordinary block six-face icons use actual raw preview metadata");
        TextureBlock textureBlock = new TextureBlock(); scene.blocks = Collections.singletonMap(0L, textureBlock); result = read(scene, 5, 0, 64);
        check(textureBlock.lastMeta == 7 && result.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("baseIcon").get("iconName").getAsString().equals("fixture:block_api5"), "IBlockWithTextures public API precedes ordinary icon fallback");
        check(!result.getAsJsonObject("data").get("pixelDataStored").getAsBoolean(), "no screenshot or texture pixels are retained");
    }
    private static GTRenderedTexture rendered(String base, String overlay) { return new GTRenderedTexture(new Container(base, overlay), new short[] {21, 34, 55, 0}, true, true); }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static final class TextureTile implements ITexturedTileEntity {
        boolean active = true; int calls; ITexture override;
        @Override public ITexture[] getTexture(Block block, ForgeDirection side) { calls++; return new ITexture[] {override != null ? override : new GTMultiTextureRender(rendered("fixture:face" + side.ordinal() + (active ? "_active" : "_idle"), "fixture:overlay" + side.ordinal()), rendered("fixture:second", null))}; }
    }
    public static class FixtureBlock extends Block {
        int lastMeta;
        public FixtureBlock() { super(Material.rock); }
        @Override public IIcon getIcon(int side, int meta) { lastMeta = meta; return new Icon("fixture:block" + side + "_meta" + meta); }
    }
    public static final class TextureBlock extends FixtureBlock implements IBlockWithTextures {
        @Override public ITexture[][] getTextures(int meta) { lastMeta = meta; ITexture[][] faces = new ITexture[6][]; for (int side = 0; side < 6; side++) faces[side] = new ITexture[] {rendered("fixture:block_api" + side, null)}; return faces; }
    }
    public static final class UnknownTexture implements ITexture { @Override public boolean isValidTexture() { return true; } public void renderXPos() { throw new AssertionError("Custom rendering is forbidden"); } }
    public static final class Container implements IIconContainer {
        private final IIcon base, overlay;
        Container(String base, String overlay) { this.base = new Icon(base); this.overlay = overlay == null ? null : new Icon(overlay); }
        @Override public IIcon getIcon() { return base; }
        @Override public IIcon getOverlayIcon() { return overlay; }
        @Override public ResourceLocation getTextureFile() { return new ResourceLocation("minecraft", "textures/atlas/blocks.png"); }
    }
    public static final class Icon implements IIcon {
        private final String name;
        Icon(String name) { this.name = name; }
        @Override public int getIconWidth() { return 16; }
        @Override public int getIconHeight() { return 16; }
        @Override public float getMinU() { return 0; }
        @Override public float getMaxU() { return 1; }
        @Override public float getInterpolatedU(double value) { return (float) value / 16; }
        @Override public float getMinV() { return 0; }
        @Override public float getMaxV() { return 1; }
        @Override public float getInterpolatedV(double value) { return (float) value / 16; }
        @Override public String getIconName() { return name; }
    }
}
