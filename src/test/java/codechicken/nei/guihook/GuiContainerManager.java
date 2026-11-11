package codechicken.nei.guihook;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.inventory.GuiContainer;

public final class GuiContainerManager {
    public static final List<IContainerTooltipHandler> tooltipHandlers = new ArrayList<IContainerTooltipHandler>();
    public final List<IContainerTooltipHandler> instanceTooltipHandlers = new ArrayList<IContainerTooltipHandler>();
    public static GuiContainerManager current;
    public static boolean tooltipAllowed = true;
    public static int tooltipPredicateCalls;
    public static boolean shouldShowTooltip(GuiContainer screen) { tooltipPredicateCalls++; return tooltipAllowed; }
    public static void addTooltipHandler(IContainerTooltipHandler observer) { tooltipHandlers.add(observer); }
    public static GuiContainerManager getManager(GuiContainer screen) { return current; }
}
