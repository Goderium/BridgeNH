package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;

import blockrenderer6343.client.renderer.WorldSceneRenderer;
import blockrenderer6343.client.utils.BRUtil;
import blockrenderer6343.integration.gregtech.GTGuiMultiblockHandler;
import codechicken.nei.PositionedStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Direct model queries, native candidate pagination and Unicode continuation without scans or client rendering. */
public final class NeiPreviewDetailSelfTest {
    private static int checks;
    public static int run() { checks = 0; verify(); return checks; }
    public static void main(String[] args) { System.out.println("NEI preview detail fixtures passed: " + run()); }
    private static JsonObject args(String target) { JsonObject value = new JsonObject(); value.addProperty("target", target); return value; }
    private static JsonObject xyz(int x, int y, int z) { JsonObject value = new JsonObject(); value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z); return value; }
    private static JsonObject read(NeiPreviewProvider.Scene scene, JsonObject args, int offset, int limit) { return NeiPreviewDetailProvider.readModel(scene, null, args, offset, limit, Long.MAX_VALUE, false); }

    private static void verify() {
        SelectionAccess gui = new SelectionAccess(); WorldSceneRenderer renderer = new WorldSceneRenderer();
        ItemStack trigger = new ItemStack(new FixtureItem(), 1, 0); AtomicInteger callbacks = new AtomicInteger();
        gui.fixtureInit(renderer, trigger, values -> callbacks.incrementAndGet());
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.gui = gui; scene.handler = new Object(); scene.controller = new Object(); scene.recipeIndex = 0; scene.built = true;
        scene.renderer = renderer; scene.world = renderer.world; scene.blocks = renderer.world.blockMap; scene.metadata = renderer.world.blockMetaMap; scene.tiles = renderer.world.tileMap;
        scene.rendered = renderer.renderedBlocks; scene.translucent = renderer.renderTranslucentBlocks; scene.elements = Collections.emptyMap();
        long packed = NeiPreviewProvider.pack(-2, 67, 4); renderer.world.blockMap.put(packed, new FixtureBlock()); renderer.world.blockMetaMap.put(packed, 5);
        scene.hatchDots = Collections.singletonMap(packed, 3); String longText = repeat("水\ud83d\ude80", 5000);
        scene.hatchHints = Collections.singletonMap(3, longText);
        List<PositionedStack> groups = new ArrayList<>(); FixtureItem item = new FixtureItem();
        for (int i = 0; i < 70; i++) {
            ItemStack[] choices = new ItemStack[70];
            for (int j = 0; j < choices.length; j++) { choices[j] = new ItemStack(item, j + 1, j); NBTTagCompound nbt = new NBTTagCompound(); nbt.setString("fixture", (i == 4 && j == 67 ? longText : "group") + i + '/' + j); choices[j].setTagCompound(nbt); }
            groups.add(new PositionedStack(choices, i * 17, i));
        }
        scene.candidates = groups;
        JsonObject noSelection = read(scene, args("preview_candidate_groups"), 0, 16);
        check(noSelection.get("total").getAsInt() == 0 && noSelection.getAsJsonObject("data").get("candidateStatus").getAsString().equals("no_selected_block"), "unselected native cache is explicitly empty");
        JsonObject selectedQuery = args("preview_block"); expect("not_present", () -> read(scene, selectedQuery, 0, 1));
        gui.select(-2, 67, 4); int scans = BRUtil.fixtureScans; int calls = callbacks.get();
        String context = NeiPreviewProvider.contextOf(scene), frame = NeiPreviewProvider.frameContextOf(scene);
        JsonObject block = read(scene, args("preview_block"), 0, 1);
        check(block.getAsJsonObject("data").getAsJsonObject("block").get("metadata").getAsInt() == 5, "selected block query preserves actual metadata");
        check(block.getAsJsonObject("data").get("candidateCacheMatchesPosition").getAsBoolean(), "selected block cache relationship is explicit");
        JsonObject explicit = args("preview_block"); explicit.addProperty("previewBlockSource", "position"); explicit.add("previewPosition", xyz(-2, 67, 4));
        check(read(scene, explicit, 0, 1).getAsJsonObject("data").getAsJsonObject("position").equals(xyz(-2, 67, 4)), "explicit O(1) coordinate query preserves fake-world frame");
        explicit.getAsJsonObject("previewPosition").addProperty("other", 1); expect("invalid_args", () -> read(scene, explicit, 0, 1));
        explicit.getAsJsonObject("previewPosition").remove("other"); explicit.getAsJsonObject("previewPosition").remove("y"); expect("invalid_args", () -> read(scene, explicit, 0, 1));
        JsonObject forbiddenPosition = args("preview_block"); forbiddenPosition.add("previewPosition", xyz(-2, 67, 4)); expect("invalid_args", () -> read(scene, forbiddenPosition, 0, 1));
        JsonObject missing = args("preview_block"); missing.addProperty("previewBlockSource", "position"); missing.add("previewPosition", xyz(0, 0, 0)); expect("not_found", () -> read(scene, missing, 0, 1));
        expect("invalid_args", () -> read(scene, args("preview_block"), 0, 2));
        int seen = 0;
        for (int offset = 0; offset < 70;) {
            JsonObject page = read(scene, args("preview_candidate_groups"), offset, 16); JsonArray entries = page.getAsJsonObject("data").getAsJsonArray("entries");
            for (int i = 0; i < entries.size(); i++) check(entries.get(i).getAsJsonObject().get("groupIndex").getAsInt() == seen++, "native group indices survive pagination");
            check(page.get("nextOffset").getAsInt() > offset, "group continuation makes progress"); offset = page.get("nextOffset").getAsInt();
        }
        check(seen == 70, "all native cached groups are pageable");
        JsonObject items = args("preview_candidate_items"); items.addProperty("groupIndex", 4);
        JsonObject itemPage = read(scene, items, 64, 16); check(itemPage.get("complete").getAsBoolean() && itemPage.getAsJsonObject("data").getAsJsonArray("entries").size() == 6, "alternative tail is complete without cycling");
        check(itemPage.getAsJsonObject("data").getAsJsonArray("entries").get(0).getAsJsonObject().getAsJsonObject("item").get("metadata").getAsInt() == 64, "alternative identity comes from stored ItemStack");
        JsonObject hint = args("preview_hint_text"); StringBuilder hintResult = new StringBuilder();
        for (int offset = 0; offset < 10000;) { JsonObject page = read(scene, hint, offset, 2048); hintResult.append(page.getAsJsonObject("data").get("text").getAsString()); offset = page.get("nextOffset").getAsInt(); }
        check(hintResult.toString().equals(longText), "cached hatch descriptions page by Unicode codepoints without splitting surrogate pairs");
        JsonObject nbtArgs = args("preview_candidate_nbt"); nbtArgs.addProperty("groupIndex", 4); nbtArgs.addProperty("alternativeIndex", 67);
        String expectedNbt = groups.get(4).items[67].getTagCompound().toString(); StringBuilder nbtResult = new StringBuilder(); int offset = 0;
        while (true) { JsonObject page = read(scene, nbtArgs, offset, 2048); nbtResult.append(page.getAsJsonObject("data").get("text").getAsString()); if (!page.get("hasMore").getAsBoolean()) break; offset = page.get("nextOffset").getAsInt(); }
        check(nbtResult.toString().equals(expectedNbt), "candidate NBT is complete through explicit text continuation");
        JsonObject tooltip = args("preview_candidate_tooltip_lines"); tooltip.addProperty("groupIndex", 4); tooltip.addProperty("alternativeIndex", 67);
        JsonObject lines = read(scene, tooltip, 0, 64); int longLine = -1;
        for (com.google.gson.JsonElement entry : lines.getAsJsonObject("data").getAsJsonArray("entries")) if (entry.getAsJsonObject().get("textTruncated").getAsBoolean()) longLine = entry.getAsJsonObject().get("lineIndex").getAsInt();
        check(item.tooltipCalls == 1 && longLine >= 0, "only an explicit candidate Tooltip target invokes real ItemStack tooltip");
        tooltip.addProperty("target", "preview_candidate_tooltip_text"); tooltip.addProperty("lineIndex", longLine); String expectedTooltip = item.lastLine; StringBuilder tooltipResult = new StringBuilder(); offset = 0;
        while (true) { JsonObject page = read(scene, tooltip, offset, 2048); tooltipResult.append(page.getAsJsonObject("data").get("text").getAsString()); if (!page.get("hasMore").getAsBoolean()) break; offset = page.get("nextOffset").getAsInt(); }
        check(tooltipResult.toString().equals(expectedTooltip), "full actual Tooltip line supports Unicode continuation");
        check(context.equals(NeiPreviewProvider.contextOf(scene)) && frame.equals(NeiPreviewProvider.frameContextOf(scene)) && BRUtil.fixtureScans == scans && callbacks.get() == calls, "all reads preserve selection, controls and native scan/callback counts");
        groups.get(0).items[0].stackSize++; check(!context.equals(NeiPreviewProvider.contextOf(scene)), "bounded cached candidate count changes invalidate continuation");
        frame = NeiPreviewProvider.frameContextOf(scene); context = NeiPreviewProvider.contextOf(scene); NeiPreviewResources.changedForFixture();
        check(!frame.equals(NeiPreviewProvider.frameContextOf(scene)) && !context.equals(NeiPreviewProvider.contextOf(scene)), "resource epoch invalidates data and completed-frame continuations");
        JsonObject soft = NeiPreviewDetailProvider.readModel(scene, null, args("preview_candidate_groups"), 0, 16, 0, false);
        check(soft.get("nextOffset").getAsInt() == 1 && soft.get("softBudgetPartial").getAsBoolean(), "soft budget still advances one candidate and reports continuation");
        scene.candidates = null; scene.candidateStatus = "fixture missing"; check(read(scene, args("preview_candidate_groups"), 0, 16).get("status").getAsString().equals("not_present"), "missing cache is explicit");
    }
    private static String repeat(String text, int count) { StringBuilder result = new StringBuilder(); for (int i = 0; i < count; i++) result.append(text); return result.toString(); }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable runnable) { checks++; try { runnable.run(); } catch (BridgeException error) { if (code.equals(error.code)) return; throw new AssertionError("Expected " + code + ", got " + error.code, error); } throw new AssertionError("Expected " + code); }
    public static final class SelectionAccess extends GTGuiMultiblockHandler { void select(int x, int y, int z) { selectedBlock.set(x, y, z); } }
    public static final class FixtureBlock extends Block { public FixtureBlock() { super(Material.rock); } }
    public static final class FixtureItem extends Item {
        int tooltipCalls; String lastLine;
        @Override public String getItemStackDisplayName(ItemStack stack) { return "fixture"; }
        @Override public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) { tooltipCalls++; lastLine = stack.getTagCompound().getString("fixture"); lines.add(lastLine); }
    }
}
