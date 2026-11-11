package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import net.minecraft.client.settings.GameSettings;

/** Real vanilla option snapping and typed widget bindings; no window or settings writes. */
public class VanillaSettingsControlsSelfTest {
    private static int checks;
    static class Bound { private final GameSettings.Options actual = GameSettings.Options.FOV; }
    static class Ambiguous { private final GameSettings.Options first = GameSettings.Options.FOV, second = GameSettings.Options.GAMMA; }
    static class Missing { private final GameSettings.Options actual = null; }
    public static int run() {
        checks = 0;
        check(VanillaSettingsControls.enumBinding(new Bound(), Bound.class, GameSettings.Options.class) == GameSettings.Options.FOV, "Read the actual unique native enum field; never infer from a button ID");
        expect(() -> VanillaSettingsControls.enumBinding(new Ambiguous(), Ambiguous.class, GameSettings.Options.class));
        expect(() -> VanillaSettingsControls.enumBinding(new Missing(), Missing.class, GameSettings.Options.class));
        for (int value = 30; value <= 110; value++) {
            final int desired = value;
            int pixel = VanillaSettingsControls.pixel(value, 142, fraction -> GameSettings.Options.FOV.denormalizeValue((float) fraction));
            check(GameSettings.Options.FOV.denormalizeValue((float) pixel / 142) == desired, "Normal 150px FOV widget can attain each native integer option");
        }
        for (int steps : new int[] {1, 10, 142, 302, 4096}) {
            for (int i = 0; i <= 100; i++) {
                double requested = i / 100.0;
                int pixel = VanillaSettingsControls.pixel(requested, steps, fraction -> fraction);
                check(pixel >= 0 && pixel <= steps && Math.abs((double) pixel / steps - requested) <= .5 / steps + 1e-9, "Continuous native settings retain bounded integer-pixel quantization");
            }
        }
        for (double bad : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            expect(() -> VanillaSettingsControls.pixel(bad, 142, fraction -> fraction));
        for (int bad : new int[] {0, -1, 4097}) expect(() -> VanillaSettingsControls.pixel(.5, bad, fraction -> fraction));
        expect(() -> VanillaSettingsControls.pixel(.5, 142, fraction -> Double.NaN));
        return checks;
    }
    private static void expect(Runnable action) {
        try { action.run(); throw new AssertionError("Expected an unsupported native setting binding/range"); }
        catch (BridgeException expected) { check("unsupported".equals(expected.code), "Unknown native settings stay explicitly unsupported"); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
