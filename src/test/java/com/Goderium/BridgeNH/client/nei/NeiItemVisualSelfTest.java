package com.Goderium.BridgeNH.client.nei;

import blockrenderer6343.client.renderer.WorldSceneRenderer;
import codechicken.nei.PositionedStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.CommonMetaTileEntity;
import gregtech.api.render.ISBRInventoryContext;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.render.GTRenderedTexture;
import java.util.Collections;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.IIcon;
import net.minecraftforge.client.IItemRenderer;
import net.minecraftforge.client.MinecraftForgeClient;

/** Native candidate ItemStack inventory skins, ordinary pass icons and truthful custom-renderer gaps. */
public final class NeiItemVisualSelfTest {
    private static int checks;
    public static int run() { checks = 0; verify(); return checks; }
    public static void main(String[] args) { System.out.println("NEI item visual fixtures passed: " + run()); }
    private static JsonObject args(String target) { JsonObject value = new JsonObject(); value.addProperty("target", target); return value; }
    private static JsonObject read(ItemStack stack, int offset, int limit) { return NeiItemVisualProvider.readItem(stack, args("item_visuals"), offset, limit, Long.MAX_VALUE); }
    private static void verify() {
        MinecraftForgeClient.fixtureRenderer = null;
        MultiIconItem item = new MultiIconItem(); ItemStack stack = new ItemStack(item, 3, 73); NBTTagCompound tag = new NBTTagCompound(); tag.setString("skin", "actual_nbt"); stack.setTagCompound(tag);
        int seen = 0;
        for (int offset = 0; offset < 70;) {
            JsonObject page = read(stack, offset, 16); JsonArray entries = page.getAsJsonObject("data").getAsJsonArray("entries");
            for (int i = 0; i < entries.size(); i++) { JsonObject entry = entries.get(i).getAsJsonObject(); check(entry.get("renderPass").getAsInt() == seen, "native pass order is pageable"); check(entry.getAsJsonObject("icon").get("iconName").getAsString().equals("fixture:actual_nbt_" + seen), "icon getter receives actual candidate NBT"); seen++; }
            check(page.get("nextOffset").getAsInt() > offset, "native pass paging makes progress"); offset = page.get("nextOffset").getAsInt();
        }
        check(seen == 70 && item.iconCalls == 70, "all actual declared item passes are readable without arbitrary pass clamps");
        JsonObject first = read(stack, 1, 1).getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject();
        check(first.get("colorRGB").getAsInt() == 0x123457 && first.get("glint").getAsBoolean() && first.get("atlasResource").getAsString().equals("minecraft:textures/atlas/items.png"), "pass tint, glint and correct native atlas metadata are retained");
        SingleIconItem single = new SingleIconItem(); JsonObject singleData = read(new ItemStack(single, 1, 0), 0, 16).getAsJsonObject("data");
        check(single.indexCalls == 1 && singleData.getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("icon").get("iconName").getAsString().equals("fixture:single_index"), "single-pass GUI path uses exact ItemStack.getIconIndex rather than another pass getter");
        JsonObject nativeBlock = NeiPreviewDetailProvider.base("item_visuals"); NeiTextureSelfTest.FixtureBlock ordinaryBlock = new NeiTextureSelfTest.FixtureBlock();
        NeiItemVisualProvider.readBlockInventory(nativeBlock, stack, ordinaryBlock, 0, 64, Long.MAX_VALUE);
        check(nativeBlock.getAsJsonObject("data").getAsJsonArray("entries").size() == 6 && ordinaryBlock.lastMeta == 73, "ordinary inventory block path retains six actual side icons with raw stack metadata");
        check(nativeBlock.getAsJsonObject("data").get("blockRenderType").getAsInt() == ordinaryBlock.getRenderType() && nativeBlock.getAsJsonObject("data").get("visualCoverage").getAsString().contains("non-cube"), "render type is real and six-side icons do not claim complete block geometry");
        ItemMachines machines = new ItemMachines(); CommonMetaTileEntity meta = new CommonMetaTileEntity(); meta.fixtureTextures = new ITexture[6][];
        for (int side = 0; side < 6; side++) meta.fixtureTextures[side] = new ITexture[] {new GTRenderedTexture(new NeiTextureSelfTest.Container("fixture:hatch_side" + side, "fixture:hatch_overlay" + side), new short[] {44, 55, 66, 0}, false, false)};
        machines.fixtureMeta = meta; ItemStack candidate = new ItemStack(machines, 1, 2001); candidate.setTagCompound(tag);
        JsonObject gt = read(candidate, 0, 64); JsonArray faces = gt.getAsJsonObject("data").getAsJsonArray("entries");
        check(faces.size() == 6 && meta.fixtureReads == 1 && meta.fixtureFallbacks == 0, "GT candidate reads public native six-face inventory model, no construction or fallback");
        for (int side = 0; side < 6; side++) check(faces.get(side).getAsJsonObject().getAsJsonObject("overlayIcon").get("iconName").getAsString().equals("fixture:hatch_overlay" + side), "actual candidate hatch overlay is not the original scene casing skin");
        check(gt.getAsJsonObject("data").get("inventoryModelStatus").getAsString().equals("standard_native_inventory_path"), "standard published MTE model identifies exact native inventory path");
        meta.fixtureTextures = null; JsonObject fallback = read(candidate, 0, 64);
        check(meta.fixtureFallbacks == 6 && meta.fixtureNativeContext && fallback.getAsJsonObject("data").get("inventoryTextureSource").getAsString().contains("WEST,-1,true,false"), "null standard model uses exact public native fallback context without rendering");
        CustomInventoryMeta customMeta = new CustomInventoryMeta(); machines.fixtureMeta = customMeta; JsonObject customMte = read(candidate, 0, 64);
        check(!customMte.getAsJsonObject("data").get("textureModelComplete").getAsBoolean() && customMeta.fixtureFallbacks == 0, "custom MTE without model is explicit and its render callback is never called");
        MinecraftForgeClient.fixtureRenderer = new OpaqueRenderer(); JsonObject opaque = read(stack, 0, 16);
        check(opaque.getAsJsonObject("data").get("itemRendererClass").getAsString().equals(OpaqueRenderer.class.getName()) && !opaque.getAsJsonObject("data").get("textureModelComplete").getAsBoolean() && opaque.getAsJsonObject("data").getAsJsonArray("entries").size() == 0, "actual Forge custom renderer class is retained with explicit gap, no fallback icon claim");
        MinecraftForgeClient.fixtureRenderer = null;
        WorldSceneRenderer renderer = new WorldSceneRenderer(); NeiPreviewDetailSelfTest.SelectionAccess gui = new NeiPreviewDetailSelfTest.SelectionAccess(); gui.fixtureInit(renderer, stack, values -> { throw new AssertionError("Candidate callback forbidden"); }); gui.select(2, 70, 3);
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.gui = gui; scene.candidates = Collections.singletonList(new PositionedStack(new ItemStack[] {stack}, 0, 0));
        JsonObject request = args("preview_candidate_visuals"); request.addProperty("groupIndex", 0); request.addProperty("alternativeIndex", 0);
        JsonObject current = NeiItemVisualProvider.readCandidateModel(scene, request, 0, 1, Long.MAX_VALUE);
        check(current.getAsJsonObject("data").get("alternativeIndex").getAsInt() == 0 && current.getAsJsonObject("data").getAsJsonObject("candidateSourcePosition").get("y").getAsInt() == 70, "candidate visuals preserve exact stored alternative and selected-position provenance");
        gui.select(-100000, 0, -100000); check(NeiItemVisualProvider.readCandidateModel(scene, request, 0, 1, Long.MAX_VALUE).get("status").getAsString().equals("not_present"), "unselected candidate cannot be read as another position's visible item");
        check(!current.getAsJsonObject("data").get("pixelDataStored").getAsBoolean(), "inventory visuals retain no screenshot or pixel data");
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    public static final class MultiIconItem extends Item {
        int iconCalls;
        @Override public String getItemStackDisplayName(ItemStack stack) { return "native icon fixture"; }
        @Override public boolean requiresMultipleRenderPasses() { return true; }
        @Override public int getRenderPasses(int metadata) { return 70; }
        @Override public IIcon getIcon(ItemStack stack, int pass) { iconCalls++; return new NeiTextureSelfTest.Icon("fixture:" + stack.getTagCompound().getString("skin") + '_' + pass); }
        @Override public int getColorFromItemStack(ItemStack stack, int pass) { return 0x123456 + pass; }
        @Override public boolean hasEffect(ItemStack stack, int pass) { return pass == 1; }
    }
    public static final class SingleIconItem extends Item {
        int indexCalls;
        @Override public String getItemStackDisplayName(ItemStack stack) { return "single icon fixture"; }
        @Override public IIcon getIconIndex(ItemStack stack) { indexCalls++; return new NeiTextureSelfTest.Icon("fixture:single_index"); }
        @Override public IIcon getIcon(ItemStack stack, int pass) { throw new AssertionError("Single GUI path uses getIconIndex"); }
    }
    public static final class CustomInventoryMeta extends CommonMetaTileEntity { @Override public boolean renderInInventory(ISBRInventoryContext ctx) { throw new AssertionError("Custom inventory drawing forbidden"); } }
    public static final class OpaqueRenderer implements IItemRenderer {
        @Override public boolean handleRenderType(ItemStack stack, ItemRenderType type) { return type == ItemRenderType.INVENTORY; }
        @Override public boolean shouldUseRenderHelper(ItemRenderType type, ItemStack stack, ItemRendererHelper helper) { throw new AssertionError("Renderer helper callback forbidden"); }
        @Override public void renderItem(ItemRenderType type, ItemStack stack, Object... data) { throw new AssertionError("Actual item rendering forbidden"); }
    }
}
