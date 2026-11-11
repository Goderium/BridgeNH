package com.gtnewhorizons.modularui.common.widget.textfield;
import java.util.function.Function;
/** Native-shaped fixture keeps edited client text separate from a later authoritative echo. */
public class TextFieldWidget extends BaseTextFieldWidget {
    public String text = "100", serverText = "100";
    public int commits, syncPackets, replacements;
    public boolean serverSync = true, clientSync = true, acceptFocus = true;
    public Function<String, String> validator = value -> value;
    public String getText() { return text; }
    public void setText(String text) { this.text = text; replacements++; }
    public boolean syncsToServer() { return serverSync; }
    public boolean syncsToClient() { return clientSync; }
    public boolean shouldGetFocus() { return acceptFocus; }
    public void onRemoveFocus() { text = validator.apply(text); commits++; if (serverSync) syncPackets++; }
}
