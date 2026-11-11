package com.gtnewhorizons.modularui.common.widget;
import com.gtnewhorizons.modularui.api.widget.Widget;
public class TextWidget extends Widget {
    public String text = "Machine address";
    public Text getText() { return new Text(text); }
    public static class Text {
        public final String value;
        public Text(String value) { this.value = value; }
        public String getFormatted() { return value; }
    }
}
