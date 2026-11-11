package net.minecraft.client.gui.inventory;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/** Test-only native shape, without client texture or renderer initialization. */
public abstract class GuiContainer extends GuiScreen {
    protected int xSize = 176;
    protected int ySize = 166;
    protected int guiLeft;
    protected int guiTop;
    public Container inventorySlots;

    public GuiContainer(Container container) { inventorySlots = container; }

    protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    protected void handleMouseClick(Slot slot, int number, int button, int type) {
        throw new AssertionError("The base alias must never handle a creative inventory wrapper");
    }

    protected void func_146984_a(Slot slot, int number, int button, int type) {
        throw new AssertionError("A fixture must record the native creative override");
    }
}
