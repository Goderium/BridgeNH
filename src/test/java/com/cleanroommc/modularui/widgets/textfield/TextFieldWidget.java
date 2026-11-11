package com.cleanroommc.modularui.widgets.textfield;
import com.Goderium.BridgeNH.client.gui.WidgetFixtures;
public class TextFieldWidget extends BaseTextFieldWidget {
    public String text = ""; public boolean focused; public int commits;
    public final WidgetFixtures.StringValue value = new WidgetFixtures.StringValue();
    public final WidgetFixtures.Context context = new WidgetFixtures.Context();
    public String getText() { return text; } public void setText(String text) { this.text = text; }
    public boolean isFocused() { return focused; } public WidgetFixtures.StringValue getStringValue() { return value; }
    public WidgetFixtures.Context getContext() { return context; }
}
