package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.common.machine.MachineDescriptionSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Public API fixtures only, no client construction, window access, or runtime authentication. */
public class StructuredInfoSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        plans();
        tooltips();
        checks += MachineDescriptionSelfTest.run();
        checks += PieceBoundsSelfTest.run();
        return checks;
    }
    public static void main(String[] args) { System.out.println("Structured info fixtures passed: " + run()); }

    private static void plans() {
        JsonObject empty = new JsonObject();
        StructureAdapter.Piece discovery = StructureAdapter.piece("unknown.controller", empty);
        check(discovery.name.isEmpty() && discovery.offsets == null, "missing piece requests generic discovery without default offsets");
        JsonObject incomplete = new JsonObject(); incomplete.addProperty("piece", "fixture"); incomplete.addProperty("offsetA", 0);
        expect("invalid_args", () -> StructureAdapter.piece("unknown", incomplete));
        JsonObject args = parameters();
        Object tile = new Object(), meta = new Object();
        Facing facing = new Facing();
        IStructureElement casing = new IStructureElement() {
            @Override public List<String> getDescription(Object context) { return Collections.singletonList("Casing"); }
        };
        Element hatch = new Element("Input Bus");
        IStructureElement navigation = new Element("navigation") {
            @Override public boolean isNavigating() { return true; }
            @Override public boolean resetA() { return true; }
            @Override public boolean resetB() { return true; }
            @Override public int getStepA() { return 2; }
            @Override public int getStepB() { return 1; }
        };
        Definition definition = new Definition(new IStructureElement[] {casing, navigation, hatch, casing});
        StructureAdapter adapter = new StructureAdapter();
        List<JsonObject> positions = new ArrayList<JsonObject>();
        int cursor = 0, loops = 0;
        JsonObject page;
        do {
            args.addProperty("offset", cursor);
            page = adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime());
            check(page.get("visited").getAsInt() >= 1, "bounded page makes progress");
            check(page.toString().getBytes(StandardCharsets.UTF_8).length < StructureAdapter.BYTE_LIMIT, "bounded result bytes");
            for (Object entry : page.getAsJsonArray("positions")) positions.add(((com.google.gson.JsonElement) entry).getAsJsonObject());
            cursor = page.get("nextOffset").getAsInt();
            if (++loops > 10) throw new AssertionError("pagination failed to terminate");
        } while (page.get("hasMore").getAsBoolean());
        check(positions.size() == 3, "navigation entries are not physical blocks");
        JsonObject first = positions.get(0), second = positions.get(1);
        check(first.get("a").getAsInt() == -1 && first.get("b").getAsInt() == -2 && first.get("c").getAsInt() == -3, "negative controller ABC offsets");
        check(first.get("x").getAsInt() == 7 && first.get("y").getAsInt() == 22 && first.get("z").getAsInt() == 31, "public facing transform used");
        check(second.get("a").getAsInt() == 1 && second.get("b").getAsInt() == -1, "navigation reset and steps honored");
        check(!first.get("loaded").getAsBoolean() && !first.has("observed"), "unloaded is unknown, not air");
        check(second.getAsJsonArray("descriptionKeys").get(0).getAsString().equals("Input Bus"), "public hatch description preserved");
        check(!first.has("descriptionError"), "non-public anonymous elements work through public interface Method");
        check(!first.has("valid") && !page.has("formed"), "planning does not masquerade as validation");
        args.addProperty("offset", 1);
        JsonObject repeated = adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime());
        check(repeated.get("visited").getAsInt() > 0, "issued cursor is repeatable");
        JsonObject reset = parameters();
        adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, reset, System.nanoTime());
        repeated = adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime());
        check(repeated.get("visited").getAsInt() > 0 && repeated.get("nonAtomic").getAsBoolean(), "offset zero reuses current plan without destroying issued checkpoints");
        check("not_queried_unloaded".equals(first.get("candidateCoverage").getAsString()), "unloaded positions do not invoke candidate world getters");
        expect("stale_cursor", () -> adapter.readPlan(null, new Object(), meta, definition, facing, 10, 20, 30, args, System.nanoTime()));
        adapter.invalidate();
        expect("stale_cursor", () -> adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime()));
        facing.rotation = "CLOCKWISE";
        expect("stale_cursor", () -> adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime()));
        facing.rotation = "NORMAL";
        args.addProperty("offset", 0); args.addProperty("query", "Input Bus");
        List<JsonObject> hatchPositions = new ArrayList<JsonObject>(); loops = 0;
        do {
            page = adapter.readPlan(null, tile, meta, definition, facing, 10, 20, 30, args, System.nanoTime());
            for (Object entry : page.getAsJsonArray("positions")) hatchPositions.add(((com.google.gson.JsonElement) entry).getAsJsonObject());
            args.addProperty("offset", page.get("nextOffset").getAsInt());
            if (++loops > 10) throw new AssertionError("filtered pagination failed to terminate");
        } while (page.get("hasMore").getAsBoolean());
        check(hatchPositions.size() == 1, "description filtering locates hatch planning positions");
        JsonObject delayed = parameters();
        page = new StructureAdapter().readPlan(null, tile, meta, definition, facing, 10, 20, 30, delayed,
                System.nanoTime() - StructureAdapter.SOFT_BUDGET_NANOS * 2);
        check(page.get("visited").getAsInt() == 1 && page.getAsJsonObject("budget").get("softBudgetExceeded").getAsBoolean(), "soft deadline still makes one step and reports overrun");
        IStructureElement[] many = new IStructureElement[StructureAdapter.COMPILED_LIMIT + 1]; Arrays.fill(many, casing);
        expect("too_large", () -> new StructureAdapter().readPlan(null, tile, meta, new Definition(many), facing, 10, 20, 30, parameters(), System.nanoTime()));
    }

    private static void tooltips() {
        JsonObject empty = GuiDataAdapter.inspect(null, null, new JsonObject());
        check(empty.getAsJsonArray("widgets").size() == 0 && empty.getAsJsonObject("coverage").get("aeVirtualSlots").getAsString().contains("unavailable"), "missing GUI data remains explicit and no hover fallback is used");
        List<String> lines = new ArrayList<String>();
        for (int i = 0; i < 40; i++) lines.add(String.join("", Collections.nCopies(600, "x")));
        JsonObject text = new JsonObject(); GuiDataAdapter.text(text, lines);
        check(text.getAsJsonArray("tooltip").size() == 32, "tooltip line cap");
        check(text.getAsJsonArray("tooltip").get(0).getAsString().length() == 512, "tooltip character cap");
        check(text.get("tooltipTotalLines").getAsInt() == 40 && text.get("tooltipTruncated").getAsBoolean(), "tooltip truncation is explicit");
        JsonObject args = new JsonObject(); args.addProperty("advanced", "true");
        expect("invalid_args", () -> GuiDataAdapter.booleanArg(args, "advanced", false));
        check(!GuiDataAdapter.booleanArg(new JsonObject(), "advanced", false), "advanced defaults to false");
        FakeItem item = new FakeItem();
        ItemStack stack = new ItemStack(item, 1, 7);
        stack.setTagCompound(new NBTTagCompound()); stack.getTagCompound().setString("fixture", "real NBT");
        JsonObject result = new JsonObject(); GuiDataAdapter.itemTooltip(result, stack, null, true);
        check(item.called && item.advanced && item.seen != stack && ItemStack.areItemStacksEqual(item.seen, stack), "tooltip invokes the public getter on an independent full actual-stack copy");
        check(result.getAsJsonArray("tooltip").toString().contains("real NBT"), "current NBT context reaches tooltip getter");
        check(result.getAsJsonObject("item").get("hasTag").getAsBoolean(), "item identity preserves tag presence");
        Item mutating = new FakeItem() {
            @Override public String getItemStackDisplayName(ItemStack value) {
                value.getTagCompound().setString("clientInitialized", "display"); return "mutating getter";
            }
            @Override public void addInformation(ItemStack value, EntityPlayer player, List lines, boolean advanced) {
                value.getTagCompound().setString("clientInitialized", "tooltip"); lines.add("initialized on copy");
            }
        };
        ItemStack protectedStack = new ItemStack(mutating, 1, 0); protectedStack.setTagCompound(new NBTTagCompound());
        protectedStack.getTagCompound().setString("fixture", "preserved");
        GuiDataAdapter.itemTooltip(new JsonObject(), protectedStack, null, false);
        check(!protectedStack.getTagCompound().hasKey("clientInitialized") && "preserved".equals(protectedStack.getTagCompound().getString("fixture")),
                "Native display-name and Tooltip initialization cannot mutate actual inventory NBT through bridge reads");

    }

    private static JsonObject parameters() {
        JsonObject args = new JsonObject(); args.addProperty("piece", "fixture");
        args.addProperty("offsetA", 1); args.addProperty("offsetB", 2); args.addProperty("offsetC", 3); args.addProperty("limit", 1); return args;
    }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }
    public static class Definition implements IStructureDefinition {
        final IStructureElement[] elements;
        public Definition(IStructureElement[] elements) { this.elements = elements; }
        @Override public IStructureElement[] getStructureFor(String name) { return elements; }
    }
    public static class Element implements IStructureElement {
        final String description;
        public Element(String description) { this.description = description; }
        @Override public List<String> getDescription(Object context) { return Collections.singletonList(description); }
    }
    public static class Facing {
        String rotation = "NORMAL";
        public String getDirection() { return "WEST"; }
        public String getRotation() { return rotation; }
        public String getFlip() { return "HORIZONTAL"; }
        public void getWorldOffset(int[] abc, int[] xyz) { xyz[0] = abc[2]; xyz[1] = -abc[1]; xyz[2] = -abc[0]; }
    }
    public static class FakeItem extends Item {
        public boolean called, advanced; public ItemStack seen;
        @Override public String getItemStackDisplayName(ItemStack stack) { return "fixture"; }
        @Override public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
            called = true; this.advanced = advanced; seen = stack; lines.add(stack.getTagCompound().getString("fixture"));
        }
    }
}
