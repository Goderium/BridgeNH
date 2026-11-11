package codechicken.nei;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;
public class ItemsPanelGrid extends ItemsGrid {
    protected List<ItemsGridSlot> gridMask = new ArrayList<ItemsGridSlot>();
    public void fixtureVisible(ItemStack stack) { gridMask.add(new ItemsGridSlot(3, 0, stack)); }
    @Override public ItemsGridSlot getSlotMouseOver(int x, int y) {
        for (ItemsGridSlot slot : gridMask) { codechicken.lib.vec.Rectangle4i r=getSlotRect(slot.slotIndex); if (x>=r.x && x<r.x+r.w && y>=r.y && y<r.y+r.h) return slot; }
        return null;
    }
    @Override public void fixtureMask(List<ItemsGridSlot> slots) { gridMask = slots; }
}
