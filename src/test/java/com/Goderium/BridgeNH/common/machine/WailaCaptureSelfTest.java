package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BoundedNbt;

import com.Goderium.BridgeNH.client.gui.WailaDataAdapter;
import com.Goderium.BridgeNH.client.render.FramebufferCapture;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mcp.mobius.waila.api.IWailaConfigHandler;
import mcp.mobius.waila.api.IWailaDataAccessor;
import mcp.mobius.waila.api.IWailaDataProvider;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;

/** Offline regression of complete provider payloads, error provenance, and padded framebuffer pixels. */
public class WailaCaptureSelfTest {
    private static int checks;
    public static int run() {
        checks = 0; completePayload(); limits(); errors(); pixels(); return checks;
    }
    private static void completePayload() {
        NBTTagCompound source = new NBTTagCompound();
        source.setString("routes", String.join("", Collections.nCopies(200, "fixture.route,")));
        source.setLong("quantity", 8457765750L);
        NBTTagCompound nested = source;
        for (int i = 0; i < 9; i++) {
            NBTTagCompound child = new NBTTagCompound(); nested.setTag("nested", child); nested = child;
        }
        nested.setLong("exact", Long.MAX_VALUE);
        NBTTagList entries = new NBTTagList();
        for (int i = 0; i < 40; i++) {
            NBTTagCompound entry = new NBTTagCompound(); entry.setInteger("slot", i); entries.appendTag(entry);
        }
        source.setTag("groups", entries);
        NBTTagList longs = new NBTTagList(); longs.appendTag(new NBTTagLong(Long.MIN_VALUE)); source.setTag("longs", longs);
        NBTTagList arrays = new NBTTagList(); arrays.appendTag(new NBTTagByteArray(new byte[96]));
        NBTTagList lists = new NBTTagList(); lists.appendTag(arrays); source.setTag("lists", lists);
        check(BoundedNbt.snapshot(source).truncated, "fixture exceeds external preview budgets");
        WailaProviderNbt snapshot = WailaProviderNbt.snapshot(source);
        check(snapshot.describe().get("complete").getAsBoolean(), "provider copy accepts long strings, deep compounds, 40 groups and nested lists");
        check(source.equals(snapshot.copy()), "complete payload preserves every native type and long value exactly");
        source.setString("routes", "later native update");
        NBTTagCompound first = snapshot.copy(); first.setString("routes", "provider mutated private copy");
        check(!snapshot.copy().getString("routes").equals(source.getString("routes")), "snapshot is detached from native cache updates");
        check(snapshot.copy().getString("routes").length() > 512, "provider mutation cannot damage next callback input");
        List<String> text = invoke(new NbtProvider(), snapshot, new ArrayList<>(), new JsonArray());
        check(text.get(0).equals("8457765750") && text.get(1).equals("40"), "real public-provider invocation sees full groups and exact long quantity");
    }
    private static void limits() {
        NBTTagCompound source = new NBTTagCompound(); source.setByteArray("large", new byte[WailaProviderNbt.BYTE_LIMIT + 1]);
        WailaProviderNbt large = WailaProviderNbt.snapshot(source);
        check(large.describe().get("reason").getAsString().equals("provider_nbt_byte_limit"), "oversized native arrays fail before copying");
        expect("waila_nbt_limit", large::copy);
        NBTTagCompound deep = new NBTTagCompound(), cursor = deep;
        for (int i = 0; i < 40; i++) { NBTTagCompound child = new NBTTagCompound(); cursor.setTag("child", child); cursor = child; }
        check(WailaProviderNbt.snapshot(deep).describe().get("reason").getAsString().equals("provider_nbt_depth_limit"), "deep payload is refused without recursive copy");
        NBTTagCompound wide = new NBTTagCompound(); NBTTagList entries = new NBTTagList();
        for (int i = 0; i < WailaProviderNbt.NODE_LIMIT; i++) entries.appendTag(new NBTTagCompound());
        wide.setTag("entries", entries);
        check(WailaProviderNbt.snapshot(wide).describe().get("reason").getAsString().equals("provider_nbt_node_limit"), "list cardinality preflight refuses oversized native data");
        expect("waila_nbt_unavailable", () -> WailaProviderNbt.snapshot(null).copy());
    }
    private static void errors() {
        JsonArray reports = new JsonArray(); List<String> accumulated = new ArrayList<>(); accumulated.add("original");
        List<String> output = invoke(new NbtProvider(), WailaProviderNbt.snapshot(null), accumulated, reports);
        check(output == accumulated && output.size() == 1, "failed provider preserves prior text");
        JsonObject failure = reports.get(0).getAsJsonObject();
        check(failure.getAsJsonArray("errorDetails").get(0).getAsJsonObject().get("code").getAsString().equals("waila_nbt_unavailable"), "reflection retains underlying bridge error code and frames");
        check(failure.getAsJsonArray("errorDetails").get(0).getAsJsonObject().getAsJsonArray("frames").size() > 0, "failure points to actual throw site");
        WailaDataAdapter.invokeProvider(new SecretFailure(), IWailaDataProvider.class, "getWailaBody", null, output,
            (IWailaDataAccessor) () -> new NBTTagCompound(), new IWailaConfigHandler() {}, IWailaDataAccessor.class,
            IWailaConfigHandler.class, reports, new int[1]);
        check(!reports.toString().contains("private-secret-nbt"), "provider messages are not copied into diagnostics");
        JsonObject result = new JsonObject(), sections = new JsonObject(), body = new JsonObject();
        body.addProperty("providerBudgetPartial", false); sections.add("body", body); result.add("sections", sections); result.add("providers", reports);
        WailaDataAdapter.summarizeCompleteness(result);
        check(!result.get("complete").getAsBoolean() && result.get("partial").getAsBoolean(), "body failure cannot appear complete despite remaining harvestability text");
        JsonArray clean = new JsonArray(); JsonObject provider = new JsonObject(); provider.addProperty("available", true); provider.addProperty("truncated", false); clean.add(provider);
        result.add("providers", clean); WailaDataAdapter.summarizeCompleteness(result);
        check(result.get("complete").getAsBoolean(), "full provider coverage is separate from preview truncation");
        provider.addProperty("truncated", true); WailaDataAdapter.summarizeCompleteness(result);
        check(result.get("partial").getAsBoolean(), "line truncation reports incomplete provider text");
        provider.addProperty("truncated", false); body.addProperty("providerBudgetPartial", true); WailaDataAdapter.summarizeCompleteness(result);
        check(result.get("partial").getAsBoolean(), "callback budget exhaustion reports incomplete provider text");
        JsonArray longReports = new JsonArray();
        List<String> longLines = invoke(new LongLineProvider(), WailaProviderNbt.snapshot(null), new ArrayList<>(), longReports);
        check(longLines.get(0).length() == 512 && longReports.get(0).getAsJsonObject().get("truncated").getAsBoolean(), "character clipping is visible even when line count fits");
    }
    private static List<String> invoke(IWailaDataProvider provider, WailaProviderNbt nbt, List<String> lines, JsonArray reports) {
        return WailaDataAdapter.invokeProvider(provider, IWailaDataProvider.class, "getWailaBody", null, lines,
            (IWailaDataAccessor) nbt::copy, new IWailaConfigHandler() {}, IWailaDataAccessor.class, IWailaConfigHandler.class, reports, new int[1]);
    }
    public static class NbtProvider implements IWailaDataProvider {
        public List<String> getWailaBody(ItemStack item, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            NBTTagCompound tag = accessor.getNBTData();
            lines.add(Long.toString(tag.getLong("quantity"))); lines.add(Integer.toString(tag.getTagList("groups", 10).tagCount())); return lines;
        }
    }
    public static class SecretFailure implements IWailaDataProvider {
        public List<String> getWailaBody(ItemStack item, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            throw new IllegalStateException("private-secret-nbt");
        }
    }
    public static class LongLineProvider implements IWailaDataProvider {
        public List<String> getWailaBody(ItemStack item, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config) {
            lines.add(String.join("", Collections.nCopies(600, "x"))); return lines;
        }
    }
    private static void pixels() {
        IntBuffer buffer = IntBuffer.wrap(new int[] {1,2,90,90,3,4,90,90,5,6,90,90,90,90,90,90});
        BufferedImage image = FramebufferCapture.pixelsToImage(buffer, 4, 4, 2, 3);
        check(image.getWidth() == 2 && image.getHeight() == 3, "visible framebuffer region is independent of padded texture dimensions");
        check((image.getRGB(0, 0) & 0xffffff) == 5 && (image.getRGB(1, 2) & 0xffffff) == 2, "texture crop uses full row stride and flips GL bottom-left origin");
        expect("unsupported", () -> FramebufferCapture.validateDimensions(0, 4, 1, 1));
        expect("unsupported", () -> FramebufferCapture.validateDimensions(4, 4, 5, 1));
        expect("unsupported", () -> FramebufferCapture.validateDimensions(8192, 8192, 8192, 8192));
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "expected error " + code); }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
