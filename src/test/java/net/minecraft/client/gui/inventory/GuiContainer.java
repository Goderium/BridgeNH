package net.minecraft.client.gui.inventory;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Test-only native shape, without client texture or renderer initialization. */
public abstract class GuiContainer extends GuiScreen {
    protected int xSize = 176;
    protected int ySize = 166;
    protected int guiLeft;
    protected int guiTop;
    public Container inventorySlots;
    private long lastClickTime;
    private Slot lastClickSlot;
    private int lastClickButton;
    private boolean doubleClick;
    private ItemStack shiftClickedSlot;

    /** The 1.7.10 container's double-click predicate and the history written at the end of its press. */
    public boolean fixturePress(Slot slot, int button, long time, ItemStack shiftStack) {
        doubleClick = lastClickSlot == slot && time - lastClickTime < 250L && lastClickButton == button;
        lastClickSlot = slot;
        lastClickButton = button;
        lastClickTime = time;
        if (shiftStack != null) shiftClickedSlot = shiftStack;
        return doubleClick;
    }

    public boolean fixtureHasDoubleClickHistory() {
        return lastClickSlot != null || lastClickButton != -1 || lastClickTime != 0 || doubleClick || shiftClickedSlot != null;
    }

    public GuiContainer(Container container) { inventorySlots = container; }

    protected abstract void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY);

    protected void handleMouseClick(Slot slot, int number, int button, int type) {
        throw new AssertionError("The base alias must never handle a creative inventory wrapper");
    }

    protected void func_146984_a(Slot slot, int number, int button, int type) {
        throw new AssertionError("A fixture must record the native creative override");
    }
}
