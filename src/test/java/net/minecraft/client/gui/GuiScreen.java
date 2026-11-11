package net.minecraft.client.gui;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Test-only native shape; the real static RenderItem requires a running graphics environment. */
public class GuiScreen extends Gui {
    public Minecraft mc;
    public int width;
    public int height;
    protected List buttonList = new ArrayList();
    protected List labelList = new ArrayList();
    public boolean allowUserInput;
    public void drawScreen(int mouseX, int mouseY, float partialTicks) { throw new AssertionError("A fixture must record the original draw callback"); }

    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        throw new AssertionError("A fixture must record the native press callback");
    }

    protected void mouseReleased(int mouseX, int mouseY, int state) {
        throw new AssertionError("A fixture must record the native release callback");
    }

    protected void keyTyped(char typedChar, int keyCode) {
        throw new AssertionError("A fixture must record the native key callback");
    }

    protected void actionPerformed(GuiButton button) {
        throw new AssertionError("A fixture must record the native button callback");
    }
}
