package com.gtnewhorizons.modularui.common.widget;
import com.gtnewhorizons.modularui.api.widget.Widget;
public class ButtonWidget extends Widget {
    public int clicks;
    public enum ClickResult { ACCEPT, ACKNOWLEDGED }
    public ClickResult onClick(int button, boolean doubleClick) { clicks++; return ClickResult.ACCEPT; }
}
