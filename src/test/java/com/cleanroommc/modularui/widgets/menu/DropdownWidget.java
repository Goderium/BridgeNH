package com.cleanroommc.modularui.widgets.menu;
import com.cleanroommc.modularui.widget.Widget;
import java.util.Arrays;
import java.util.List;
import com.Goderium.BridgeNH.client.gui.WidgetFixtures;
public class DropdownWidget extends Widget {
    public final List<String> values = Arrays.asList("north", "south", "west");
    public final WidgetFixtures.StringValue value = new WidgetFixtures.StringValue(); public int changes;
    public DropdownWidget() { value.value = "north"; }
    public WidgetFixtures.StringValue getValue() { return value; }
    protected void setValue(Object value, boolean update) { if (update) { this.value.value = String.valueOf(value); changes++; } }
}
