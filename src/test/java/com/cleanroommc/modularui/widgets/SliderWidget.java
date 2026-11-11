package com.cleanroommc.modularui.widgets;
import com.cleanroommc.modularui.widget.Widget;
public class SliderWidget extends Widget {
    public double value; public int changes;
    public double getMin() { return -2.0; } public double getMax() { return 5.0; } public double getSliderValue() { return value; }
    public void setValue(double value, boolean source) { this.value = Math.min(5, Math.max(-2, value)); if (source) changes++; }
}
