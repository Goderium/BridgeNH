package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

/** Actual stack/NBT preservation, fixed registration fallback and explicit uncertainty. */
public final class FluidContainerDataSelfTest {
    private static int checks;
    private static Fluid fluid, otherFluid;
    private static final String NAME = "bridge_fluid_container_fixture.fluid", OTHER = "bridge_fluid_container_fixture.other";
    private static final String ITEM = "bridge_fluid_container_fixture:item";
    public static int run() {
        checks = 0;
        if (FluidRegistry.getFluid(NAME) == null) FluidRegistry.registerFluid(new Fluid(NAME));
        if (FluidRegistry.getFluid(OTHER) == null) FluidRegistry.registerFluid(new Fluid(OTHER));
        fluid = FluidRegistry.getFluid(NAME); otherFluid = FluidRegistry.getFluid(OTHER);
        try { interfaceContainers(); fixedContainers(); requestedCapacity(); failures(); hooks(); tooltipRouting(); checks += NativeFluidRegistrySelfTest.run(); }
        finally { FluidContainerRegistry.resetForTest(); }
        return checks;
    }

    private static void interfaceContainers() {
        FluidContainerRegistry.resetForTest();
        Container item = new Container(); item.capacity = 12345;
        ItemStack stack = tagged(item, 3, 7, 11112, 12345);
        NBTTagCompound original = (NBTTagCompound) stack.getTagCompound().copy();
        JsonObject read = FluidContainerDataAdapter.read(stack);
        check(read.get("schemaVersion").getAsInt() == 1 && read.get("scope").getAsString().equals("observed_stack_copy_public_api_read")
                && read.get("source").getAsString().equals("public_container_item_api"), "Versioned interface result identifies the observed stack-copy scope");
        check(read.get("available").getAsBoolean() && !read.get("empty").getAsBoolean()
                && read.getAsJsonObject("fluid").get("amount").getAsInt() == 11112
                && read.getAsJsonObject("capacity").get("value").getAsInt() == 12345, "Actual NBT fluid and capacity retain raw per-stack API amounts");
        check(read.get("itemHasTag").getAsBoolean() && read.getAsJsonObject("fluid").get("hasTag").getAsBoolean()
                && read.getAsJsonObject("fluid").get("fluidName").getAsString().equals(NAME), "Container NBT and returned fluid NBT are reported separately");
        check(!read.get("probeMutatedCopy").getAsBoolean() && FluidContainerRegistry.callsForTest == 0
                && stack.stackSize == 3 && stack.getMetadata() == 7 && stack.getTagCompound().equals(original), "Positive interface capacity is authoritative and original stack stays unchanged");
        check(item.fluidProbe != stack && item.capacityProbe != stack && item.fluidProbe != item.capacityProbe
                && item.fluidProbe.getTagCompound() != stack.getTagCompound(), "Every getter receives an independent deep stack snapshot");

        item.mutate = true; read = FluidContainerDataAdapter.read(stack);
        check(read.get("probeMutatedCopy").getAsBoolean() && read.getAsJsonObject("capacity").get("value").getAsInt() == 12345
                && item.capacityProbe.stackSize == 3 && item.capacityProbe.getMetadata() == 7
                && item.capacityProbe.getTagCompound().getCompoundTag("nested").getString("marker").equals("original"), "Mutation by the fluid getter cannot contaminate the capacity getter");
        check(stack.stackSize == 3 && stack.getMetadata() == 7 && stack.getTagCompound().equals(original), "Probe mutation cannot alter original metadata/count or nested NBT");

        item = new Container(); item.createTag = true; item.capacity = 8192;
        stack = new ItemStack(item, 2, 19); read = FluidContainerDataAdapter.read(stack);
        check(read.get("available").getAsBoolean() && read.get("empty").getAsBoolean() && read.get("fluid").isJsonNull()
                && !read.get("itemHasTag").getAsBoolean() && read.get("probeMutatedCopy").getAsBoolean(), "IC2-style getter-added probe NBT does not become original item NBT");
        check(!stack.hasTagCompound() && !item.capacityProbe.hasTagCompound() && item.capacityProbe.stackSize == 2
                && item.capacityProbe.getMetadata() == 19, "Tagless original remains tagless and the second getter sees the same original state");

        item = new Container(); item.capacity = Integer.MAX_VALUE; item.returned = new FluidStack(fluid, Integer.MAX_VALUE);
        read = FluidContainerDataAdapter.read(new ItemStack(item, 64, 0));
        check(read.getAsJsonObject("capacity").get("value").getAsInt() == Integer.MAX_VALUE
                && read.getAsJsonObject("fluid").get("amount").getAsInt() == Integer.MAX_VALUE, "API integer limits remain exact without count multiplication");
    }

    private static void fixedContainers() {
        FluidContainerRegistry.resetForTest();
        Item item = new DisplayItem(); ItemStack filled = new ItemStack(item, 4, 7), empty = new ItemStack(item, 1, 8);
        FluidContainerRegistry.registerFluidContainer(new FluidStack(fluid, 4321), filled, empty);
        JsonObject read = FluidContainerDataAdapter.read(filled);
        check(read.get("available").getAsBoolean() && read.get("source").getAsString().equals("public_fluid_container_registry")
                && !read.get("empty").getAsBoolean() && read.getAsJsonObject("fluid").get("amount").getAsInt() == 4321
                && read.getAsJsonObject("capacity").get("value").getAsInt() == 4321, "Fixed filled registration reports its exact registered amount rather than 1000 or count-scaled totals");
        read = FluidContainerDataAdapter.read(empty);
        check(read.get("available").getAsBoolean() && read.get("empty").getAsBoolean() && read.get("fluid").isJsonNull()
                && !read.getAsJsonObject("capacity").get("available").getAsBoolean()
                && read.getAsJsonObject("capacity").get("reason").getAsString().equals("registered_empty_capacity_requires_fluid_identity")
                && !read.getAsJsonObject("capacity").has("value"), "Registered empty container is known empty while unknown-fluid capacity is explicit unavailable");
        negative(FluidContainerDataAdapter.read(new ItemStack(new DisplayItem(), 1, 7)), "not_a_public_fluid_container");
        negative(FluidContainerDataAdapter.read(new ItemStack(item, 1, 9)), "not_a_public_fluid_container");

        Container gtStyle = new Container(); gtStyle.capacity = 0;
        ItemStack gtFilled = new ItemStack(gtStyle, 5, 11), gtEmpty = new ItemStack(gtStyle, 1, 12);
        FluidContainerRegistry.registerFluidContainer(new FluidStack(fluid, 7777), gtFilled, gtEmpty);
        read = FluidContainerDataAdapter.read(gtFilled);
        check(read.get("available").getAsBoolean() && read.getAsJsonObject("fluid").get("amount").getAsInt() == 7777
                && read.get("source").getAsString().equals("public_fluid_container_registry"), "An interface capacity of zero preserves GT-style registered fixed cells through generic fallback");
        check(FluidContainerDataAdapter.read(gtEmpty).get("empty").getAsBoolean(), "Interface capacity-zero registered empty containers remain observable");
        gtStyle.returned = new FluidStack(fluid, 7777);
        check(FluidContainerDataAdapter.read(gtFilled).get("available").getAsBoolean(), "Agreeing interface and fixed identities can share the registry capacity");
        gtStyle.returned = new FluidStack(otherFluid, 7777);
        negative(FluidContainerDataAdapter.read(gtFilled), "container_interface_registry_disagree");
        gtStyle.returned = new FluidStack(fluid, 7776);
        negative(FluidContainerDataAdapter.read(gtFilled), "container_interface_registry_disagree");
        gtStyle.returned = new FluidStack(fluid, 7777); gtStyle.returned.tag = new NBTTagCompound();
        negative(FluidContainerDataAdapter.read(gtFilled), "container_interface_registry_disagree");
        gtStyle.returned = new FluidStack(fluid, 7777);
        negative(FluidContainerDataAdapter.read(gtEmpty), "container_interface_registry_disagree");
        gtStyle.capacity = 9000; gtStyle.returned = new FluidStack(otherFluid, 4000);
        read = FluidContainerDataAdapter.read(gtFilled);
        check(read.get("source").getAsString().equals("public_container_item_api")
                && read.getAsJsonObject("fluid").get("fluidName").getAsString().equals(OTHER)
                && read.getAsJsonObject("capacity").get("value").getAsInt() == 9000, "A positive interface capacity prioritizes its actual state over fixed registration");
    }

    private static void failures() {
        FluidContainerRegistry.resetForTest();
        negative(FluidContainerDataAdapter.read(null), "item_stack_unavailable");
        negative(FluidContainerDataAdapter.read(new ItemStack(new DisplayItem(), 0, 0)), "item_stack_unavailable");
        Container item = new Container(); item.capacity = 0;
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "not_a_public_fluid_container");
        item.capacity = -1; negative(FluidContainerDataAdapter.read(new ItemStack(item)), "container_capacity_negative");
        item.capacity = 100; item.returned = new FluidStack(fluid, 101);
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "contained_amount_exceeds_capacity");
        item.returned = new FluidStack(fluid, 0); negative(FluidContainerDataAdapter.read(new ItemStack(item)), "contained_fluid_amount_not_positive");
        item.returned.amount = -1; negative(FluidContainerDataAdapter.read(new ItemStack(item)), "contained_fluid_amount_not_positive");
        item.returned = null; item.throwFluid = true;
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "public_fluid_container_read_failed");
        item.throwFluid = false; item.throwCapacity = true;
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "public_fluid_container_read_failed");
        item.throwCapacity = false;
        ItemStack large = tagged(item, 1, 0, 1, 100); large.getTagCompound().setString("tooLarge", repeat(513));
        int before = item.reads; negative(FluidContainerDataAdapter.read(large), "container_nbt_snapshot_truncated");
        check(item.reads == before && large.getTagCompound().getString("tooLarge").length() == 513, "Incomplete bounded original snapshots are refused before any getters and never rewritten");
        item.returned = new FluidStack(fluid, 1); item.returned.tag = new NBTTagCompound(); item.returned.tag.setString("tooLarge", repeat(513));
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "contained_fluid_nbt_snapshot_truncated");
        Fluid unknown = new Fluid("bridge_fluid_container_fixture.unknown"); FluidRegistry.registerFluid(unknown);
        item.returned = new FluidStack(unknown, 1); FluidRegistry.forgetForTest(unknown.getName());
        negative(FluidContainerDataAdapter.read(new ItemStack(item)), "contained_fluid_identity_unavailable");

        Item fixed = new DisplayItem(); FluidContainerRegistry.registerFluidContainer(new FluidStack(fluid, 500), new ItemStack(fixed, 1, 0), new ItemStack(fixed, 1, 1));
        FluidContainerRegistry.capacityOverrideForTest = 0;
        negative(FluidContainerDataAdapter.read(new ItemStack(fixed)), "registered_filled_capacity_unavailable");
        FluidContainerRegistry.capacityOverrideForTest = 499;
        negative(FluidContainerDataAdapter.read(new ItemStack(fixed)), "contained_amount_exceeds_capacity");
        FluidContainerRegistry.failForTest = true;
        negative(FluidContainerDataAdapter.read(new ItemStack(fixed)), "public_fluid_container_read_failed");
        FluidContainerRegistry.resetForTest();
    }

    private static void requestedCapacity() {
        FluidContainerRegistry.resetForTest();
        Item item = new DisplayItem(); ItemStack empty = new ItemStack(item, 5, 3);
        NBTTagCompound tag = new NBTTagCompound(), nested = new NBTTagCompound();
        nested.setString("marker", "untouched"); tag.setTag("nested", nested); empty.setTagCompound(tag);
        NBTTagCompound expected = (NBTTagCompound) tag.copy();
        FluidContainerRegistry.registerFluidContainer(new FluidStack(fluid, 4321), new ItemStack(item, 1, 1), empty);
        FluidContainerRegistry.registerFluidContainer(new FluidStack(otherFluid, 8765), new ItemStack(item, 1, 2), empty);
        JsonObject first = FluidContainerDataAdapter.read(empty, NAME), second = FluidContainerDataAdapter.read(empty, OTHER);
        requested(first, NAME, 4321); requested(second, OTHER, 8765);
        check(empty.stackSize == 5 && empty.getMetadata() == 3 && empty.getTagCompound().equals(expected)
                && !first.get("probeMutatedCopy").getAsBoolean(), "Requested-fluid registry capacity preserves observed count, metadata and nested NBT");
        capacityUnavailable(FluidContainerDataAdapter.read(empty, "unknown_exact_runtime_name"), "requested_container_fluid_unknown");
        Fluid third = new Fluid("bridge_fluid_container_fixture.no_tuple"); FluidRegistry.registerFluid(third);
        JsonObject missing = FluidContainerDataAdapter.read(empty, third.getName());
        capacityUnavailable(missing, "registered_empty_capacity_unavailable_for_fluid");
        check(missing.getAsJsonObject("capacity").get("fluidName").getAsString().equals(third.getName())
                && missing.getAsJsonObject("capacity").get("source").getAsString().equals("public_fluid_container_registry_getContainerCapacity"), "Zero tuple retains exact resolved query identity without inventing a capacity");
        JsonObject filled = FluidContainerDataAdapter.read(new ItemStack(item, 6, 1), OTHER);
        check(filled.getAsJsonObject("fluid").get("fluidName").getAsString().equals(NAME)
                && filled.getAsJsonObject("capacity").get("value").getAsInt() == 4321
                && !filled.getAsJsonObject("capacity").has("fluidName") && !filled.getAsJsonObject("capacity").has("source"), "A request cannot replace actual filled identity or add query-derived fields");
        Container dynamic = new Container(); dynamic.capacity = 9123;
        JsonObject actual = FluidContainerDataAdapter.read(new ItemStack(dynamic), OTHER);
        check(actual.getAsJsonObject("capacity").get("value").getAsInt() == 9123
                && actual.get("source").getAsString().equals("public_container_item_api")
                && !actual.getAsJsonObject("capacity").has("source") && !actual.getAsJsonObject("capacity").has("fluidName"), "Positive interface capacity keeps its actual getter without query evidence");
        FluidContainerRegistry.capacityOverrideForTest = 0;
        capacityUnavailable(FluidContainerDataAdapter.read(empty, NAME), "registered_empty_capacity_unavailable_for_fluid");
        FluidContainerRegistry.resetForTest();
    }

    private static void tooltipRouting() {
        FluidContainerRegistry.resetForTest();
        Item item = new DisplayItem(); ItemStack empty = new ItemStack(item, 4, 3);
        FluidContainerRegistry.registerFluidContainer(new FluidStack(fluid, 4321), new ItemStack(item, 1, 1), empty);
        InventoryBasic inventory = new InventoryBasic("fixture", false, 1); inventory.setInventorySlotContents(0, empty);
        Slot slot = new Slot(inventory, 0, 5, 7);
        net.minecraft.inventory.Container container = new net.minecraft.inventory.Container() {
            { addSlotToContainer(slot); }
            @Override public boolean canInteractWith(EntityPlayer player) { return true; }
        };
        TooltipScreen screen = new TooltipScreen(container); screen.width = 400; screen.height = 300;
        Minecraft client = new Minecraft(); JsonObject args = new JsonObject();
        args.addProperty("screenId", "current-screen"); args.addProperty("slot", slot.slotNumber); args.addProperty("containerFluid", NAME);
        JsonObject bySlot = GuiDataAdapter.tooltip(client, screen, args);
        requested(bySlot.getAsJsonObject("fluidContainer"), NAME, 4321);
        check(bySlot.get("slot").getAsInt() == slot.slotNumber && bySlot.get("screenId").getAsString().equals("current-screen")
                && bySlot.getAsJsonObject("item").get("count").getAsInt() == 4, "Slot query binds capacity to the current actual ordinary slot stack");
        args.remove("slot"); args.addProperty("x", 16); args.addProperty("y", 28);
        JsonObject byXY = GuiDataAdapter.tooltip(client, screen, args);
        requested(byXY.getAsJsonObject("fluidContainer"), NAME, 4321);
        check(byXY.get("slot").getAsInt() == slot.slotNumber, "XY query forwards requested fluid only after resolving the same actual ordinary slot");
        args.addProperty("x", 300); args.addProperty("y", 250);
        check(!GuiDataAdapter.tooltip(client, screen, args).has("fluidContainer"), "A geometric miss cannot invent actual-stack capacity evidence");
        for (com.google.gson.JsonElement invalid : Arrays.asList(JsonNull.INSTANCE, new JsonPrimitive(true), new JsonPrimitive(1),
                new JsonPrimitive(""), new JsonPrimitive(repeat(129)))) {
            args.add("containerFluid", invalid);
            try { GuiDataAdapter.tooltip(client, screen, args); throw new AssertionError("Expected invalid_args"); }
            catch (BridgeException error) { check(error.code.equals("invalid_args"), "containerFluid requires an exact typed 1..128 string"); }
        }
        args.addProperty("containerFluid", repeat(128)); args.addProperty("x", 16); args.addProperty("y", 28);
        capacityUnavailable(GuiDataAdapter.tooltip(client, screen, args).getAsJsonObject("fluidContainer"), "requested_container_fluid_unknown");
        args.remove("containerFluid");
        capacityUnavailable(GuiDataAdapter.tooltip(client, screen, args).getAsJsonObject("fluidContainer"), "registered_empty_capacity_requires_fluid_identity");
        FluidContainerRegistry.resetForTest();
    }

    private static void requested(JsonObject result, String name, int capacity) {
        JsonObject data = result.getAsJsonObject("capacity");
        check(result.get("available").getAsBoolean() && result.get("empty").getAsBoolean() && result.get("fluid").isJsonNull()
                && data.get("available").getAsBoolean() && data.get("value").getAsInt() == capacity
                && data.get("fluidName").getAsString().equals(name)
                && data.get("source").getAsString().equals("public_fluid_container_registry_getContainerCapacity")
                && !data.has("reason"), "Exact requested-fluid registration capacity stays separate from the actual empty contents");
    }
    private static void capacityUnavailable(JsonObject result, String reason) {
        JsonObject data = result.getAsJsonObject("capacity");
        check(result.get("available").getAsBoolean() && result.get("empty").getAsBoolean() && result.get("fluid").isJsonNull()
                && !data.get("available").getAsBoolean() && data.get("reason").getAsString().equals(reason) && !data.has("value"),
                "Unavailable requested capacity preserves actual registered empty state: " + reason);
    }
    public static final class TooltipScreen extends GuiContainer {
        TooltipScreen(net.minecraft.inventory.Container container) { super(container); }
        public int getGuiLeft() { return 10; }
        public int getGuiTop() { return 20; }
        @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) { }
    }

    private static void hooks() {
        Container item = new Container(); item.capacity = 6000;
        Item.itemRegistry.addObject(32230, ITEM, item);
        ItemStack actual = tagged(item, 4, 13, 2222, 9000);
        JsonObject summary = JsonData.item(actual).getAsJsonObject();
        check(!summary.has("fluidContainer") && item.reads == 0, "Generic inventory/NEI summary remains lightweight and calls no fluid getters");
        JsonObject args = new JsonObject(); args.addProperty("registryName", ITEM); args.addProperty("metadata", 13);
        JsonObject template = new ClientCatalog().item(args, null);
        check(template.get("source").getAsString().equals("registry_count_one_template") && template.get("countOneTemplate").getAsBoolean()
                && template.get("count").getAsInt() == 1 && !template.get("hasTag").getAsBoolean()
                && template.getAsJsonObject("fluidContainer").get("empty").getAsBoolean()
                && template.getAsJsonObject("fluidContainer").getAsJsonObject("capacity").get("value").getAsInt() == 6000, "Registered-item detail labels its constructed tagless count-one template explicitly");
        JsonObject gui = new JsonObject(); gui.addProperty("screenId", "current-screen"); gui.addProperty("slot", 27);
        GuiDataAdapter.itemTooltip(gui, actual, null, false);
        JsonObject container = gui.getAsJsonObject("fluidContainer");
        check(gui.get("screenId").getAsString().equals("current-screen") && gui.get("slot").getAsInt() == 27
                && gui.getAsJsonObject("item").get("count").getAsInt() == 4
                && container.getAsJsonObject("fluid").get("amount").getAsInt() == 2222
                && container.getAsJsonObject("capacity").get("value").getAsInt() == 9000
                && container.get("itemHasTag").getAsBoolean() && !gui.has("countOneTemplate"), "Actual GUI detail retains its screen/slot owner and reads actual count/metadata/full bounded NBT");
        GuiDataAdapter.itemTooltip(new JsonObject(), null, null, false);
        Item noncontainer = new DisplayItem(); Item.itemRegistry.addObject(32231, ITEM + "_not_container", noncontainer);
        args.addProperty("registryName", ITEM + "_not_container");
        negative(new ClientCatalog().item(args, null).getAsJsonObject("fluidContainer"), "not_a_public_fluid_container");
        JsonObject guiNoncontainer = new JsonObject(); GuiDataAdapter.itemTooltip(guiNoncontainer, new ItemStack(noncontainer), null, false);
        negative(guiNoncontainer.getAsJsonObject("fluidContainer"), "not_a_public_fluid_container");
    }

    private static ItemStack tagged(Container item, int count, int metadata, int amount, int capacity) {
        ItemStack stack = new ItemStack(item, count, metadata); NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("amount", amount); tag.setInteger("capacity", capacity);
        NBTTagCompound nested = new NBTTagCompound(); nested.setString("marker", "original"); tag.setTag("nested", nested);
        stack.setTagCompound(tag); return stack;
    }
    private static void negative(JsonObject read, String reason) {
        check(!read.get("available").getAsBoolean() && read.get("reason").getAsString().equals(reason)
                && !read.has("fluid") && !read.has("empty") && !read.getAsJsonObject("capacity").get("available").getAsBoolean()
                && !read.getAsJsonObject("capacity").has("value"), "Unavailable " + reason + " cannot invent fluid, emptiness or zero capacity");
    }
    private static String repeat(int length) { char[] chars = new char[length]; java.util.Arrays.fill(chars, 'a'); return new String(chars); }
    private static void check(boolean condition, String name) { checks++; if (!condition) throw new AssertionError(name); }
    private static class DisplayItem extends Item {
        @Override public String getItemStackDisplayName(ItemStack stack) { return "fluid fixture"; }
        @Override public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) { lines.add("fixture"); }
    }
    private static final class Container extends DisplayItem implements IFluidContainerItem {
        int capacity, reads; boolean mutate, createTag, throwFluid, throwCapacity; FluidStack returned;
        ItemStack fluidProbe, capacityProbe;
        @Override public FluidStack getFluid(ItemStack stack) {
            reads++; fluidProbe = stack;
            if (throwFluid) throw new IllegalStateException("fixture fluid getter");
            FluidStack result = returned;
            if (stack.hasTagCompound() && stack.getTagCompound().hasKey("amount")) {
                result = new FluidStack(fluid, stack.getTagCompound().getInteger("amount"));
                result.tag = (NBTTagCompound) stack.getTagCompound().getCompoundTag("nested").copy();
            }
            if (createTag && !stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
            if (mutate) {
                stack.stackSize = 99; stack.setMetadata(2);
                stack.getTagCompound().getCompoundTag("nested").setString("marker", "changed");
                stack.getTagCompound().setInteger("capacity", 1);
            }
            return result;
        }
        @Override public int getCapacity(ItemStack stack) {
            reads++; capacityProbe = stack;
            if (throwCapacity) throw new IllegalStateException("fixture capacity getter");
            return stack.hasTagCompound() && stack.getTagCompound().hasKey("capacity") ? stack.getTagCompound().getInteger("capacity") : capacity;
        }
        @Override public int fill(ItemStack stack, FluidStack fluid, boolean doFill) { throw new AssertionError("Fluid inspection must never fill"); }
        @Override public FluidStack drain(ItemStack stack, int amount, boolean doDrain) { throw new AssertionError("Fluid inspection must never drain"); }
    }
}
