package blockrenderer6343.client.utils;

import java.util.function.IntConsumer;

/** Matches the actual slider's last-draw clamp and native listener invocation. */
public class GuiSlider extends BRButton {
    private int maxValue, minValue, value;
    private boolean dragging;
    private Object maxValueSupplier, valueStringSupplier;
    public final String name;
    private float scale = 1;
    private final int originalHeight = 16;
    private final IntConsumer listener;
    public int fixtureCalls;
    public GuiSlider(String name, int index, int value, int min, int max, IntConsumer listener) {
        super("", index, null); this.name = name; this.value = value; this.minValue = min; this.maxValue = max; this.listener = listener;
    }
    public void setValue(int value) {
        fixtureCalls++; this.value = Math.max(minValue, Math.min(maxValue, value));
        if (listener != null) listener.accept(this.value);
    }
    public int fixtureValue() { return value; }
    public void fixtureMaximum(int max) { this.maxValue = max; }
}
