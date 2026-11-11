package codechicken.nei;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.item.ItemStack;
public class ItemCraftablesPanel extends AbstractSubpanel {
    private final Object firstAnonymous = new Object() {};
    private Map<ItemStack, Object> availableRecipes = new HashMap<ItemStack, Object>();
    public ItemCraftablesPanel() { grid = new ItemsGrid() {
        protected List<ItemsGridSlot> gridMask;
        @Override public void fixtureMask(List<ItemsGridSlot> slots) { gridMask = slots; }
    }; }
    public void fixtureAvailable(ItemStack stack) { availableRecipes.put(stack, new Object()); }
}
