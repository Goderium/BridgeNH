package codechicken.nei;
public class PanelWidget extends Widget {
    public net.minecraft.item.ItemStack draggedStack;
    public int mouseDownSlot = -1;
    public int presses, releases;
    public boolean handleClick(int x, int y, int button) {
        presses++; ItemsGrid.ItemsGridSlot slot=grid.getSlotMouseOver(x,y);
        if (slot==null) return false;
        if (button==2) draggedStack=slot.getItemStack().copy(); else mouseDownSlot=slot.slotIndex;
        return true;
    }
    public void mouseUp(int x, int y, int button) { throw new AssertionError("Never invoke bookmark/panel mouseUp: it can commit bookmark changes"); }
    public final ItemsPanelGrid grid = new ItemsPanelGrid();
    public ItemsGrid getGrid() { return grid; }
    public String getLabelText() { return "fixture panel"; }
}
