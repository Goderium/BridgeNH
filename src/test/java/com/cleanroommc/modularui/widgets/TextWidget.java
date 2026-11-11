package com.cleanroommc.modularui.widgets;
import com.cleanroommc.modularui.widget.Widget;
public class TextWidget extends Widget {
    public String text = "fixture label";
    public Key getKey() { return new Key(text); }
    public static class Key { private final String text; public Key(String text) { this.text = text; } public String getFormatted() { return text; } }
}
