package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.StatCollector;

/** Passive existing preview reads; explicit named controls reuse BlockRenderer6343 1.4.23 callbacks. */
public final class NeiPreviewProvider {
    private static final String GUI = "codechicken.nei.recipe.GuiRecipe";
    private static final String HANDLER = "blockrenderer6343.integration.nei.MultiblockHandler";
    private static final String PREVIEW = "blockrenderer6343.integration.nei.GuiMultiblockHandler";
    private static final String RENDERER = "blockrenderer6343.client.renderer.WorldSceneRenderer";
    private static final String WORLD = "blockrenderer6343.client.world.TrackedDummyWorld";
    private static final String VECTOR3F = "org.joml.Vector3f";
    private static final String VECTOR3I = "org.joml.Vector3i";
    private static final String BUTTON = "blockrenderer6343.client.utils.BRButton";
    private static final String SLIDER = "blockrenderer6343.client.utils.GuiSlider";
    private static final String TIER_DATA = "blockrenderer6343.client.utils.ConstructableData";
    private static final String GT_GUI = "blockrenderer6343.integration.gregtech.GTGuiMultiblockHandler";
    private static final String BLOCK_POS = "com.gtnewhorizon.gtnhlib.blockpos.BlockPos";
    private static final String BR_UTIL = "blockrenderer6343.client.utils.BRUtil";
    private static final String ELEMENT = "com.gtnewhorizon.structurelib.structure.IStructureElement";
    private static final String GT_TILE = "gregtech.api.metatileentity.BaseMetaTileEntity";
    private static final String GT_META = "gregtech.api.metatileentity.MetaTileEntity";
    private static final String GT_COMMON_META = "gregtech.api.metatileentity.CommonMetaTileEntity";
    private static final String SOURCE = "BlockRenderer6343 1.4.23 public model + fixed read-only fields; GTNHLib 0.11.51 coordinates; GT5 5.09.54.183 existing tile identity";
    private static final long SOFT_NANOS = 4000000L;
    private static final int BYTE_LIMIT = 112 * 1024;
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private NeiPreviewProvider() {}

    static JsonObject inspect(Object screen, int offset, int limit, long started, boolean includeBlocks) {
        if (offset < 0 || limit < 1 || limit > 64) {
            throw new BridgeException("invalid_args", "Preview offset must be nonnegative and limit from 1 to 64.");
        }
        JsonObject result = base();
        JsonObject data = new JsonObject();
        result.add("data", data);
        try {
            Scene scene = scene(screen);
            if (scene == null) return unavailable(result, "not_present", "Current screen has no BlockRenderer6343 preview handler.");
            data.addProperty("handlerClass", scene.handler.getClass().getName());
            data.addProperty("guiHandlerClass", scene.gui.getClass().getName());
            data.addProperty("recipeIndex", scene.recipeIndex);
            data.addProperty("sceneBuilt", scene.built);
            data.addProperty("sceneStatus", scene.built ? "existing_model" : "not_built_for_current_recipe");
            if (!scene.built) return unavailable(result, "not_built", "No verified existing scene for the current preview recipe; inspection does not build it.");

            data.addProperty("rendererClass", scene.renderer.getClass().getName());
            data.addProperty("worldClass", scene.world.getClass().getName());
            data.addProperty("blockCount", scene.blocks.size());
            data.addProperty("renderedBlockCount", size(scene.rendered));
            data.addProperty("translucentBlockCount", size(scene.translucent));
            data.addProperty("tileEntityCount", size(scene.tiles));
            data.addProperty("structureElementCount", size(scene.elements));
            data.add("camera", camera(scene));
            data.add("bounds", bounds(scene));
            data.add("controllerAnchor", anchor(scene));
            data.addProperty("positionFrame", "Preview fake-world XYZ; controllerRelativePosition subtracts the verified controller anchor, without orientation/ABC conversion. Not actual-world positions.");
            data.add("channels", channels(scene));
            if (type(scene.gui, GT_GUI) != null) data.addProperty("hatchHighlightEnabled", (Boolean) field(scene.gui, GT_GUI, "highlightHatch", false));
            data.add("selection", selection(scene));
            data.add("hover", hover(scene));
            data.add("controllerStack", stack(field(scene.gui, PREVIEW, "stackForm", false)));
            data.add("triggerStack", stack(field(scene.gui, PREVIEW, "trigger", false)));
            data.addProperty("lastSearch", boundedString(field(scene.gui, PREVIEW, "lastSearch", false), 512));
            data.addProperty("searchTruncated", stringLength(field(scene.gui, PREVIEW, "lastSearch", false)) > 512);
            data.addProperty("controlCount", size(field(scene.gui, PREVIEW, "allButtons", false)));
            data.addProperty("controlsStatus", "Use preview_controls for existing button fields, stored tooltip and slider labels/values.");
            data.addProperty("previewDetailTargets", "preview_block, preview_hint_text, preview_candidate_groups, preview_candidate_items, preview_candidate_tooltip_lines, preview_candidate_tooltip_text, preview_candidate_nbt, preview_candidate_visuals, preview_textures");
            data.addProperty("candidateGroupCount", scene.candidates == null ? 0 : scene.candidates.size());
            data.addProperty("candidateCacheStatus", scene.candidates == null ? scene.candidateStatus : "Existing selected-position RecipeCacher.positionedResults; reads do not generate or cycle alternatives.");
            data.addProperty("resourceContext", NeiPreviewResources.context());
            data.addProperty("nativeControlOperation", "nei.preview_control; requires a fresh NEI contextToken and exact current screenId.");
            data.addProperty("visibleYLevel", number(field(scene.world, WORLD, "visibleYLevel", false)).intValue());
            JsonObject coverage = new JsonObject();
            coverage.addProperty("blocks", "Existing fake-world block positions, raw metadata, tile class and current render-set membership; all map entries are pageable.");
            coverage.addProperty("coordinates", "Preview fake-world coordinates, not player-world coordinates; packed signed 26/12/26 bits from matching GTNHLib CoordinatePacker.");
            coverage.addProperty("renderedMembership", "Renderer's selected solid/search set; translucent membership is separate. Membership does not establish pixel visibility or occlusion.");
            coverage.addProperty("tileNbt", "Unavailable: no tile writeToNBT, inventory probing or arbitrary tile getter is invoked; actual controller/trigger/hover ItemStack NBT is retained with explicit truncation.");
            coverage.addProperty("metaMachine", "Existing standard GT BaseMetaTileEntity ID/MTE and proven inherited getStackForm identity; raw world metadata/itemStack remain distinct from actualStack. Unknown getter overrides are explicit.");
            coverage.addProperty("machineTooltip", "Existing primary generated tooltip translation and source keys only; no MTE getDescription, tooltip-cache construction, secondary selector or item tooltip callback is invoked.");
            coverage.addProperty("graphics", "preview_textures reads actual six-face base/overlay icons, UV, resource paths and colors from existing tiles/blocks; CTM context remapping, custom/TESR rendering, effects and pixel occlusion remain explicit gaps.");
            coverage.addProperty("purity", "Only already-present model fields and proven read getters; no construct, draw, raytrace, hasChanged, getSize, load or rebuild.");
            data.add("coverage", coverage);
            result.addProperty("status", "ok");
            result.addProperty("total", scene.blocks.size());
            result.addProperty("nonAtomic", true);
            result.addProperty("order", "Existing blockMap encounter order, stable only while the model is unchanged; no sorting or global snapshot.");
            result.addProperty("contextCoverage", "Model identity/size, layer, selection, trigger identity/count, known channel NBT values, search, resource reload/language and bounded native candidate identities/counts; camera/hover are excluded from structural continuation. Equal-size in-place map edits, opaque candidate tails or unknown in-place NBT edits are not a revision counter.");
            result.addProperty("budgetSource", "4 ms soft deadline; offset traversal and the first entry are allowed to finish. No synchronous getter is preempted.");
            result.addProperty("context", contextOf(scene));
            if (!includeBlocks) {
                result.addProperty("nextOffset", 0);
                result.addProperty("hasMore", !scene.blocks.isEmpty());
                result.addProperty("complete", true);
                data.addProperty("blocksStatus", "not_requested; use preview_blocks pagination");
                result.addProperty("softBudgetPartial", false);
                result.addProperty("softBudgetExceeded", expired(started));
                result.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000.0);
                trimSummary(result);
                return result;
            }

            JsonArray entries = new JsonArray();
            data.add("blocks", entries);
            int next = Math.min(offset, scene.blocks.size());
            Iterator<? extends Map.Entry<?, ?>> iterator = scene.blocks.entrySet().iterator();
            int skipped = 0;
            // Offset traversal must reach the first requested entry even after the soft deadline.
            while (skipped < next && iterator.hasNext()) { iterator.next(); skipped++; }
            if (skipped != next) {
                result.addProperty("modelChanged", true);
                next = skipped;
            }
            int bytes = utf8(result) + 4096;
            while (iterator.hasNext() && entries.size() < limit) {
                if (entries.size() > 0 && expired(started)) break;
                Map.Entry<?, ?> entry = iterator.next();
                JsonObject block = block(scene, entry, next);
                int blockBytes = utf8(block) + 2;
                if (bytes + blockBytes > BYTE_LIMIT) {
                    result.addProperty("byteLimited", true);
                    if (entries.size() == 0) {
                        block.remove("itemStack");
                        block.addProperty("itemStackByteLimited", true);
                        blockBytes = utf8(block) + 2;
                    }
                    if (bytes + blockBytes > BYTE_LIMIT) break;
                }
                entries.add(block);
                bytes += blockBytes;
                next++;
            }
            boolean more = iterator.hasNext() || next < scene.blocks.size();
            result.addProperty("nextOffset", next);
            result.addProperty("hasMore", more);
            result.addProperty("complete", !more && !booleanValue(result, "byteLimited"));
            result.addProperty("softBudgetPartial", more && expired(started));
            result.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000.0);
            if (!contextOf(scene).equals(result.get("context").getAsString())) {
                result.addProperty("modelChanged", true);
                result.addProperty("complete", false);
            }
            trimSummary(result);
            return result;
        } catch (Unavailable error) {
            return unavailable(result, "unsupported", error.getMessage());
        } catch (RuntimeException | LinkageError error) {
            return unavailable(result, "unavailable", "Existing preview read failed: " + error.getClass().getSimpleName());
        }
    }

    static String context(Object screen) {
        try {
            Scene scene = scene(screen);
            return scene == null ? "preview:not_present" : contextOf(scene);
        } catch (RuntimeException | LinkageError error) {
            return "preview:unavailable:" + error.getClass().getSimpleName();
        }
    }

    /** Completed draw-frame compatibility without camera, hover, or block-map traversal. */
    static String frameContext(Object screen) {
        try {
            Scene scene = scene(screen);
            return scene == null ? "preview_frame:not_present" : frameContextOf(scene);
        } catch (RuntimeException | LinkageError error) { return "preview_frame:unavailable:" + error.getClass().getSimpleName(); }
    }

    static String frameContextOf(Scene scene) {
        StringBuilder result = new StringBuilder("preview_frame:1.4.23:");
        result.append(identity(scene.handler)).append(':').append(identity(scene.gui)).append(':').append(scene.recipeIndex)
            .append(':').append(identity(scene.controller)).append(':').append(scene.built);
        if (!scene.built) return result.toString();
        result.append(':').append(NeiPreviewResources.context());
        result.append(':').append(identity(scene.renderer)).append(':').append(identity(scene.world))
            .append(':').append(identity(scene.blocks)).append(':').append(scene.blocks.size())
            .append(':').append(size(scene.rendered)).append(':').append(size(scene.translucent))
            .append(':').append(number(field(scene.gui, PREVIEW, "layerIndex", false)));
        appendVector(result, field(scene.gui, PREVIEW, "SELECTED_BLOCK", false), VECTOR3I);
        Object trigger = field(scene.gui, PREVIEW, "trigger", false);
        if (trigger instanceof ItemStack) {
            ItemStack stack = (ItemStack) trigger;
            result.append(':').append(stack.stackSize).append(':').append(stack.getMetadata());
            int checked = 0;
            for (Map.Entry<?, ?> entry : channelMap(scene).entrySet()) {
                if (++checked > 62) { result.append(":channel_tail_unfingerprinted"); break; }
                if (!(entry.getKey() instanceof String)) throw new Unavailable("Channel frame fingerprint has a non-string identifier.");
                String channel = (String) entry.getKey();
                if (channel.length() > 128) throw new Unavailable("Channel frame fingerprint exceeds the bounded identifier schema.");
                result.append(':').append(channel.length()).append(':').append(channel).append(':').append(entry.getValue())
                    .append(':').append(explicitChannel(stack, channel));
            }
        }
        Object search = field(scene.gui, PREVIEW, "lastSearch", false);
        if (search instanceof String) result.append(':').append(((String) search).length()).append(':').append(((String) search).hashCode());
        return result.toString();
    }

    static JsonObject controls(Object screen, int offset, int limit, long started) {
        if (offset < 0 || limit < 1 || limit > 64) {
            throw new BridgeException("invalid_args", "Preview control offset must be nonnegative and limit from 1 to 64.");
        }
        JsonObject result = base();
        JsonObject data = new JsonObject();
        result.add("data", data);
        try {
            Scene scene = scene(screen);
            if (scene == null) return unavailable(result, "not_present", "Current screen has no BlockRenderer6343 preview handler.");
            data.addProperty("sceneBuilt", scene.built);
            data.addProperty("handlerClass", scene.handler.getClass().getName());
            if (!scene.built) return unavailable(result, "not_built", "Controls are not verified for the current preview recipe; inspection does not initialize them.");
            Object value = field(scene.gui, PREVIEW, "allButtons", false);
            if (!(value instanceof List<?>)) throw new Unavailable("Existing preview allButtons model is not a list.");
            List<?> buttons = (List<?>) value;
            int total = buttons.size();
            result.addProperty("status", "ok");
            result.addProperty("total", total);
            result.addProperty("context", contextOf(scene));
            result.addProperty("nonAtomic", true);
            result.addProperty("order", "Current allButtons list order; every entry is pageable.");
            data.addProperty("coordinateFrame", "Stored GuiButton coordinates are relative to the NEI recipe widget; they are not final screen-space bounds.");
            data.addProperty("tooltipSource", "Existing BRButton tooltip string, independent of hover; no mouse-state simulation or tooltip callback.");
            data.addProperty("sliderSource", "Current stored name/value/min/max/scale; supplier callbacks and font trimming are not executed.");
            JsonArray entries = new JsonArray();
            data.add("controls", entries);
            int next = Math.min(offset, total);
            int bytes = utf8(result) + 4096;
            boolean textLimited = false;
            boolean unsupportedEntry = false;
            while (next < buttons.size() && entries.size() < limit) {
                if (entries.size() > 0 && expired(started)) break;
                JsonObject entry = control(buttons.get(next), next);
                describeAction(scene, buttons, next, entry);
                int more = utf8(entry) + 2;
                if (bytes + more > BYTE_LIMIT) {
                    result.addProperty("byteLimited", true);
                    if (entries.size() == 0) {
                        shrinkControl(entry);
                        more = utf8(entry) + 2;
                    }
                    if (bytes + more > BYTE_LIMIT) break;
                }
                entries.add(entry);
                bytes += more;
                textLimited |= booleanValue(entry, "displayStringTruncated") || booleanValue(entry, "tooltipTruncated")
                    || booleanValue(entry, "nameTruncated") || booleanValue(entry, "semanticTextTruncated");
                unsupportedEntry |= !"ok".equals(entry.get("status").getAsString());
                next++;
            }
            boolean more = next < buttons.size();
            result.addProperty("nextOffset", next);
            result.addProperty("hasMore", more);
            result.addProperty("complete", !more && !textLimited && !unsupportedEntry && !booleanValue(result, "byteLimited"));
            result.addProperty("textLimited", textLimited);
            result.addProperty("unsupportedEntry", unsupportedEntry);
            result.addProperty("softBudgetPartial", more && expired(started));
            result.addProperty("elapsedMillis", (System.nanoTime() - started) / 1000000.0);
            if (buttons.size() != total || !contextOf(scene).equals(result.get("context").getAsString())) {
                result.addProperty("modelChanged", true);
                result.addProperty("complete", false);
            }
            return result;
        } catch (Unavailable error) {
            return unavailable(result, "unsupported", error.getMessage());
        } catch (RuntimeException | LinkageError error) {
            return unavailable(result, "unavailable", "Existing preview controls read failed: " + error.getClass().getSimpleName());
        }
    }

    /** Caller must first validate the exact current screen/session and the observed NEI context token. */
    public static JsonObject control(Object screen, JsonObject args) {
        try {
            Scene scene = scene(screen);
            if (scene == null || !scene.built) throw new BridgeException("not_present", "No existing built preview is available for the current original recipe.");
            if (!scene.gui.getClass().getName().equals(GT_GUI)) throw new BridgeException("unsupported", "Native preview controls are only verified for the existing GTGuiMultiblockHandler implementation.");
            JsonObject result = applyControl(scene, args);
            Scene after = scene(screen);
            if (after != null && after.built) {
                JsonObject state = new JsonObject();
                state.addProperty("blockCount", after.blocks.size());
                state.add("bounds", bounds(after)); state.add("channels", channels(after));
                state.add("camera", camera(after)); state.add("selection", selection(after));
                if (type(after.gui, GT_GUI) != null) state.addProperty("hatchHighlightEnabled", (Boolean) field(after.gui, GT_GUI, "highlightHatch", false));
                result.add("previewAfter", state);
                result.addProperty("previewAfterSource", "Existing callback model; screen-changing or server-facing button effects require a fresh current-screen/world read.");
            }
            return result;
        } catch (Unavailable error) {
            throw new BridgeException("unsupported", error.getMessage());
        } catch (LinkageError error) {
            throw new BridgeException("unsupported", "Native preview API is unavailable: " + error.getClass().getSimpleName());
        }
    }

    static JsonObject applyControl(Scene scene, JsonObject args) {
        String action = JsonData.string(args, "action");
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", 1); result.addProperty("action", action);
        result.addProperty("source", "Existing BlockRenderer6343 1.4.23 preview callbacks and exact native model fields.");
        result.addProperty("requiresViewRefresh", true);
        result.addProperty("effectStatus", "requires_readback; callback completion alone does not prove a server, inventory or world effect.");
        long started = System.nanoTime();
        switch (action) {
            case "set_channel": case "set_tier": case "set_layer": {
                allowed(args, "channel", "value");
                if (!"set_channel".equals(action) && args.has("channel")) throw new BridgeException("invalid_args", "channel is only allowed for set_channel.");
                List<?> buttons = buttons(scene);
                int index = "set_tier".equals(action) ? 0 : 1;
                if ("set_channel".equals(action)) {
                    String channel = JsonData.string(args, "channel");
                    if (channel.isEmpty() || channel.length() > 128) throw new BridgeException("invalid_args", "channel must contain 1 to 128 characters.");
                    index = -1;
                    for (int i = 2; i < buttons.size(); i++) if (channel.equals(channelAt(scene, i))) { index = i; break; }
                    if (index < 0) throw new BridgeException("not_found", "No existing slider is bound to this exact channel identifier.");
                    result.addProperty("channel", channel);
                }
                Object slider = verifiedSlider(scene, buttons, index);
                int minimum = number(field(slider, SLIDER, "minValue", false)).intValue();
                int maximum = maximum(scene, slider, index);
                int value = JsonData.integer(args, "value", minimum, maximum);
                int before = number(field(slider, SLIDER, "value", false)).intValue();
                result.addProperty("controlIndex", index); result.addProperty("previousValue", before);
                result.addProperty("minimum", minimum); result.addProperty("maximum", maximum);
                // GuiSlider.setValue clamps against its last rendered maximum. Refresh only the known
                // stored maximum for the layer slider from existing bounds; never call a supplier.
                if (index == 1) write(slider, SLIDER, "maxValue", maximum);
                nativeCall(type(slider, SLIDER), slider, "setValue", new Class<?>[] {int.class}, value);
                int current = number(field(slider, SLIDER, "value", false)).intValue();
                result.addProperty("value", current);
                result.addProperty("status", before == current ? "unchanged" : "applied");
                result.addProperty("nativeCallback", "GuiSlider.setValue(int) -> existing tier/channel/layer listener.");
                result.addProperty("rebuildStatus", "set_layer".equals(action) ? "existing_render_filter_updated_synchronously"
                    : before == current ? "not_required" : "existing_listener_returned_after_synchronous_preview_rebuild");
                break;
            }
            case "press_control": {
                allowed(args, "controlIndex");
                int index = JsonData.integer(args, "controlIndex", 0, 63);
                List<?> buttons = buttons(scene);
                if (index >= buttons.size()) throw new BridgeException("not_found", "controlIndex is absent.");
                Object value = buttons.get(index);
                if (value == null || !value.getClass().getName().equals(BUTTON) || !(value instanceof GuiButton)) throw new BridgeException("unsupported", "Only existing verified BRButton callbacks can be pressed.");
                GuiButton button = (GuiButton) value;
                if (!button.enabled || !button.visible) throw new BridgeException("unavailable", "The existing control is hidden or disabled.");
                int pluginIndex = number(field(value, BUTTON, "index", false)).intValue();
                boolean hasChannels = !channelMap(scene).isEmpty();
                String expected = pluginIndex == 0 ? "P" : pluginIndex == 1 ? "?" : pluginIndex == 2 && hasChannels ? "C"
                    : pluginIndex == (hasChannels ? 3 : 2) ? "H" : null;
                if (expected == null || !expected.equals(button.displayString)) throw new BridgeException("unsupported", "Button identity differs from the verified existing preview layout.");
                Object callback = field(value, BUTTON, "clickAction", false);
                if (!(callback instanceof Runnable)) throw new BridgeException("unsupported", "The existing button has no Runnable callback.");
                try { ((Runnable) callback).run(); }
                catch (RuntimeException error) { throw new BridgeException("native_callback_failed", "Existing button callback failed after possible changes: " + error.getClass().getSimpleName()); }
                clearSelection(scene);
                result.addProperty("controlIndex", index); result.addProperty("displayString", button.displayString);
                result.addProperty("status", "callback_completed");
                result.addProperty("nativeCallback", "Existing BRButton.clickAction.run(); normal post-button selection cleanup.");
                break;
            }
            case "set_camera": {
                allowed(args, "rotationYaw", "rotationPitch", "zoom", "center");
                float yaw = (float) JsonData.number(args, "rotationYaw", -89.9, 89.9);
                float pitch = (float) (JsonData.number(args, "rotationPitch", 0, 360) % 360);
                float zoom = (float) JsonData.number(args, "zoom", 3, 999);
                Object center = field(scene.gui, PREVIEW, "center", false);
                if (args.has("center")) {
                    JsonObject target = object(args, "center"); xyzOnly(target);
                    float x = (float) JsonData.number(target, "x", -33554432, 33554431);
                    float y = (float) JsonData.number(target, "y", -33554432, 33554431);
                    float z = (float) JsonData.number(target, "z", -33554432, 33554431);
                    nativeCall(type(center, VECTOR3F), center, "set", new Class<?>[] {float.class, float.class, float.class}, x, y, z);
                }
                write(scene.gui, PREVIEW, "rotationYaw", yaw); write(scene.gui, PREVIEW, "rotationPitch", pitch);
                write(scene.gui, PREVIEW, "zoom", zoom); write(scene.gui, PREVIEW, "scrolled", 0);
                nativeCall(type(scene.renderer, RENDERER), scene.renderer, "setCameraLookAt",
                    new Class<?>[] {type(center, VECTOR3F), double.class, double.class, double.class}, center, (double) zoom, Math.toRadians(pitch), Math.toRadians(yaw));
                result.addProperty("status", "applied"); result.add("camera", camera(scene));
                result.addProperty("nativeCallback", "Existing preview camera fields and WorldSceneRenderer.setCameraLookAt; same plugin ranges and angle convention.");
                break;
            }
            case "select_block": {
                allowed(args, "position");
                JsonObject position = object(args, "position"); xyzOnly(position);
                int x = JsonData.integer(position, "x", -33554432, 33554431), y = JsonData.integer(position, "y", -2048, 2047), z = JsonData.integer(position, "z", -33554432, 33554431);
                long packed = pack(x, y, z);
                if (!scene.blocks.containsKey(packed) || !(scene.rendered.contains(packed) || scene.translucent.contains(packed))) throw new BridgeException("not_found", "Position is not a block in the current preview render sets.");
                Object selected = field(scene.gui, PREVIEW, "SELECTED_BLOCK", false);
                nativeCall(type(selected, BLOCK_POS), selected, "set", new Class<?>[] {int.class, int.class, int.class}, x, y, z);
                ClassLoader loader = scene.gui.getClass().getClassLoader();
                Class<?> util = fixedClass(loader, BR_UTIL), element = fixedClass(loader, ELEMENT);
                Object candidates = nativeCall(util, null, "scanCandidates", new Class<?>[] {Object.class, element, ItemStack.class, type(selected, BLOCK_POS)},
                    scene.controller, scene.elements.get(packed), field(scene.gui, PREVIEW, "DEFAULT_TRIGGER", false), selected);
                if (!(candidates instanceof List<?>)) throw new BridgeException("native_callback_failed", "Existing candidate scan returned an unsupported value.");
                candidateCallback(scene).accept((List<?>) candidates);
                result.addProperty("status", "applied"); result.addProperty("candidateGroupCount", ((List<?>) candidates).size());
                result.add("selection", selection(scene));
                result.addProperty("nativeCallback", "Same existing BRUtil.scanCandidates and onCandidateChanged callback used by preview block selection; no raytrace, pixel mapping or world write.");
                break;
            }
            case "clear_selection": {
                allowed(args); clearSelection(scene); result.addProperty("status", "applied"); result.add("selection", selection(scene));
                result.addProperty("nativeCallback", "Existing SELECTED_BLOCK sentinel and empty onCandidateChanged callback.");
                break;
            }
            default: throw new BridgeException("invalid_args", "Unknown named preview action.");
        }
        result.addProperty("callbackMillis", (System.nanoTime() - started) / 1000000.0);
        return result;
    }

    private static Object verifiedSlider(Scene scene, List<?> buttons, int index) {
        if (index < 0 || index >= buttons.size()) throw new BridgeException("not_found", "The expected slider is absent.");
        Object value = buttons.get(index);
        if (value == null || !value.getClass().getName().equals(SLIDER) || !(value instanceof GuiButton)) throw new BridgeException("unsupported", "The existing control is not the verified GuiSlider implementation.");
        GuiButton button = (GuiButton) value;
        if (!button.enabled || !button.visible) throw new BridgeException("unavailable", "The existing slider is hidden or disabled.");
        if (number(field(value, BUTTON, "index", false)).intValue() != index) throw new BridgeException("unsupported", "The existing slider index differs from the verified layout.");
        String name = index == 0 ? StatCollector.translateToLocal("blockrenderer6343.nei.tier") : index == 1 ? StatCollector.translateToLocal("blockrenderer6343.nei.layer") : channelAt(scene, index);
        if (index >= 2 && name != null) name = Character.toTitleCase(name.charAt(0)) + name.substring(1);
        if (name == null || !name.equals(field(value, SLIDER, "name", true))) throw new BridgeException("unsupported", "Existing slider name differs from its exact channel/tier/layer mapping.");
        return value;
    }

    private static int maximum(Scene scene, Object slider, int index) {
        if (index != 1) return number(field(slider, SLIDER, "maxValue", false)).intValue();
        JsonObject bound = bounds(scene);
        return (int) (bound.getAsJsonObject("maximum").get("y").getAsDouble() - bound.getAsJsonObject("minimum").get("y").getAsDouble());
    }

    @SuppressWarnings("unchecked")
    private static Consumer<List<?>> candidateCallback(Scene scene) {
        Object callback = field(scene.gui, PREVIEW, "onCandidateChanged", false);
        if (!(callback instanceof Consumer<?>)) throw new BridgeException("unsupported", "Existing preview candidate callback is unavailable.");
        return (Consumer<List<?>>) callback;
    }

    private static void clearSelection(Scene scene) {
        Object selected = field(scene.gui, PREVIEW, "SELECTED_BLOCK", false);
        nativeCall(type(selected, BLOCK_POS), selected, "set", new Class<?>[] {int.class, int.class, int.class}, -100000, 0, -100000);
        candidateCallback(scene).accept(Collections.emptyList());
    }

    private static Class<?> fixedClass(ClassLoader loader, String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException error) { throw new Unavailable("Fixed native preview API class is unavailable: " + name); }
    }

    private static JsonObject object(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonObject()) throw new BridgeException("invalid_args", key + " must be an object.");
        return args.getAsJsonObject(key);
    }

    private static void xyzOnly(JsonObject args) {
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!Arrays.asList("x", "y", "z").contains(entry.getKey())) throw new BridgeException("invalid_args", "Coordinate contains an unsupported field.");
    }

    private static void allowed(JsonObject args, String... names) {
        for (Map.Entry<String, JsonElement> entry : args.entrySet()) {
            String key = entry.getKey();
            if (!Arrays.asList("screenId", "contextToken", "action").contains(key) && !Arrays.asList(names).contains(key)) throw new BridgeException("invalid_args", "Field is not allowed for this preview action: " + key);
        }
    }

    private static void write(Object target, String ownerName, String name, Object value) {
        Class<?> owner = type(target, ownerName);
        if (owner == null) throw new Unavailable("Fixed preview write owner is unavailable.");
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException | RuntimeException error) { throw new Unavailable("Fixed preview field cannot be updated: " + ownerName + '.' + name); }
    }

    private static Object nativeCall(Class<?> owner, Object target, String name, Class<?>[] signature, Object... args) {
        if (owner == null) throw new Unavailable("Fixed native preview owner is unavailable.");
        try {
            Method method = owner.getMethod(name, signature);
            if (method.getDeclaringClass() != owner || target != null && target.getClass().getMethod(name, signature).getDeclaringClass() != owner) throw new Unavailable("Unverified override of fixed native preview method: " + name);
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw new BridgeException("native_callback_failed", "Existing preview callback failed after possible changes: " + name + '/' + error.getCause().getClass().getSimpleName());
        } catch (ReflectiveOperationException error) { throw new Unavailable("Fixed native preview method unavailable: " + owner.getName() + '.' + name); }
    }

    private static JsonObject control(Object value, int index) {
        JsonObject result = new JsonObject();
        result.addProperty("index", index);
        result.addProperty("controlIndex", index);
        if (value == null) {
            result.addProperty("status", "not_present");
            return result;
        }
        result.addProperty("class", value.getClass().getName());
        if (!(value instanceof GuiButton) || type(value, BUTTON) == null) {
            result.addProperty("status", "unsupported");
            result.addProperty("reason", "Entry is outside the verified BRButton/GuiButton schema.");
            return result;
        }
        GuiButton button = (GuiButton) value;
        result.addProperty("status", "ok");
        result.addProperty("id", button.id);
        result.addProperty("x", button.xPosition); result.addProperty("y", button.yPosition);
        result.addProperty("width", button.width); result.addProperty("height", button.height);
        result.addProperty("visible", button.visible); result.addProperty("enabled", button.enabled);
        text(result, "displayString", button.displayString, 8192);
        text(result, "tooltip", field(value, BUTTON, "tooltip", false), 8192);
        int pluginIndex = number(field(value, BUTTON, "index", false)).intValue();
        result.addProperty("pluginIndex", pluginIndex);
        if (type(value, SLIDER) != null) {
            result.addProperty("kind", "slider");
            Object name = field(value, SLIDER, "name", true);
            text(result, "name", name, 8192);
            int current = number(field(value, SLIDER, "value", false)).intValue();
            result.addProperty("value", current);
            result.addProperty("minimum", number(field(value, SLIDER, "minValue", false)).intValue());
            result.addProperty("lastRenderedMaximum", number(field(value, SLIDER, "maxValue", false)).intValue());
            result.addProperty("dragging", (Boolean) field(value, SLIDER, "dragging", false));
            result.addProperty("scale", number(field(value, SLIDER, "scale", false)));
            result.addProperty("originalHeight", number(field(value, SLIDER, "originalHeight", false)).intValue());
            Object supplier = field(value, SLIDER, "valueStringSupplier", false);
            result.addProperty("hasTextSupplier", supplier != null);
            result.addProperty("hasMaximumSupplier", field(value, SLIDER, "maxValueSupplier", false) != null);
            String semantic = null;
            String status;
            if (supplier == null) {
                semantic = boundedString(name, 8192) + ": " + current;
                status = "Stored name and numeric value; drawing may trim the name to fit.";
            } else if (pluginIndex == 1 && name instanceof String
                && name.equals(StatCollector.translateToLocal("blockrenderer6343.nei.layer"))) {
                semantic = name + ": " + (current == -1 ? StatCollector.translateToLocal("blockrenderer6343.nei.all") : Integer.toString(current + 1));
                status = "Known 1.4.23 layer-label semantics from current value, without invoking the stored supplier; drawing may trim the name.";
            } else status = "Custom text-supplier callback not invoked; stored name and numeric state are available.";
            text(result, "semanticText", semantic, 8192);
            result.addProperty("semanticTextStatus", status);
            result.addProperty("exactDrawnLabelStatus", "Not stored in GuiSlider; exact font trimming is observable only during actual drawing.");
        } else {
            result.addProperty("kind", "button");
            result.addProperty("semanticTextStatus", "Stored displayString.");
        }
        return result;
    }

    private static void text(JsonObject result, String key, Object value, int limit) {
        if (value == null) { result.add(key, JsonNull.INSTANCE); return; }
        if (!(value instanceof String)) throw new Unavailable("Fixed preview control text field is not a string.");
        String text = (String) value;
        result.addProperty(key, JsonData.bounded(text, limit));
        result.addProperty(key + "Truncated", text.length() > limit);
    }

    private static void shrinkControl(JsonObject entry) {
        for (String key : new String[] {"displayString", "tooltip", "name", "semanticText"}) {
            if (!entry.has(key) || entry.get(key).isJsonNull()) continue;
            String text = entry.get(key).getAsString();
            if (text.length() > 1024) {
                entry.addProperty(key, JsonData.bounded(text, 1024));
                entry.addProperty(key + "Truncated", true);
            }
        }
        entry.addProperty("textByteLimited", true);
    }

    private static Map<?, ?> channelMap(Scene scene) {
        Object data = field(scene.gui, PREVIEW, "constructableData", false);
        Class<?> api = type(data, TIER_DATA);
        if (api == null || !data.getClass().getName().equals(TIER_DATA)) throw new Unavailable("Existing channel tier data is outside the verified ConstructableData model.");
        return map(call(api, data, "getChannelMaxTierMap"));
    }

    private static List<?> buttons(Scene scene) {
        Object value = field(scene.gui, PREVIEW, "allButtons", false);
        if (!(value instanceof List<?>)) throw new Unavailable("Existing allButtons model is not a list.");
        List<?> buttons = (List<?>) value;
        if (buttons.size() > 64) throw new Unavailable("Native controls require at most 64 existing controls; read pages remain available.");
        return buttons;
    }

    private static String channelAt(Scene scene, int controlIndex) {
        if (controlIndex < 2) return null;
        int index = 2;
        for (Map.Entry<?, ?> entry : channelMap(scene).entrySet()) {
            if (index++ == controlIndex) {
                if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number)) throw new Unavailable("Stored channel map has an unsupported entry.");
                String channel = (String) entry.getKey();
                if (channel.isEmpty() || channel.length() > 128) throw new Unavailable("Stored channel name is outside the bounded native control schema.");
                return channel;
            }
            if (index > 64) break;
        }
        return null;
    }

    private static JsonObject channels(Scene scene) {
        JsonObject result = new JsonObject();
        Map<?, ?> map = channelMap(scene);
        result.addProperty("total", map.size());
        JsonArray entries = new JsonArray();
        result.add("entries", entries);
        Object trigger = field(scene.gui, PREVIEW, "trigger", false);
        int tier = trigger instanceof ItemStack ? ((ItemStack) trigger).stackSize : 0;
        result.addProperty("masterTier", tier);
        result.addProperty("zeroMeaning", "Not set: removes the explicit channel, using the master tier/default under StructureLib semantics.");
        List<?> controls = (List<?>) field(scene.gui, PREVIEW, "allButtons", false);
        int index = 2;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entries.size() >= 62) break;
            JsonObject value = new JsonObject();
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number)) throw new Unavailable("Stored channel map has an unsupported entry.");
            String channel = (String) entry.getKey();
            text(value, "channel", channel, 128);
            value.addProperty("controlIndex", index);
            value.addProperty("minimum", 0);
            value.addProperty("maximum", ((Number) entry.getValue()).intValue());
            int explicit = explicitChannel(trigger, channel);
            value.addProperty("explicitTier", explicit);
            value.addProperty("effectiveTriggerTier", explicit > 0 ? explicit : tier);
            if (index < controls.size()) {
                Object slider = controls.get(index);
                if (type(slider, SLIDER) != null) {
                    value.addProperty("value", number(field(slider, SLIDER, "value", false)).intValue());
                    value.addProperty("nativeAction", "set_channel");
                } else value.addProperty("controlStatus", "unverified_channel_control");
            } else value.addProperty("controlStatus", "missing_channel_control");
            entries.add(value);
            index++;
        }
        result.addProperty("truncated", entries.size() < map.size());
        result.addProperty("source", "Existing ConstructableData channel map order, exact channel identifiers, current slider state, and read-only StructureLib trigger channels NBT.");
        return result;
    }

    private static int explicitChannel(Object value, String channel) {
        if (!(value instanceof ItemStack)) return 0;
        ItemStack stack = (ItemStack) value;
        if (!stack.hasTagCompound()) return 0;
        NBTTagCompound nbt = stack.getTagCompound();
        if (!nbt.hasKey("channels", 10)) return 0;
        NBTTagCompound channels = nbt.getCompoundTag("channels");
        return channels.hasKey(channel, 3) ? channels.getInteger(channel) : 0;
    }

    private static JsonObject anchor(Scene scene) {
        JsonObject result = vector(field(scene.gui, PREVIEW, "MB_PLACE_POS", true), VECTOR3I);
        long packed = pack(result.get("x").getAsInt(), result.get("y").getAsInt(), result.get("z").getAsInt());
        result.addProperty("verifiedController", type(scene.gui, GT_GUI) != null && scene.blocks.containsKey(packed));
        result.addProperty("source", "Existing public GuiMultiblockHandler.MB_PLACE_POS; GTGuiMultiblockHandler places the controller here facing SOUTH in the preview.");
        return result;
    }

    static JsonObject coordinates(long packed, int anchorX, int anchorY, int anchorZ) {
        JsonObject result = new JsonObject();
        int x = (int) (packed >> 38), y = (int) (packed << 52 >> 52), z = (int) (packed << 26 >> 38);
        JsonObject position = new JsonObject();
        position.addProperty("x", x); position.addProperty("y", y); position.addProperty("z", z);
        result.add("position", position);
        JsonObject relative = new JsonObject();
        relative.addProperty("x", x - anchorX); relative.addProperty("y", y - anchorY); relative.addProperty("z", z - anchorZ);
        result.add("controllerRelativePosition", relative);
        return result;
    }

    static long pack(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL);
    }

    private static void describeAction(Scene scene, List<?> buttons, int index, JsonObject entry) {
        Object value = buttons.get(index);
        if (value == null || !"ok".equals(entry.get("status").getAsString())) return;
        if (type(value, SLIDER) != null) {
            entry.addProperty("nativeAction", index == 0 ? "set_tier" : index == 1 ? "set_layer" : "set_channel");
            if (index >= 2) {
                String channel = channelAt(scene, index);
                if (channel != null) entry.addProperty("channel", channel);
                else entry.addProperty("nativeActionStatus", "unverified_channel_mapping");
                entry.addProperty("zeroMeaning", "Not set: master tier/default applies.");
            }
        } else {
            entry.addProperty("nativeAction", "press_control");
            String text = entry.has("displayString") && !entry.get("displayString").isJsonNull() ? entry.get("displayString").getAsString() : "";
            entry.addProperty("effect", "P".equals(text) ? "Project structure hints via the existing world-facing callback."
                : "?".equals(text) ? "Existing NEI overlay action; may close this screen and send the usual overlay packet."
                : "C".equals(text) ? "Copy preview channel data to the held hologram projector; callback can report missing projector."
                : "H".equals(text) ? "Toggle hatch-position highlight in this preview." : "unverified_button");
        }
    }

    static Scene scene(Object screen) {
        Class<?> gui = type(screen, GUI);
        if (gui == null || !(screen instanceof GuiScreen)) return null;
        Object handler = call(gui, screen, "getHandler");
        Class<?> handlerType = type(handler, HANDLER);
        if (handlerType == null) return null;
        Object active = call(handlerType, null, "getHandlerFromGui", new Class<?>[] {GuiScreen.class}, screen);
        if (active != handler) throw new Unavailable("Public preview handler did not match the current original handler.");
        Scene scene = new Scene();
        scene.handler = handler;
        scene.gui = field(handler, HANDLER, "guiHandler", false);
        if (type(scene.gui, PREVIEW) == null) throw new Unavailable("Matching preview GUI model is unavailable.");
        scene.recipeIndex = number(field(handler, HANDLER, "oldRecipe", false)).intValue();
        Object multiblocks = field(handler, HANDLER, "currentMultiblocks", false);
        Object controller = field(scene.gui, PREVIEW, "renderingController", false);
        Object previous = field(scene.gui, PREVIEW, "lastRenderingController", false);
        scene.controller = controller;
        Object visible = call(gui, screen, "getRecipeIndices");
        boolean selected = visible instanceof Collection<?> && ((Collection<?>) visible).contains(scene.recipeIndex);
        scene.built = selected && multiblocks != null && multiblocks.getClass().isArray()
            && scene.recipeIndex >= 0 && scene.recipeIndex < Array.getLength(multiblocks)
            && controller != null && Array.get(multiblocks, scene.recipeIndex) == controller && previous == controller;
        if (!scene.built) return scene;
        scene.renderer = field(scene.gui, PREVIEW, "renderer", false);
        if (scene.renderer == null) { scene.built = false; return scene; }
        if (type(scene.renderer, RENDERER) == null) throw new Unavailable("Matching preview renderer model is unavailable.");
        scene.world = field(scene.renderer, RENDERER, "world", true);
        if (scene.world == null) { scene.built = false; return scene; }
        if (type(scene.world, WORLD) == null) throw new Unavailable("Matching preview fake-world model is unavailable.");
        Object blocks = field(scene.world, WORLD, "blockMap", true);
        if (!(blocks instanceof Map<?, ?>)) throw new Unavailable("Existing preview blockMap is not a map.");
        scene.blocks = (Map<?, ?>) blocks;
        scene.metadata = map(field(scene.world, WORLD, "blockMetaMap", true));
        scene.tiles = map(field(scene.world, WORLD, "tileMap", true));
        scene.elements = map(field(scene.gui, PREVIEW, "structureElementMap", true));
        scene.rendered = collection(field(scene.renderer, RENDERER, "renderedBlocks", true));
        scene.translucent = collection(field(scene.renderer, RENDERER, "renderTranslucentBlocks", true));
        if (type(scene.gui, GT_GUI) != null) {
            scene.hatchDots = map(field(scene.gui, GT_GUI, "dotForPos", false));
            scene.hatchHints = map(field(scene.gui, GT_GUI, "hintForDot", false));
        }
        try {
            Object cacher = field(handler, HANDLER, "recipeCacher", false);
            Object results = field(cacher, HANDLER + "$RecipeCacher", "positionedResults", false);
            if (results instanceof List<?>) scene.candidates = (List<?>) results;
            else scene.candidateStatus = "Existing positionedResults is not a list.";
        } catch (Unavailable error) { scene.candidateStatus = error.getMessage(); }
        return scene;
    }

    static JsonObject block(Scene scene, Map.Entry<?, ?> entry, int index) {
        JsonObject result = new JsonObject();
        result.addProperty("index", index);
        if (!(entry.getKey() instanceof Long)) throw new Unavailable("Existing preview has a non-long packed coordinate.");
        long packed = (Long) entry.getKey();
        result.addProperty("packedPosition", Long.toString(packed));
        JsonObject anchor = anchor(scene);
        JsonObject positions = coordinates(packed, anchor.get("x").getAsInt(), anchor.get("y").getAsInt(), anchor.get("z").getAsInt());
        result.add("position", positions.get("position"));
        if (anchor.get("verifiedController").getAsBoolean()) result.add("controllerRelativePosition", positions.get("controllerRelativePosition"));
        else result.addProperty("controllerRelativePositionStatus", "unverified_controller_anchor");
        Object value = entry.getValue();
        if (!(value instanceof Block)) throw new Unavailable("Existing preview has a non-Block entry.");
        Block block = (Block) value;
        Object registry = Block.blockRegistry.getNameForObject(block);
        result.addProperty("registryName", registry instanceof String ? (String) registry : null);
        result.addProperty("blockClass", block.getClass().getName());
        Object metadata = scene.metadata.get(packed);
        int meta = metadata instanceof Number ? ((Number) metadata).intValue() : 0;
        result.addProperty("metadata", meta);
        result.addProperty("metadataEntryPresent", scene.metadata.containsKey(packed));
        result.addProperty("rendered", scene.rendered.contains(packed));
        result.addProperty("translucent", scene.translucent.contains(packed));
        Object tile = scene.tiles.get(packed);
        result.addProperty("tileClass", tile == null ? null : tile.getClass().getName());
        result.addProperty("tileNbtStatus", tile == null ? "not_present" : "unavailable_without_tile_serialization");
        Object element = scene.elements.get(packed);
        result.addProperty("structureElementClass", element == null ? null : element.getClass().getName());
        if (scene.hatchDots != null) {
            Object dot = scene.hatchDots.get(packed);
            if (dot instanceof Number) {
                int hintDot = ((Number) dot).intValue();
                result.addProperty("hatchHintDot", hintDot);
                text(result, "hatchHint", scene.hatchHints.get(hintDot), 2048);
                result.addProperty("hatchHintSource", "Existing GTGuiMultiblockHandler dotForPos and hintForDot; no element traversal or new tooltip/candidate scan.");
            }
        }
        Item item = Item.getItemFromBlock(block);
        if (item != null) {
            result.add("itemStack", stack(new ItemStack(item, 1, meta)));
            result.addProperty("itemStackSource", "Block item form with raw world metadata; not a drop or candidate identity.");
        } else result.add("itemStack", JsonNull.INSTANCE);
        machineIdentity(result, tile);
        return result;
    }

    private static void machineIdentity(JsonObject block, Object tile) {
        Class<?> tileApi = type(tile, GT_TILE);
        if (tileApi == null) return;
        JsonObject machine = new JsonObject();
        block.add("metaMachine", machine);
        block.add("actualStack", JsonNull.INSTANCE);
        machine.addProperty("source", "Existing BaseMetaTileEntity public getters implementing IGregTechTileEntity; raw block metadata is a separate value.");
        try {
            // Reject an unverified override of this otherwise simple stored-ID accessor.
            if (tile.getClass().getMethod("getMetaTileID").getDeclaringClass() != tileApi) {
                throw new Unavailable("getMetaTileID override has no verified read-only implementation.");
            }
            int id = number(call(tileApi, tile, "getMetaTileID")).intValue();
            machine.addProperty("metaTileId", id);
            Object meta = call(tileApi, tile, "getMetaTileEntity");
            if (meta == null) {
                machine.addProperty("status", "not_present");
                machine.addProperty("reason", "The existing tile has no valid existing MetaTileEntity; inspection does not create one.");
                return;
            }
            machine.addProperty("metaTileClass", meta.getClass().getName());
            Class<?> metaApi = type(meta, GT_META);
            if (metaApi == null) throw new Unavailable("Existing GT MTE is outside the verified standard MetaTileEntity identity implementation.");
            if (call(metaApi, meta, "getBaseMetaTileEntity") != tile) {
                throw new Unavailable("Existing MetaTileEntity belongs to a different base tile.");
            }
            Class<?> nameApi = type(meta, GT_COMMON_META);
            if (nameApi != null && meta.getClass().getMethod("getMetaName").getDeclaringClass() == nameApi) {
                Object name = call(nameApi, meta, "getMetaName");
                if (name instanceof String) {
                    text(machine, "metaName", name, 512);
                    machine.add("tooltipSource", machineTooltip((String) name, id));
                }
            } else machine.addProperty("metaNameStatus", "unsupported_unverified_getMetaName_override");
            if (meta.getClass().getMethod("getStackForm", long.class).getDeclaringClass() != metaApi) {
                throw new Unavailable("getStackForm override has no verified read-only implementation.");
            }
            Object actual = call(metaApi, meta, "getStackForm", new Class<?>[] {long.class}, 1L);
            if (!(actual instanceof ItemStack)) throw new Unavailable("Verified GT getStackForm returned no ItemStack.");
            block.add("actualStack", stack(actual));
            block.addProperty("actualStackSource", "Existing standard MetaTileEntity.getStackForm(1L), using the owning tile's actual MetaTile ID; no tile NBT serialization.");
            machine.addProperty("status", "ok");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            machine.addProperty("status", "unsupported");
            machine.addProperty("reason", error instanceof Unavailable ? error.getMessage()
                : "Existing GT identity read failed: " + error.getClass().getSimpleName());
        }
    }

    private static JsonObject machineTooltip(String metaName, int id) {
        JsonObject result = new JsonObject();
        String primary = "gt.blockmachines." + metaName + ".tooltip";
        String idKey = primary + "." + id;
        String key = StatCollector.canTranslate(idKey) ? idKey : primary;
        result.addProperty("source", "GT5 ItemMachines.addDescription generated primary translation key; existing language lookup only.");
        text(result, "primaryKey", key, 1024);
        text(result, "secondaryKey", primary + "_secondary", 1024);
        result.addProperty("primaryTranslated", StatCollector.canTranslate(key));
        if (StatCollector.canTranslate(key)) {
            String translation = StatCollector.translateToLocal(key);
            text(result, "primaryTranslation", translation, 2048);
            result.addProperty("requiresDescriptionParameters", translation.contains("%s"));
            result.addProperty("status", "existing_primary_translation");
        } else result.addProperty("status", "not_present");
        result.addProperty("coverage", "Primary language resource only; SkipGenerateDescription, secondary selection, formatting parameters and additional item tooltip lines can use callbacks or lazy caches and are not reconstructed.");
        return result;
    }

    private static JsonObject camera(Scene scene) {
        JsonObject result = new JsonObject();
        for (String name : new String[] {"rotationYaw", "rotationPitch", "zoom", "layerIndex", "scaleFactor"}) {
            result.addProperty(name, number(field(scene.gui, PREVIEW, name, false)));
        }
        result.add("center", vector(field(scene.gui, PREVIEW, "center", false), VECTOR3F));
        Class<?> api = type(scene.renderer, RENDERER);
        result.add("eyePosition", vector(call(api, scene.renderer, "getEyePos"), VECTOR3F));
        result.add("lookAt", vector(call(api, scene.renderer, "getLookAt"), VECTOR3F));
        result.add("worldUp", vector(call(api, scene.renderer, "getWorldUp"), VECTOR3F));
        result.addProperty("anglesSource", "Raw plugin fields in degrees; renderer camera vectors are current model values.");
        return result;
    }

    private static JsonObject bounds(Scene scene) {
        JsonObject result = new JsonObject();
        Class<?> api = type(scene.world, WORLD);
        result.add("minimum", vector(call(api, scene.world, "getMinPos"), VECTOR3F));
        result.add("maximum", vector(call(api, scene.world, "getMaxPos"), VECTOR3F));
        result.addProperty("source", "Existing preview world bounds; may include previously visited air placements.");
        return result;
    }

    static JsonObject selection(Scene scene) {
        JsonObject result = new JsonObject();
        Object selected = field(scene.gui, PREVIEW, "SELECTED_BLOCK", false);
        JsonObject pos = vector(selected, VECTOR3I);
        int x = pos.get("x").getAsInt(), y = pos.get("y").getAsInt(), z = pos.get("z").getAsInt();
        result.addProperty("present", !(x == -100000 && y == 0 && z == -100000));
        result.add("position", pos);
        return result;
    }

    static JsonObject hover(Scene scene) {
        JsonObject result = new JsonObject();
        Object trace = call(type(scene.renderer, RENDERER), scene.renderer, "getLastTraceResult");
        if (trace instanceof MovingObjectPosition) {
            MovingObjectPosition hit = (MovingObjectPosition) trace;
            JsonObject pos = new JsonObject();
            pos.addProperty("x", hit.blockX); pos.addProperty("y", hit.blockY); pos.addProperty("z", hit.blockZ);
            result.add("position", pos); result.addProperty("side", hit.sideHit);
        } else result.add("position", JsonNull.INSTANCE);
        result.add("itemStack", stack(field(scene.gui, PREVIEW, "tooltipBlockStack", false)));
        result.addProperty("source", "Previously rendered raytrace result and already-built hover ItemStack; no new raytrace or hover callback.");
        return result;
    }

    private static JsonObject vector(Object object, String owner) {
        JsonObject result = new JsonObject();
        for (String component : new String[] {"x", "y", "z"}) result.addProperty(component, number(field(object, owner, component, true)));
        return result;
    }

    static String contextOf(Scene scene) {
        StringBuilder result = new StringBuilder("preview:1.4.23:");
        result.append(identity(scene.handler)).append(':').append(identity(scene.gui)).append(':').append(scene.recipeIndex)
            .append(':').append(identity(scene.controller)).append(':').append(scene.built);
        if (!scene.built) return result.toString();
        result.append(':').append(NeiPreviewResources.context()).append(':').append(NeiPreviewDetailProvider.candidateContext(scene));
        result.append(':').append(identity(scene.renderer)).append(':').append(identity(scene.world))
            .append(':').append(identity(scene.blocks)).append(':').append(scene.blocks.size())
            .append(':').append(size(scene.rendered)).append(':').append(size(scene.translucent)).append(':').append(size(scene.tiles));
        Object controls = field(scene.gui, PREVIEW, "allButtons", false);
        result.append(':').append(identity(controls)).append(':').append(size(controls));
        result.append(':').append(number(field(scene.gui, PREVIEW, "layerIndex", false)));
        appendVector(result, field(scene.gui, PREVIEW, "SELECTED_BLOCK", false), VECTOR3I);
        Object trigger = field(scene.gui, PREVIEW, "trigger", false);
        if (trigger instanceof ItemStack) {
            ItemStack stack = (ItemStack) trigger;
            result.append(':').append(identity(stack)).append(':').append(stack.stackSize).append(':').append(stack.getMetadata())
                .append(':').append(identity(stack.getTagCompound()));
            int checked = 0;
            for (Map.Entry<?, ?> entry : channelMap(scene).entrySet()) {
                if (++checked > 62) { result.append(":channel_tail_unfingerprinted"); break; }
                if (!(entry.getKey() instanceof String)) throw new Unavailable("Channel fingerprint encountered a non-string identifier.");
                String channel = (String) entry.getKey();
                result.append(':').append(channel.length()).append(':').append(channel).append(':').append(entry.getValue())
                    .append(':').append(explicitChannel(stack, channel));
            }
        }
        Object search = field(scene.gui, PREVIEW, "lastSearch", false);
        if (search instanceof String) result.append(':').append(((String) search).hashCode());
        return result.toString();
    }

    private static void appendVector(StringBuilder result, Object object, String owner) {
        for (String component : new String[] {"x", "y", "z"}) result.append(':').append(number(field(object, owner, component, true)));
    }

    // Every owner/member passed below is a constant in this provider, never an argument from the caller.
    static Object field(Object target, String ownerName, String name, boolean publicOnly) {
        Class<?> owner = type(target, ownerName);
        if (owner == null) throw new Unavailable("Fixed preview owner unavailable: " + ownerName);
        String key = ownerName + '#' + name;
        try {
            Field field = FIELDS.get(key);
            if (field == null || field.getDeclaringClass() != owner) {
                field = publicOnly ? owner.getField(name) : owner.getDeclaredField(name);
                if (!publicOnly) field.setAccessible(true);
                FIELDS.put(key, field);
            }
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new Unavailable("Fixed preview field unavailable: " + ownerName + '.' + name);
        }
    }

    static Object call(Class<?> owner, Object target, String name) {
        return call(owner, target, name, new Class<?>[0]);
    }

    static Object call(Class<?> owner, Object target, String name, Class<?>[] signature, Object... args) {
        if (owner == null) throw new Unavailable("Fixed preview public API owner is unavailable.");
        String key = owner.getName() + '#' + name + Arrays.toString(signature);
        try {
            Method method = METHODS.get(key);
            if (method == null || method.getDeclaringClass() != owner) {
                method = owner.getMethod(name, signature);
                METHODS.put(key, method);
            }
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw new Unavailable("Preview read getter failed: " + name + '/' + error.getCause().getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new Unavailable("Fixed preview public API unavailable: " + owner.getName() + '.' + name);
        }
    }

    static Class<?> type(Object target, String name) {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            if (name.equals(type.getName())) return type;
        }
        return null;
    }

    private static Map<?, ?> map(Object object) {
        if (!(object instanceof Map<?, ?>)) throw new Unavailable("Fixed preview map model is unavailable.");
        return (Map<?, ?>) object;
    }

    private static Collection<?> collection(Object object) {
        if (!(object instanceof Collection<?>)) throw new Unavailable("Fixed preview render collection is unavailable.");
        return (Collection<?>) object;
    }

    private static Number number(Object object) {
        if (!(object instanceof Number)) throw new Unavailable("Fixed preview numeric model is unavailable.");
        Number number = (Number) object;
        if (Double.isNaN(number.doubleValue()) || Double.isInfinite(number.doubleValue())) throw new Unavailable("Preview numeric model is not finite.");
        return number;
    }

    private static int size(Object object) {
        if (object instanceof Map<?, ?>) return ((Map<?, ?>) object).size();
        return object instanceof Collection<?> ? ((Collection<?>) object).size() : 0;
    }

    static JsonElement stack(Object object) {
        if (!(object instanceof ItemStack)) return JsonNull.INSTANCE;
        ItemStack stack = (ItemStack) object;
        JsonObject result = new JsonObject();
        try {
            JsonElement identity = JsonData.item(stack);
            if (identity.isJsonObject()) result = identity.getAsJsonObject();
        } catch (RuntimeException | LinkageError error) {
            result.addProperty("identityError", error.getClass().getSimpleName());
        }
        if (stack.hasTagCompound()) {
            String nbt = stack.getTagCompound().toString();
            result.addProperty("nbt", JsonData.bounded(nbt, 8192));
            result.addProperty("nbtTruncated", nbt.length() > 8192);
        }
        // StackInfo.getFluid would populate NEI's fluid cache. Preview reads deliberately omit it.
        return result;
    }

    private static int identity(Object object) { return object == null ? 0 : System.identityHashCode(object); }
    private static int stringLength(Object object) { return object instanceof String ? ((String) object).length() : 0; }
    private static String boundedString(Object object, int limit) { return object instanceof String ? JsonData.bounded((String) object, limit) : ""; }
    private static boolean expired(long started) { return System.nanoTime() - started >= SOFT_NANOS; }
    private static int utf8(JsonObject object) { return object.toString().getBytes(StandardCharsets.UTF_8).length; }
    private static boolean booleanValue(JsonObject object, String key) { return object.has(key) && object.get(key).getAsBoolean(); }

    private static JsonObject base() {
        JsonObject result = new JsonObject();
        result.addProperty("source", SOURCE);
        result.addProperty("total", 0); result.addProperty("nextOffset", 0); result.addProperty("hasMore", false);
        result.addProperty("complete", false); result.addProperty("byteLimited", false); result.addProperty("softBudgetPartial", false);
        return result;
    }

    private static JsonObject unavailable(JsonObject result, String status, String reason) {
        result.addProperty("status", status); result.addProperty("reason", reason);
        result.addProperty("complete", false);
        return result;
    }

    private static void trimSummary(JsonObject result) {
        if (utf8(result) <= BYTE_LIMIT) return;
        JsonObject data = result.getAsJsonObject("data");
        for (String key : new String[] {"controllerStack", "triggerStack", "hover"}) {
            data.remove(key);
            data.addProperty(key + "ByteLimited", true);
        }
        result.addProperty("byteLimited", true); result.addProperty("complete", false);
        if (utf8(result) > BYTE_LIMIT) {
            data.remove("blocks");
            data.addProperty("blocksByteLimited", true);
            result.addProperty("nextOffset", 0);
            result.addProperty("hasMore", result.get("total").getAsInt() > 0);
        }
    }

    static final class Scene {
        Object handler, gui, controller, renderer, world;
        int recipeIndex;
        boolean built;
        Map<?, ?> blocks, metadata, tiles, elements, hatchDots, hatchHints;
        Collection<?> rendered, translucent;
        List<?> candidates;
        String candidateStatus;
    }

    private static final class Unavailable extends RuntimeException {
        Unavailable(String message) { super(message); }
    }
}
