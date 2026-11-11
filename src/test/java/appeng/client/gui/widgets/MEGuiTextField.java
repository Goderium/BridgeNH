package appeng.client.gui.widgets;

/** Plain data fixture, without real GuiTextField/window/LWJGL initialization. */
public class MEGuiTextField {
    public final Object field = new Object();
    public int x = 3, y = 5, w = 80, h = 12, edits;
    public String text = ""; public boolean focused;
    public String getText() { return text; }
    public void setText(String text) { this.text = text; edits++; }
    public int getMaxStringLength() { return 1024; }
    public boolean isFocused() { return focused; }
    public void setFocused(boolean focused) { this.focused = focused; }
}
