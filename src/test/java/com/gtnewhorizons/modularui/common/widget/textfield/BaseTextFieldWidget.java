package com.gtnewhorizons.modularui.common.widget.textfield;
import com.gtnewhorizons.modularui.api.widget.Widget;
public class BaseTextFieldWidget extends Widget {
    protected final Handler handler = new Handler();
    public static class Handler { public boolean test(String text) { return text.length() <= 12 && text.matches("[0-9]*"); } }
}
