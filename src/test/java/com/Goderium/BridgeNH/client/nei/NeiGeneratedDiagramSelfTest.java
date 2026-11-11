package com.Goderium.BridgeNH.client.nei;

import codechicken.nei.recipe.GuiRecipe;
import com.github.dcysteine.neicustomdiagram.api.diagram.Diagram;
import com.github.dcysteine.neicustomdiagram.api.diagram.DiagramGroup;
import com.github.dcysteine.neicustomdiagram.api.diagram.DiagramState;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.Component;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.DisplayComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.FluidComponent;
import com.github.dcysteine.neicustomdiagram.api.diagram.component.ImmutableNbtWrapper;
import com.github.dcysteine.neicustomdiagram.api.diagram.interactable.InteractiveComponentGroup;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Layout;
import com.github.dcysteine.neicustomdiagram.api.diagram.layout.Slot;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.TextFormatting;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.Tooltip;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.TooltipElement;
import com.github.dcysteine.neicustomdiagram.api.diagram.tooltip.TooltipLine;
import com.google.common.collect.ImmutableSortedSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

/** Installed generated API shapes, active union access, fluid identity and explicit copied NBT pages. */
public final class NeiGeneratedDiagramSelfTest {
    private static int checks;
    private static final String API = "com.github.dcysteine.neicustomdiagram.api.diagram.";
    public static int run() {
        checks = 0;
        Fluid fluid = new Fluid("r38_generated_fluid_fixture");
        check(FluidRegistry.registerFluid(fluid), "generated fluid fixture registers a unique identity");
        try { verify(fluid); }
        finally { FluidRegistry.forgetForTest(fluid.getName()); }
        return checks;
    }
    private static JsonObject request(int metadataIndex, int... path) {
        JsonObject args = new JsonObject(); args.addProperty("screenId", "generated-diagram-fixture");
        args.addProperty("section", "detail"); args.addProperty("target", "metadata_value");
        args.addProperty("recipeIndex", 0); args.addProperty("metadataIndex", metadataIndex);
        JsonArray values = new JsonArray(); for (int index : path) values.add(new JsonPrimitive(index)); args.add("valuePath", values);
        return args;
    }
    private static JsonObject read(GuiRecipe screen, JsonObject args) {
        return NeiViewAdapter.read(screen, null, args, "generated-diagram-session", System.nanoTime() + 60000000000L);
    }
    private static JsonObject value(GuiRecipe screen, String token, int metadataIndex, int... path) {
        JsonObject args = request(metadataIndex, path); args.addProperty("contextToken", token);
        JsonObject result = read(screen, args);
        check(result.get("contextToken").getAsString().equals(token), "generated definition token remains stable on model child reads");
        check(result.getAsJsonObject("data").has("value"), "selected generated model remains readable: " + result);
        return result.getAsJsonObject("data").getAsJsonObject("value");
    }
    private static void verify(Fluid fluid) {
        NBTTagCompound original = new NBTTagCompound(); StringBuilder longNbt = new StringBuilder("A\uD83D\uDE80B");
        for (int i = 0; i < 6000; i++) longNbt.append('x');
        original.setString("fluidFixture", longNbt.toString()); String expectedNbt = original.toString();
        ImmutableNbtWrapper wrapper = ImmutableNbtWrapper.generated(original);
        FluidComponent component = FluidComponent.generated(fluid, wrapper);
        check(wrapper.getClass().getName().equals(API + "component.AutoValue_ImmutableNbtWrapper")
                && Modifier.isFinal(wrapper.getClass().getModifiers()) && wrapper.getClass().getSuperclass() == ImmutableNbtWrapper.class,
                "NBT fixture uses the exact final generated wrapper class and direct API parent");
        check(component.getClass().getName().equals(API + "component.AutoValue_FluidComponent")
                && Modifier.isFinal(component.getClass().getModifiers()) && component.getClass().getSuperclass() == FluidComponent.class,
                "fluid fixture uses the exact final generated model class and direct API parent");
        try {
            check(wrapper.getClass().getMethod("get").getDeclaringClass() == ImmutableNbtWrapper.class
                    && Modifier.isProtected(wrapper.getClass().getDeclaredMethod("nbt").getModifiers()), "wrapper get copies through the API while the generated nbt accessor stays protected");
            check(component.getClass().getMethod("fluid").getDeclaringClass() == component.getClass()
                    && component.getClass().getMethod("nbtWrapper").getDeclaringClass() == component.getClass()
                    && component.getClass().getMethod("description").getDeclaringClass() == FluidComponent.class,
                    "fluid fixture matches audited generated getter and inherited description ownership");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        Tooltip empty = new Tooltip(Collections.<TooltipLine>emptyList());
        DisplayComponent defaultFluid = new DisplayComponent(component, empty, null);
        DisplayComponent overrideFluid = new DisplayComponent(component, empty, Integer.valueOf(2750));
        TextFormatting formatting = TextFormatting.generated(true, EnumChatFormatting.GREEN, EnumChatFormatting.BOLD);
        UnknownElement custom = new UnknownElement(); UnknownFormatting unknownFormatting = new UnknownFormatting();
        List<TooltipElement> elements = Arrays.asList(
                TooltipElement.generatedSpacing(17), TooltipElement.generatedText("A\uD83D\uDE80B generated text"),
                TooltipElement.generatedIcon(defaultFluid), TooltipElement.generatedDescription(component),
                TooltipElement.generatedFormatting(formatting), TooltipElement.unverifiedGeneratedForTest(), custom,
                TooltipElement.generatedFormatting(unknownFormatting));
        String[] suffixes = { "spacing", "text", "displayComponentIcon", "componentDescription", "formatting" };
        for (int i = 0; i < suffixes.length; i++) {
            Class<?> type = elements.get(i).getClass();
            check(type.getName().equals(API + "tooltip.AutoOneOf_TooltipElement$Impl_" + suffixes[i])
                    && Modifier.isFinal(type.getModifiers()) && Modifier.isPrivate(type.getModifiers())
                    && type.getSuperclass().getName().equals(API + "tooltip.AutoOneOf_TooltipElement$Parent_"),
                    "tooltip fixture matches actual private final generated " + suffixes[i] + " class and exact direct parent");
            try {
                for (String getter : new String[] { "type", "spacing", "text", "displayComponentIcon", "componentDescription", "formatting" }) {
                    Class<?> expectedOwner = getter.equals("type") || getter.equals(suffixes[i]) ? type : type.getSuperclass();
                    check(type.getMethod(getter).getDeclaringClass() == expectedOwner, "generated " + suffixes[i] + " getter owner matches audited " + getter);
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        Tooltip tooltip = new Tooltip(Collections.singletonList(new TooltipLine(elements)));
        Layout layout = new Layout(); layout.slots.put(new Layout.SlotKey("generated"), new Slot(tooltip));
        UnknownFluid unknownFluid = new UnknownFluid(); UnknownWrapper unknownWrapper = new UnknownWrapper();
        List<DisplayComponent> candidates = new ArrayList<DisplayComponent>(); candidates.add(defaultFluid); candidates.add(overrideFluid);
        candidates.add(new DisplayComponent(unknownFluid, empty, null));
        candidates.add(new DisplayComponent(FluidComponent.generated(fluid, unknownWrapper), empty, null));
        List<Object> interactables = Collections.<Object>singletonList(new InteractiveComponentGroup(candidates, tooltip));
        DiagramGroup handler = new DiagramGroup(Collections.singletonList(new Diagram(layout, interactables)), new DiagramState());
        GuiRecipe screen = new GuiRecipe(handler, Collections.singletonList(0));
        TooltipElement.resetGeneratedCallsForTest(); ImmutableNbtWrapper.resetCopiesForTest();
        JsonObject metadata = new JsonObject(); metadata.addProperty("screenId", "generated-diagram-fixture"); metadata.addProperty("section", "detail");
        metadata.addProperty("target", "metadata"); metadata.addProperty("recipeIndex", 0);
        JsonObject result = read(screen, metadata); String token = result.get("contextToken").getAsString();
        check(result.get("total").getAsInt() == 7 && ImmutableNbtWrapper.copiesForTest() == 0, "metadata/context traversal exposes generated definitions without copying NBT");
        JsonObject listRequest = request(3, 0, 0, 0); listRequest.addProperty("contextToken", token); result = read(screen, listRequest);
        JsonArray records = result.getAsJsonObject("data").getAsJsonArray("entries");
        check(records.size() == 8, "all five generated variants and three explicit boundary fixtures remain visible");
        String[] types = { "SPACING", "TEXT", "DISPLAY_COMPONENT_ICON", "COMPONENT_DESCRIPTION", "FORMATTING" };
        for (int i = 0; i < types.length; i++) {
            JsonObject record = records.get(i).getAsJsonObject();
            check(record.get("kind").getAsString().equals("element") && record.get("availability").getAsString().equals("stored_model")
                    && record.get("elementType").getAsString().equals(types[i]), "generated " + types[i] + " union is recognized instead of unknown");
        }
        check(records.get(0).getAsJsonObject().get("spacing").getAsInt() == 17, "generated spacing active getter retains the stored amount");
        check(records.get(5).getAsJsonObject().get("availability").getAsString().equals("unknown")
                && records.get(6).getAsJsonObject().get("availability").getAsString().equals("unknown"), "near-matching generated and arbitrary tooltip subclasses remain unknown");
        JsonObject textRequest = request(3, 0, 0, 0, 1, 0); textRequest.addProperty("contextToken", token); textRequest.addProperty("offset", 1); textRequest.addProperty("limit", 1);
        result = read(screen, textRequest);
        check(result.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && result.getAsJsonObject("data").get("offsetUnit").getAsString().equals("unicode_codepoints"),
                "private generated TEXT variant exposes its complete active text character path");
        JsonObject icon = value(screen, token, 3, 0, 0, 0, 2, 0);
        check(icon.get("kind").getAsString().equals("displayIcon") && icon.get("fluidName").getAsString().equals(fluid.getName())
                && icon.get("effectiveStackSize").getAsInt() == 1000 && icon.has("additionalTooltipStatus"), "generated icon retains typed fluid identity and icon display semantics");
        check(readText(screen, token, 3, new int[] { 0, 0, 0, 3, 0 }, 127).equals("fixture fluid description: " + fluid.getName()), "generated component description has a reachable complete text path");
        JsonObject codes = value(screen, token, 3, 0, 0, 0, 4, 0);
        check(codes.get("kind").getAsString().equals("formatting") && codes.get("small").getAsBoolean()
                && codes.getAsJsonArray("codes").size() == 2, "generated formatting model exposes stored small flag and finite vanilla formatting set");
        for (JsonElement code : codes.getAsJsonArray("codes")) {
            String name = code.getAsJsonObject().get("name").getAsString();
            check(code.getAsJsonObject().get("code").getAsString().equals(EnumChatFormatting.valueOf(name).toString()), "generated formatting retains exact vanilla code for " + name);
        }
        check(value(screen, token, 3, 0, 0, 0, 7, 0).get("availability").getAsString().equals("unknown"), "arbitrary formatting callbacks stay unknown under a recognized generated union");
        JsonObject defaultValue = value(screen, token, 5, 0, 0, 0);
        JsonObject overrideValue = value(screen, token, 5, 0, 0, 1);
        check(defaultValue.get("type").getAsString().equals("FLUID") && defaultValue.get("fluidName").getAsString().equals(fluid.getName())
                && defaultValue.get("fluidId").getAsInt() == FluidRegistry.getFluidID(fluid)
                && FluidRegistry.getFluid(fluid.getName()) == fluid, "generated fluid component reports the actual registered fluid name and ID");
        check(defaultValue.get("stackSize").isJsonNull() && defaultValue.get("effectiveStackSize").getAsInt() == 1000
                && defaultValue.get("nbtPresent").getAsBoolean(), "generated fluid uses its known 1000 default and reports stored NBT presence");
        check(overrideValue.get("stackSize").getAsInt() == 2750 && overrideValue.get("effectiveStackSize").getAsInt() == 2750
                && overrideValue.get("effectiveStackSizeSource").getAsString().contains("override"), "stored fluid quantity overrides the known default");
        check(value(screen, token, 5, 0, 0, 2).get("componentAvailability").getAsString().equals("unknown_custom_component"), "arbitrary fluid component overrides remain unknown");
        check(value(screen, token, 5, 0, 0, 3, 0).get("availability").getAsString().equals("unknown"), "arbitrary wrapper get overrides remain unknown on the explicit NBT child");
        check(ImmutableNbtWrapper.copiesForTest() == 0, "model summaries, context fingerprints and unrelated text/formatting paths never call the copying NBT getter");
        int rocketOffset = expectedNbt.codePointCount(0, expectedNbt.indexOf("\uD83D\uDE80"));
        JsonObject nbtRequest = request(5, 0, 0, 0, 0); nbtRequest.addProperty("contextToken", token); nbtRequest.addProperty("offset", rocketOffset); nbtRequest.addProperty("limit", 1);
        result = read(screen, nbtRequest);
        check(result.getAsJsonObject("data").get("text").getAsString().equals("\uD83D\uDE80") && ImmutableNbtWrapper.copiesForTest() == 1,
                "explicit generated wrapper NBT page invokes the API copying getter and preserves Unicode boundaries");
        ImmutableNbtWrapper.lastCopyForTest().setString("fluidFixture", "changed copy only");
        check(readText(screen, token, 5, new int[] { 0, 0, 0, 0 }, 127).equals(expectedNbt), "all long generated-wrapper NBT pages reconstruct the original SNBT after the returned copy is changed");
        check(original.toString().equals(expectedNbt) && ImmutableNbtWrapper.lastCopyForTest() != original, "explicit NBT reading leaves the supplied compound unchanged and returns a distinct compound");
        check(TooltipElement.inactiveCallsForTest() == 0 && TooltipElement.unverifiedCallsForTest() == 0
                && custom.calls == 0 && unknownFormatting.calls == 0 && unknownFluid.calls == 0 && unknownWrapper.calls == 0,
                "all inactive union, unverified generated, custom formatting/fluid/NBT accessors remain uninvoked");
    }
    private static String readText(GuiRecipe screen, String token, int metadataIndex, int[] path, int limit) {
        JsonObject args = request(metadataIndex, path); args.addProperty("contextToken", token); args.addProperty("limit", limit);
        StringBuilder text = new StringBuilder(); int offset = 0; int total = -1;
        do {
            args.addProperty("offset", offset); JsonObject result = read(screen, args);
            check(result.get("contextToken").getAsString().equals(token), "generated model character continuation retains its definition token");
            int currentTotal = result.get("total").getAsInt(); if (total < 0) total = currentTotal;
            check(total == currentTotal, "generated model character continuation reports the actual stable total");
            String page = result.getAsJsonObject("data").get("text").getAsString(); int next = result.get("nextOffset").getAsInt();
            check(next > offset && next - offset == page.codePointCount(0, page.length()), "generated model character pages advance without Unicode gaps or overlaps");
            text.append(page); offset = next;
            check(result.get("complete").getAsBoolean() == (offset == total), "generated character page completion matches the actual total");
        } while (offset < total);
        return text.toString();
    }
    private static final class UnknownElement extends TooltipElement {
        int calls;
        @Override public ElementType type() { calls++; throw new AssertionError("Custom tooltip type must not run"); }
        @Override public String text() { calls++; throw new AssertionError("Custom tooltip text must not run"); }
    }
    private static final class UnknownFormatting extends TextFormatting {
        int calls;
        @Override public ImmutableSortedSet<EnumChatFormatting> formatting() { calls++; throw new AssertionError("Custom formatting must not run"); }
        @Override public boolean small() { calls++; throw new AssertionError("Custom formatting flag must not run"); }
    }
    private static final class UnknownFluid extends FluidComponent {
        int calls;
        @Override public Fluid fluid() { calls++; throw new AssertionError("Custom fluid getter must not run"); }
        @Override public Optional<ImmutableNbtWrapper> nbtWrapper() { calls++; throw new AssertionError("Custom fluid NBT getter must not run"); }
        @Override public String description() { calls++; throw new AssertionError("Custom fluid description must not run"); }
    }
    private static final class UnknownWrapper extends ImmutableNbtWrapper {
        int calls;
        @Override protected NBTTagCompound nbt() { calls++; throw new AssertionError("Custom wrapper model must not run"); }
        @Override public NBTTagCompound get() { calls++; throw new AssertionError("Custom wrapper copying getter must not run"); }
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
