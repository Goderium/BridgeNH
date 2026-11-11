package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.settings.GameSettings;

/** Actual vanilla settings widgets, using their normal native press/release path. */
public final class VanillaSettingsControls {
    public static final int VERSION = 1;
    private static final String SOUND_SLIDER = "net.minecraft.client.gui.GuiScreenOptionsSounds$Button";

    static boolean supports(Object widget) {
        return widget instanceof GuiOptionSlider || ExpandedGuiApi.is(widget, SOUND_SLIDER);
    }

    // Resolve the actual widget's one fixed enum field by type, independently of
    // MCP/SRG field spelling. Button IDs and translated display text are not bindings.
    public static <T> T enumBinding(Object widget, Class<?> owner, Class<T> type) {
        if (widget == null || owner == null || !owner.isInstance(widget)) throw new BridgeException("unsupported", "Native widget binding owner is unavailable.");
        T found = null;
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != type) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(widget);
                if (!type.isInstance(value) || found != null) throw new BridgeException("unsupported", "Native settings widget has no unique actual enum binding.");
                found = type.cast(value);
            } catch (IllegalAccessException | SecurityException error) {
                throw new BridgeException("unsupported", "Native settings widget binding is unavailable.");
            }
        }
        if (found == null) throw new BridgeException("unsupported", "Native settings widget binding is unavailable.");
        return found;
    }

    private static GameSettings.Options option(Object widget) { return enumBinding(widget, GuiOptionSlider.class, GameSettings.Options.class); }
    private static SoundCategory sound(Object widget) { return enumBinding(widget, ExpandedGuiApi.named(widget.getClass(), SOUND_SLIDER), SoundCategory.class); }

    static JsonObject read(GuiButton widget) {
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        JsonObject result = new JsonObject();
        if (widget instanceof GuiOptionSlider) {
            GameSettings.Options actual = option(widget);
            result.addProperty("setting", actual.name());
            result.addProperty("value", settings.getOptionFloatValue(actual));
            result.addProperty("min", actual.denormalizeValue(0));
            result.addProperty("max", actual.denormalizeValue(1));
            result.addProperty("normalizedValue", actual.normalizeValue(settings.getOptionFloatValue(actual)));
        } else {
            SoundCategory actual = sound(widget);
            result.addProperty("soundCategory", actual.getCategoryName());
            result.addProperty("value", settings.getSoundLevel(actual));
            result.addProperty("min", 0); result.addProperty("max", 1);
            result.addProperty("normalizedValue", settings.getSoundLevel(actual));
        }
        result.addProperty("valueSource", "actual_widget_enum_and_public_GameSettings_getter");
        result.addProperty("resolution", "Native integer GUI position and option snapping; requested values may be quantized. Inspect actual readback.");
        return result;
    }

    static int pixel(double requested, int steps, DoubleUnaryOperator valueAtPixel) {
        if (!Double.isFinite(requested) || steps < 1 || steps > 4096)
            throw new BridgeException("unsupported", "Native slider range or bounded pixel geometry is unavailable.");
        int best = 0; double error = Double.POSITIVE_INFINITY;
        for (int i = 0; i <= steps; i++) {
            double value = valueAtPixel.applyAsDouble((double) i / steps);
            if (!Double.isFinite(value)) throw new BridgeException("unsupported", "Native slider has a nonfinite value.");
            double difference = Math.abs(value - requested);
            if (difference < error) { error = difference; best = i; }
        }
        return best;
    }

    static JsonObject setValue(GuiButton widget, JsonObject args, BooleanSupplier current) {
        JsonObject before = read(widget);
        double requested = JsonData.number(args, "value", before.get("min").getAsDouble(), before.get("max").getAsDouble());
        final GameSettings.Options actual = widget instanceof GuiOptionSlider ? option(widget) : null;
        int steps = widget.width - 8;
        int selected = pixel(requested, steps, fraction -> actual == null ? (float) fraction : actual.denormalizeValue((float) fraction));
        if (!current.getAsBoolean()) throw new BridgeException("stale_screen", "The native settings screen changed before slider use.");
        int x = widget.xPosition + 4 + selected, y = widget.yPosition + widget.height / 2;
        boolean accepted = false;
        try {
            accepted = widget.mousePressed(Minecraft.getMinecraft(), x, y);
            if (!accepted) throw new BridgeException("disabled", "The actual native slider refused the press.");
        } finally {
            // Release this exact owned widget even if the screen changes or the callback fails.
            widget.mouseReleased(x, y);
        }
        JsonObject result = new JsonObject();
        result.addProperty("nativeAction", "vanilla_setting_slider_press_release");
        result.addProperty("pressAccepted", accepted); result.addProperty("released", true);
        result.addProperty("requestedValue", requested); result.add("before", before);
        JsonObject after = read(widget); result.add("after", after);
        result.addProperty("exactRequestedValue", Math.abs(after.get("value").getAsDouble() - requested) <= 0.000001);
        result.addProperty("persistenceVerified", false);
        result.addProperty("scope", "Normal native settings slider callbacks and actual client setting readback; use the menu's normal Done control and observe after restart for persistence.");
        return result;
    }

    public static JsonObject settings(Minecraft client) {
        JsonObject result = new JsonObject();
        if (client.gameSettings == null) { result.addProperty("available", false); return result; }
        result.addProperty("available", true);
        result.addProperty("fieldOfView", client.gameSettings.getOptionFloatValue(GameSettings.Options.FOV));
        result.addProperty("mouseSensitivity", client.gameSettings.getOptionFloatValue(GameSettings.Options.SENSITIVITY));
        result.addProperty("masterVolume", client.gameSettings.getSoundLevel(SoundCategory.MASTER));
        result.addProperty("difficulty", client.gameSettings.difficulty.getDifficultyId());
        result.addProperty("source", "current_native_client_GameSettings; not a save-file or server-state observation");
        return result;
    }
}
