package appeng.client.gui.implementations;
import java.util.LinkedHashMap;
import java.util.Map;
import appeng.client.gui.widgets.MEGuiTextField;
public class GuiCraftingCPU {
    public final appeng.container.implementations.ContainerCraftingCPU container = new appeng.container.implementations.ContainerCraftingCPU();
    public java.util.List<?> hoveredInterfaceLocations;
    public void postInterfaceLocationsUpdate(java.util.List<?> locations) { hoveredInterfaceLocations = locations; }
    public final CraftingCpuVisualState visualState = new CraftingCpuVisualState(); public int remainingOperations = 23;
    public final MEGuiTextField searchField = new MEGuiTextField();
    public static class CraftingCpuVisualState { public final Map<Object, Object> entries = new LinkedHashMap<Object, Object>(); }
}
