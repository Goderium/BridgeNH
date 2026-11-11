package com.gtnewhorizons.modularui.api.screen;
import com.gtnewhorizons.modularui.api.widget.IWidgetParent;
import com.gtnewhorizons.modularui.api.widget.Widget;
import com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui;
import java.util.ArrayList;
import java.util.List;
public class ModularWindow implements IWidgetParent {
    public boolean enabled = true, initialized = true;
    public final List<Widget> children = new ArrayList<Widget>();
    public ModularGui.Context context;
    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }
    public List<Widget> getChildren() { return children; }
    public void add(Widget widget) { widget.window = this; children.add(widget); }
}
