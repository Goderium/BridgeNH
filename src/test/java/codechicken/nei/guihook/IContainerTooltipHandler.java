package codechicken.nei.guihook;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

/** Shape of the installed NEI native listener API. */
public interface IContainerTooltipHandler {
    default List<String> handleTooltip(GuiContainer gui, int x, int y, List<String> lines) { return lines; }
    default List<String> handleItemDisplayName(GuiContainer gui, ItemStack stack, List<String> lines) { return lines; }
    default List<String> handleItemTooltip(GuiContainer gui, ItemStack stack, int x, int y, List<String> lines) { return lines; }
    default Map<String, String> handleHotkeys(GuiContainer gui, int x, int y, Map<String, String> keys) { return keys; }
}
