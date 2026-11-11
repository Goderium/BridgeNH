package com.cleanroommc.modularui.widgets;

import com.Goderium.BridgeNH.util.BridgeException;

/** Exact native class identity with inherited public state setter and observable mouse callbacks. */
public class ToggleButton extends AbstractCycleButtonWidget {
    public int presses, releases;
    public boolean mouseBlocked;
    public ToggleButton() { count = 2; }
    @Override public Object onMousePressed(int button) {
        presses++;
        if (mouseBlocked) throw new BridgeException("input_busy", "Fixture mouse activation is busy.");
        return super.onMousePressed(button);
    }
    @Override public boolean onMouseRelease(int button) { releases++; return super.onMouseRelease(button); }
}
