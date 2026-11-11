package com.cleanroommc.modularui.widgets;
import com.cleanroommc.modularui.widget.Widget;
public class AbstractCycleButtonWidget extends Widget {
    public int selected, changes; public int count = 3;
    public int getStateCount() { return count; } public IntSource getIntValue() { return new IntSource(this); }
    public static class IntSource { final AbstractCycleButtonWidget widget; public IntSource(AbstractCycleButtonWidget widget) { this.widget = widget; } public int getIntValue() { return widget.selected; } }
    public void setState(int state, boolean source) { if (state < 0 || state >= count) throw new IndexOutOfBoundsException(); selected = state; if (source) changes++; }
    public Object onMousePressed(int button) { setState((selected + 1) % count, true); return "SUCCESS"; } public boolean onMouseRelease(int button) { return true; }
}
