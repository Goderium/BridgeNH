package com.Goderium.BridgeNH.common.machine;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import gregtech.api.interfaces.ISecondaryDescribable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Private implementors are reached through public interfaces, with all action/modifier paths forbidden. */
public final class MachineDescriptionSelfTest {
    private static int checks;

    public static int run() {
        checks = 0;
        identityAndFixedBranches();
        missingAndFailure();
        bounds();
        return checks;
    }

    private static void identityAndFixedBranches() {
        Controller controller = new Controller();
        JsonObject data = new JsonObject(); MachineDescriptionAdapter.add(data, controller);
        JsonObject structure = data.getAsJsonObject("structureDescription");
        check(structure.get("available").getAsBoolean() && structure.get("total").getAsInt() == 2, "public StructureLib hints are available independently of tooltip selection");
        check(controller.amount == 1L && controller.seen != controller.original && controller.seen.getItem() == controller.original.getItem() && controller.seen.getMetadata() == 37, "structure getter receives a copy of the actual controller stack from getStackForm(1L)");
        check(controller.seenCount == 1 && controller.original.stackSize == 9 && "original".equals(controller.original.getTagCompound().getString("fixture")), "count normalization and a mutating public getter never change the original stack or NBT");
        check(structure.getAsJsonObject("controllerStack").get("count").getAsInt() == 1 && structure.getAsJsonObject("controllerStack").get("metadata").getAsInt() == 37 && structure.getAsJsonObject("controllerStack").get("hasTag").getAsBoolean(), "controller stack receipt preserves identity and count-1 context");
        check(data.getAsJsonObject("machineDescription").getAsJsonArray("entries").get(0).getAsString().equals("machine primary"), "machine description invokes the fixed public primary branch");
        check(data.getAsJsonObject("structureTooltip").getAsJsonArray("entries").get(0).getAsString().equals("complete structure secondary"), "complete structural tooltip invokes the fixed public secondary branch");
        check(controller.primaryCalls == 1 && controller.secondaryCalls == 1 && controller.structureCalls == 1 && controller.forbiddenCalls == 0, "description read never calls physical modifier selectors, construction or structure checks");
    }

    private static void missingAndFailure() {
        JsonObject result = MachineDescriptionAdapter.structure(new Object());
        check(!result.get("available").getAsBoolean() && result.getAsJsonArray("entries").size() == 0 && result.get("reason").getAsString().contains("not_implemented"), "unimplemented public construction interface is explicit unavailable");
        check(!MachineDescriptionAdapter.fixedDescription(new Object(), "getPrimaryDescription").get("available").getAsBoolean(), "missing fixed public tooltip interface is not replaced by physical-shift getDescription");
        Controller broken = new Controller() { @Override public String[] getStructureDescription(ItemStack stack) { throw new IllegalStateException("fixture"); } };
        result = MachineDescriptionAdapter.structure(broken);
        check(!result.get("available").getAsBoolean() && result.has("error") && result.getAsJsonArray("entries").size() == 0, "public description exception is bounded and explicitly unavailable");
        Controller nullStack = new Controller() { @Override public ItemStack getStackForm(long amount) { return null; } };
        result = MachineDescriptionAdapter.structure(nullStack);
        check(!result.get("available").getAsBoolean() && nullStack.structureCalls == 0, "missing controller stack does not invoke hints with a fabricated item");
        Controller nullDescription = new Controller() { @Override public String[] getPrimaryDescription() { return null; } };
        result = MachineDescriptionAdapter.fixedDescription(nullDescription, "getPrimaryDescription");
        check(!result.get("available").getAsBoolean() && "public_getter_returned_null".equals(result.get("reason").getAsString()), "null description is unavailable rather than a known empty tooltip");
        Controller emptyDescription = new Controller() { @Override public String[] getPrimaryDescription() { return new String[0]; } };
        result = MachineDescriptionAdapter.fixedDescription(emptyDescription, "getPrimaryDescription");
        check(result.get("available").getAsBoolean() && result.get("total").getAsInt() == 0 && !result.get("truncated").getAsBoolean(), "an actual empty public description is a known empty result");
        try { MachineDescriptionAdapter.fixedDescription(new Controller(), "getDescription"); throw new AssertionError("Modifier-dependent branch must be rejected"); }
        catch (BridgeException error) { check("unsupported".equals(error.code), "caller cannot select a modifier-dependent or arbitrary method"); }
    }

    private static void bounds() {
        Controller longDescription = new Controller() { @Override public String[] getSecondaryDescription() { String[] lines = new String[100]; Arrays.fill(lines, repeat(1000)); return lines; } };
        JsonObject result = MachineDescriptionAdapter.fixedDescription(longDescription, "getSecondaryDescription");
        check(result.get("total").getAsInt() == 100 && result.get("truncated").getAsBoolean() && result.getAsJsonArray("entries").size() <= MachineDescriptionAdapter.LINE_LIMIT, "description truncation preserves original total and line bound");
        check(result.getAsJsonArray("entries").get(0).getAsString().length() == MachineDescriptionAdapter.LINE_CHAR_LIMIT && result.get("returnedChars").getAsInt() <= MachineDescriptionAdapter.TOTAL_CHAR_LIMIT, "individual lines and aggregate character budget are both bounded");
        check(result.getAsJsonObject("bounds").get("maxEntries").getAsInt() == MachineDescriptionAdapter.LINE_LIMIT && result.toString().getBytes(StandardCharsets.UTF_8).length < 24 * 1024, "DTO reports actual bounds and remains under its response allocation");
    }

    private static class Controller implements IMetaTileEntity, IConstructable, ISecondaryDescribable {
        final ItemStack original; ItemStack seen; long amount; int seenCount, primaryCalls, secondaryCalls, structureCalls, forbiddenCalls;
        Controller() { original = new ItemStack(new FixtureItem(), 9, 37); original.setTagCompound(new NBTTagCompound()); original.getTagCompound().setString("fixture", "original"); }
        @Override public ItemStack getStackForm(long value) { amount = value; return original; }
        @Override public String[] getStructureDescription(ItemStack stack) { seen = stack; seenCount = stack.stackSize; structureCalls++; stack.stackSize = 20; stack.getTagCompound().setString("fixture", "copy mutated"); return new String[] {"§econstruction hint", "count-1 controller context"}; }
        @Override public String[] getPrimaryDescription() { primaryCalls++; return new String[] {"machine primary"}; }
        @Override public String[] getSecondaryDescription() { secondaryCalls++; return new String[] {"complete structure secondary"}; }
        @Override public void construct(ItemStack stack, boolean hintsOnly) { forbiddenCalls++; throw new AssertionError("Construction forbidden"); }
        @Override public boolean isDisplaySecondaryDescription() { forbiddenCalls++; throw new AssertionError("Physical modifier selection forbidden"); }
        public String[] getCurrentDescription() { forbiddenCalls++; throw new AssertionError("Modifier-dependent getter forbidden"); }
        public String[] getDescription() { forbiddenCalls++; throw new AssertionError("Modifier-dependent getter forbidden"); }
        public void checkMachine() { forbiddenCalls++; throw new AssertionError("Structure checking forbidden"); }
    }
    public static class FixtureItem extends Item { @Override public String getItemStackDisplayName(ItemStack stack) { return "fixture controller"; } }
    private static String repeat(int length) { char[] chars = new char[length]; Arrays.fill(chars, 'x'); return new String(chars); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
