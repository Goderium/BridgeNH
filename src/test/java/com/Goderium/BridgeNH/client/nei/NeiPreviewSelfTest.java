package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;

import blockrenderer6343.client.renderer.WorldSceneRenderer;
import blockrenderer6343.client.utils.BRButton;
import blockrenderer6343.client.utils.BRUtil;
import blockrenderer6343.client.utils.GuiSlider;
import blockrenderer6343.integration.gregtech.GTGuiMultiblockHandler;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.StatCollector;

/** Callback identity, mutation boundaries, coordinates, and old-frame invalidation, without a client. */
public final class NeiPreviewSelfTest {
    private static int checks;
    public static int run() { checks = 0; verifyExistingScene(); verify(); return checks; }
    public static void main(String[] args) { System.out.println("NEI preview fixtures passed: " + run()); }
    private static JsonObject action(String action) { JsonObject args = new JsonObject(); args.addProperty("action", action); args.addProperty("screenId", "fixture-preview"); args.addProperty("contextToken", "fixture-token"); return args; }
    private static JsonObject coordinate(int x, int y, int z) { JsonObject value = new JsonObject(); value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z); return value; }
    private static long pack(int x, int y, int z) { return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL); }

    private static void verifyExistingScene() {
        blockrenderer6343.integration.nei.GuiMultiblockHandler gui = new blockrenderer6343.integration.nei.GuiMultiblockHandler();
        WorldSceneRenderer renderer = new WorldSceneRenderer();
        ItemStack trigger = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 1, 0);
        gui.fixtureInit(renderer, trigger, values -> {});
        gui.fixtureButtons().add(new GuiSlider(StatCollector.translateToLocal("blockrenderer6343.nei.tier"), 0, 1, 1, 4, value -> {}));
        GuiSlider modernLayer = new GuiSlider(StatCollector.translateToLocal("blockrenderer6343.nei.layer"), 1, 0, 0, 1, value -> gui.fixtureLayer(value - 1));
        gui.fixtureButtons().add(modernLayer);
        long position = pack(0, 64, 0);
        renderer.world.blockMap.put(position, new Object());
        renderer.renderedBlocks.add(position);
        gui.fixtureElements().put(position, new Object());
        blockrenderer6343.integration.nei.MultiblockHandler handler =
            new blockrenderer6343.integration.nei.MultiblockHandler(gui, new Object());
        codechicken.nei.recipe.GuiRecipe screen = new codechicken.nei.recipe.GuiRecipe(handler, Collections.singletonList(0));
        NeiPreviewProvider.Scene scene = NeiPreviewProvider.scene(screen);
        check(scene.built && scene.elements == gui.fixtureElements(), "scene discovery reads the protected 1.4.25 element map without building or rendering");
        JsonObject request = action("set_camera");
        request.addProperty("rotationYaw", 35); request.addProperty("rotationPitch", 80); request.addProperty("zoom", 12);
        NeiPreviewProvider.applyControl(scene, request);
        blockrenderer6343.integration.nei.GuiMultiblockHandler other = new blockrenderer6343.integration.nei.GuiMultiblockHandler();
        other.fixtureInit(new WorldSceneRenderer(), trigger, values -> {});
        JsonObject read = NeiPreviewProvider.inspect(screen, 0, 1, System.nanoTime(), false);
        check(read.get("status").getAsString().equals("ok"), "public preview read traverses the actual handler/model fields: " + read);
        check(read.getAsJsonObject("data").getAsJsonObject("camera").get("zoom").getAsFloat() == 12,
            "creating another preview cannot overwrite the selected preview camera");
        check(read.getAsJsonObject("data").get("structureElementCount").getAsInt() == 1 && renderer.fixtureCameraCalls == 1,
            "readback preserves protected map contents and does not replay camera input");
        JsonObject layer = action("set_layer"); layer.addProperty("value", 20);
        NeiPreviewProvider.applyControl(scene, layer);
        check(modernLayer.fixtureValue() == 20, "zero-based all-layers sentinel leaves the maximum physical layer reachable");
        layer.addProperty("value", 21);
        expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, layer));
        layer.addProperty("value", 0); NeiPreviewProvider.applyControl(scene, layer);
        check(modernLayer.fixtureValue() == 0, "1.4.25 all-layers sentinel is zero");
    }

    private static void verify() {
        for (int x : new int[] {-33554432, -5, -1, 0, 5, 33554431}) for (int y : new int[] {-2048, -1, 0, 62, 64, 81, 2047}) for (int z : new int[] {-33554432, -9, -1, 0, 1, 33554431}) {
            JsonObject data = NeiPreviewProvider.coordinates(pack(x, y, z), 0, 64, 0);
            check(data.getAsJsonObject("position").equals(coordinate(x, y, z)), "signed fake-world packed coordinates preserve all extremes");
            check(data.getAsJsonObject("controllerRelativePosition").equals(coordinate(x, y - 64, z)), "controller-relative coordinates subtract anchor without world mapping");
        }
        GTGuiMultiblockHandler gui = new GTGuiMultiblockHandler();
        WorldSceneRenderer renderer = new WorldSceneRenderer();
        ItemStack trigger = new ItemStack(new StructuredInfoSelfTest.FakeItem(), 1, 0);
        AtomicInteger candidateUpdates = new AtomicInteger(), candidateCount = new AtomicInteger(-1), presses = new AtomicInteger(), layer = new AtomicInteger(-1);
        gui.fixtureInit(renderer, trigger, values -> { candidateUpdates.incrementAndGet(); candidateCount.set(values.size()); });
        gui.fixtureData().fixtureChannels.put("glass", 61); gui.fixtureData().fixtureChannels.put("coil", 14);
        GuiSlider tier = new GuiSlider(StatCollector.translateToLocal("blockrenderer6343.nei.tier"), 0, 1, 1, 14, value -> trigger.stackSize = value);
        GuiSlider layerSlider = new GuiSlider(StatCollector.translateToLocal("blockrenderer6343.nei.layer"), 1, -1, -1, 0, value -> { layer.set(value); gui.fixtureLayer(value); });
        GuiSlider glass = new GuiSlider("Glass", 2, 0, 0, 61, value -> channel(trigger, "glass", value));
        GuiSlider coil = new GuiSlider("Coil", 3, 0, 0, 14, value -> channel(trigger, "coil", value));
        gui.fixtureButtons().add(tier); gui.fixtureButtons().add(layerSlider); gui.fixtureButtons().add(glass); gui.fixtureButtons().add(coil);
        gui.fixtureButtons().add(new BRButton("P", 0, presses::incrementAndGet)); gui.fixtureButtons().add(new BRButton("?", 1, presses::incrementAndGet));
        gui.fixtureButtons().add(new BRButton("C", 2, presses::incrementAndGet)); gui.fixtureButtons().add(new BRButton("H", 3, presses::incrementAndGet));
        NeiPreviewProvider.Scene scene = new NeiPreviewProvider.Scene(); scene.gui = gui; scene.handler = new Object(); scene.controller = new Object(); scene.recipeIndex = 0; scene.built = true;
        scene.renderer = renderer; scene.world = renderer.world; scene.blocks = renderer.world.blockMap; scene.rendered = renderer.renderedBlocks; scene.translucent = renderer.renderTranslucentBlocks;
        scene.elements = new LinkedHashMap<Long, Object>(); scene.tiles = renderer.world.tileMap; scene.metadata = renderer.world.blockMetaMap;
        long selected = pack(-5, 62, -9); renderer.world.blockMap.put(selected, new Object()); renderer.renderedBlocks.add(selected);
        Map<Long, Object> elements = new LinkedHashMap<>(); elements.put(selected, (IStructureElement) context -> Collections.singletonList("existing hint")); scene.elements = elements;
        String frame = NeiPreviewProvider.frameContextOf(scene), context = NeiPreviewProvider.contextOf(scene);
        JsonObject request = action("set_channel"); request.addProperty("channel", "glass"); request.addProperty("value", 12);
        JsonObject data = NeiPreviewProvider.applyControl(scene, request);
        check(glass.fixtureCalls == 1 && glass.fixtureValue() == 12 && trigger.getTagCompound().getCompoundTag("channels").getInteger("glass") == 12, "named channel action invokes the real stored slider callback with exact channel");
        check(data.get("controlIndex").getAsInt() == 2 && data.get("value").getAsInt() == 12 && data.get("effectStatus").getAsString().startsWith("requires_readback"), "bounded callback receipt distinguishes effect acceptance");
        check(!frame.equals(NeiPreviewProvider.frameContextOf(scene)), "in-place channel changes invalidate old completed draw frames without needing NBT identity change");
        check(!context.equals(NeiPreviewProvider.contextOf(scene)), "same-object channel NBT edits invalidate structural continuation context");
        frame = NeiPreviewProvider.frameContextOf(scene); JsonObject controls = modelRead("channels", scene); check(controls.getAsJsonArray("entries").get(0).getAsJsonObject().get("channel").getAsString().equals("glass") && controls.getAsJsonArray("entries").get(0).getAsJsonObject().get("effectiveTriggerTier").getAsInt() == 12, "read exposes exact identifiers, bounds and current NBT semantics");
        check(frame.equals(NeiPreviewProvider.frameContextOf(scene)) && glass.fixtureCalls == 1 && candidateUpdates.get() == 0, "passive channel read and frame marker never invoke controls or candidate callbacks");
        request.addProperty("value", 62); final JsonObject above = request; expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, above)); check(glass.fixtureCalls == 1, "out-of-range channel rejected before any listener");
        request.addProperty("value", -1); expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, above));
        request.addProperty("value", 1.5); expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, above));
        request.addProperty("value", 0); data = NeiPreviewProvider.applyControl(scene, request); check(glass.fixtureValue() == 0 && !trigger.getTagCompound().getCompoundTag("channels").hasKey("glass"), "zero removes explicit channel rather than inventing tier zero");
        request.addProperty("channel", "Glass"); expect("not_found", () -> NeiPreviewProvider.applyControl(scene, above)); check(glass.fixtureCalls == 2, "case-mismatched channel is not silently guessed");
        request = action("set_tier"); request.addProperty("value", 14); data = NeiPreviewProvider.applyControl(scene, request); check(trigger.stackSize == 14 && tier.fixtureCalls == 1, "master tier uses existing native listener");
        request.addProperty("value", 0); final JsonObject wrongTier = request; expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, wrongTier));
        request = action("set_layer"); request.addProperty("value", 19); context = NeiPreviewProvider.contextOf(scene); data = NeiPreviewProvider.applyControl(scene, request); check(layer.get() == 19 && layerSlider.fixtureValue() == 19, "native layer maximum is refreshed from bounds instead of stale render maximum or a supplier");
        check(!context.equals(NeiPreviewProvider.contextOf(scene)), "layer changes invalidate structural continuation even when render-set counts match");
        request.addProperty("value", 20); final JsonObject aboveLayer = request; expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, aboveLayer)); check(layerSlider.fixtureCalls == 1, "out-of-range layer rejected before mutation");
        request.addProperty("value", -1); NeiPreviewProvider.applyControl(scene, request); check(layer.get() == -1, "all-layers sentinel preserved");
        request = action("set_camera"); request.addProperty("rotationYaw", -40); request.addProperty("rotationPitch", 360); request.addProperty("zoom", 10); request.add("center", coordinate(2, 73, -3));
        frame = NeiPreviewProvider.frameContextOf(scene); context = NeiPreviewProvider.contextOf(scene); data = NeiPreviewProvider.applyControl(scene, request);
        check(renderer.fixtureCameraCalls == 1 && data.getAsJsonObject("camera").get("rotationPitch").getAsFloat() == 0 && renderer.getLookAt().x == 2, "named camera updates persistent plugin fields and existing renderer API in the actual angle convention");
        check(frame.equals(NeiPreviewProvider.frameContextOf(scene)), "camera motion does not invalidate unrelated captured label semantics");
        check(context.equals(NeiPreviewProvider.contextOf(scene)), "camera/center changes do not invalidate an unrelated structural block page");
        request.addProperty("zoom", 2); final JsonObject badCamera = request; expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, badCamera)); check(renderer.fixtureCameraCalls == 1, "invalid camera range rejected before renderer update");
        request = action("select_block"); request.add("position", coordinate(-5, 62, -9)); frame = NeiPreviewProvider.frameContextOf(scene); data = NeiPreviewProvider.applyControl(scene, request);
        check(BRUtil.fixtureScans == 1 && BRUtil.fixtureController == scene.controller && BRUtil.fixtureTrigger == trigger && candidateCount.get() == 2, "block selection preserves the existing structure-element scan and candidate callback");
        check(data.get("candidateGroupCount").getAsInt() == 2 && data.getAsJsonObject("selection").getAsJsonObject("position").equals(coordinate(-5, 62, -9)) && !frame.equals(NeiPreviewProvider.frameContextOf(scene)), "selection receipt returns fake-world position/count without unbounded candidate payload; old text frames rejected");
        request.add("position", coordinate(0, 999, 0)); final JsonObject missing = request; expect("not_found", () -> NeiPreviewProvider.applyControl(scene, missing)); check(BRUtil.fixtureScans == 1 && candidateUpdates.get() == 1, "absent block cannot trigger scans or selection callbacks");
        request = action("press_control"); request.addProperty("controlIndex", 7); data = NeiPreviewProvider.applyControl(scene, request); check(presses.get() == 1 && candidateCount.get() == 0 && data.get("status").getAsString().equals("callback_completed"), "button reuses exact existing callback, cleans selection, and does not claim effect confirmation");
        request.addProperty("controlIndex", 3); final JsonObject sliderAsButton = request; expect("unsupported", () -> NeiPreviewProvider.applyControl(scene, sliderAsButton));
        request = action("clear_selection"); data = NeiPreviewProvider.applyControl(scene, request); check(!data.getAsJsonObject("selection").get("present").getAsBoolean(), "explicit clear uses normal selection sentinel");
        request.addProperty("class", "java.lang.System"); final JsonObject foreign = request; expect("invalid_args", () -> NeiPreviewProvider.applyControl(scene, foreign));
        glass.enabled = false; request = action("set_channel"); request.addProperty("channel", "glass"); request.addProperty("value", 1); final JsonObject disabled = request; expect("unavailable", () -> NeiPreviewProvider.applyControl(scene, disabled)); check(glass.fixtureCalls == 2, "disabled control cannot be invoked");
        final JsonObject unsupportedScreen = action("clear_selection"); expect("not_present", () -> NeiPreviewProvider.control(new Object(), unsupportedScreen));
    }

    private static JsonObject modelRead(String name, NeiPreviewProvider.Scene scene) {
        try { Method method = NeiPreviewProvider.class.getDeclaredMethod(name, NeiPreviewProvider.Scene.class); method.setAccessible(true); return (JsonObject) method.invoke(null, scene); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void channel(ItemStack stack, String channel, int value) {
        if (!stack.hasTagCompound()) stack.setTagCompound(new NBTTagCompound());
        NBTTagCompound channels = stack.getTagCompound().getCompoundTag("channels");
        if (value == 0) channels.removeTag(channel); else channels.setInteger(channel, value);
        stack.getTagCompound().setTag("channels", channels);
    }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void expect(String code, Runnable runnable) {
        checks++; try { runnable.run(); }
        catch (BridgeException error) { if (code.equals(error.code)) return; throw new AssertionError("Expected " + code + ", got " + error.code, error); }
        throw new AssertionError("Expected " + code);
    }
}
