package codechicken.nei;
import java.util.List;
public class ItemHistoryPanel extends AbstractSubpanel {
    public ItemHistoryPanel() { grid = new ItemsGrid() {
        protected List<ItemsGridSlot> gridMask;
        @Override public void fixtureMask(List<ItemsGridSlot> slots) { gridMask = slots; }
    }; }
}
