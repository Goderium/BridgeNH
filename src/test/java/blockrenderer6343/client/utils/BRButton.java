package blockrenderer6343.client.utils;

import net.minecraft.client.gui.GuiButton;

/** Exact stored fields of the installed 1.4.23 button; no sound, window or Minecraft instance. */
public class BRButton extends GuiButton {
    protected Runnable clickAction;
    protected String tooltip = "";
    protected int index;
    public BRButton(String text, int index, Runnable callback) {
        super(0, 0, 0, 100, 16, text);
        this.index = index; this.clickAction = callback;
    }
}
