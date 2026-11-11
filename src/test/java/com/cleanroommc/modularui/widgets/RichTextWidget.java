package com.cleanroommc.modularui.widgets;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.screen.RichTooltip;
public class RichTextWidget extends Widget {
    public final RichTooltip.Text rich = new RichTooltip.Text();
    public RichTooltip.Text getRichText() { return rich; }
}
