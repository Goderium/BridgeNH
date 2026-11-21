package com.Goderium.BridgeNH.client.nei;

import com.Goderium.BridgeNH.client.gui.LogicalGuiPointer;
import com.Goderium.BridgeNH.client.gui.PersonalSpaceGuiAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import java.util.List;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;

/** Forge frame boundaries only; reads never trigger a render or refresh. */
public final class NeiRenderCapture {
    private static final String GUI = "codechicken.nei.recipe.GuiRecipe";
    private static final String NEI = "codechicken.nei.";
    private static final Class<?>[] NO_ARGS = new Class<?>[0];
    private static String lastSearch;
    private static long searchGeneration;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        boolean dedicated = dedicated(event.gui);
        if (!dedicated && !NeiUiAccess.is(event.gui, "net.minecraft.client.gui.inventory.GuiContainer")) { clear(); return; }
        String marker = marker(event.gui);
        if (marker == null) { clear(); return; }
        NeiNaturalRenderCapture.begin(event.gui, marker);
        NeiHoverCapture.begin(event.gui, marker, event.mouseX, event.mouseY);
        NeiTextCapture.begin(event.gui, marker, dedicated ? "dedicated_nei_screen" : "nei_call_paths_in_container",
                dedicated, event.gui.width, event.gui.height);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        PersonalSpaceGuiAdapter.rendered(event.gui);
        if (!dedicated(event.gui) && !NeiUiAccess.is(event.gui, "net.minecraft.client.gui.inventory.GuiContainer")) return;
        String marker = marker(event.gui);
        if (marker == null) { clear(); return; }
        NeiTextCapture.end(event.gui, marker); NeiHoverCapture.end(event.gui, marker); NeiNaturalRenderCapture.end(event.gui, marker);
    }

    @SubscribeEvent
    public void guiChanged(GuiOpenEvent event) { PersonalSpaceGuiAdapter.clearPending(); LogicalGuiPointer.clear("gui_open_event"); clear(); }

    private static void clear() { NeiTextCapture.clear(); NeiHoverCapture.clear(); NeiNaturalRenderCapture.clear(); }

    /** Optional public event, posted by Chromatic after its actual drawContent returns. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tooltipDrawn(Event event) { NeiHoverCapture.chromaticEvent(event); }

    static boolean dedicated(Object screen) {
        if (screen == null) return false;
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().startsWith("codechicken.nei.")) return true;
        return false;
    }

    static String marker(Object screen) {
        if (screen == null) return "";
        String dimensions = screen instanceof net.minecraft.client.gui.GuiScreen ? "/gui=" + ((net.minecraft.client.gui.GuiScreen) screen).width + 'x' + ((net.minecraft.client.gui.GuiScreen) screen).height : "";
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            StringBuilder marker = new StringBuilder(screen.getClass().getName()).append(dimensions)
                    .append('/').append(NeiPreviewResources.context()).append('/').append(LogicalGuiPointer.context());
            appendPanels(marker, loader);
            if (!NeiUiAccess.is(screen, GUI)) return marker.toString();
            int selected = ((Number) NeiUiAccess.publicField(loader, GUI, screen, "recipetype")).intValue();
            int page = NeiUiAccess.recipePage(loader, screen);
            Object values = NeiUiAccess.publicField(loader, GUI, screen, "currenthandlers");
            Object handler = values instanceof List<?> && selected >= 0 && selected < ((List<?>) values).size()
                    ? ((List<?>) values).get(selected) : null;
            marker.append('/').append(selected).append('/').append(page).append('/').append(System.identityHashCode(handler));
            Object visible = NeiUiAccess.publicCall(loader, GUI, screen, "getRecipeIndices", NO_ARGS);
            if (visible instanceof List<?>) {
                List<?> refs = (List<?>) visible; marker.append('/').append(refs.size());
                for (int i = 0; i < Math.min(refs.size(), 256); i++) marker.append('/').append(refs.get(i) instanceof Number ? refs.get(i) : "unsupported_ref");
            }
            return marker.append('/').append(NeiPreviewProvider.frameContext(screen)).toString();
        } catch (BridgeException failure) { return null; }
    }

    private static void appendPanels(StringBuilder marker, ClassLoader loader) {
        Object search = NeiUiAccess.publicField(loader, NEI + "LayoutManager", null, "searchField");
        String text = search == null ? null : (String) NeiUiAccess.publicCall(loader, NEI + "TextField", search, "text", NO_ARGS);
        if (!java.util.Objects.equals(text, lastSearch)) { lastSearch = text; searchGeneration++; }
        marker.append("/search=").append(searchGeneration);
        // LayoutManager creates its search field while installing NEI. Avoid initializing
        // ItemPanels ourselves while a container is drawing before that installation.
        if (search == null) return;
        Object items = NeiUiAccess.publicField(loader, NEI + "ItemPanels", null, "itemPanel");
        appendPanel(marker, loader, items, true);
        appendPanel(marker, loader, NeiUiAccess.publicField(loader, NEI + "ItemPanels", null, "bookmarkPanel"), false);
        appendPanel(marker, loader, items == null ? null : NeiUiAccess.optionalSubpanel(loader, items, "historyPanel"), false);
        appendPanel(marker, loader, items == null ? null : NeiUiAccess.optionalSubpanel(loader, items, "craftablesPanel"), false);
    }

    private static void appendPanel(StringBuilder marker, ClassLoader loader, Object panel, boolean filteredItems) {
        Object grid = panel == null ? null : NeiUiAccess.is(panel, NEI + "AbstractSubpanel")
                ? NeiUiAccess.get(loader, NEI + "AbstractSubpanel", panel, "grid")
                : NeiUiAccess.publicCall(loader, NEI + "PanelWidget", panel, "getGrid", NO_ARGS);
        marker.append('/').append(System.identityHashCode(panel)).append(':').append(System.identityHashCode(grid));
        if (grid == null) return;
        marker.append(':').append(NeiUiAccess.publicCall(loader, NEI + "ItemsGrid", grid, "getPage", NO_ARGS));
        if (filteredItems) {
            // The main item grid swaps its backing ArrayList when async search results arrive.
            Object list = NeiUiAccess.publicCall(loader, NEI + "ItemsGrid", grid, "getItems", NO_ARGS);
            marker.append(':').append(System.identityHashCode(list));
        }
    }
}
