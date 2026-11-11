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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void beforeDraw(GuiScreenEvent.DrawScreenEvent.Pre event) {
        boolean dedicated = dedicated(event.gui);
        if (!dedicated && !NeiUiAccess.is(event.gui, "net.minecraft.client.gui.inventory.GuiContainer")) { NeiTextCapture.clear(); NeiHoverCapture.clear(); NeiNaturalRenderCapture.clear(); return; }
        NeiNaturalRenderCapture.begin(event.gui, marker(event.gui));
        NeiHoverCapture.begin(event.gui, marker(event.gui), event.mouseX, event.mouseY);
        NeiTextCapture.begin(event.gui, marker(event.gui), dedicated ? "dedicated_nei_screen" : "nei_call_paths_in_container",
                dedicated, event.gui.width, event.gui.height);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void afterDraw(GuiScreenEvent.DrawScreenEvent.Post event) { PersonalSpaceGuiAdapter.rendered(event.gui); String marker = marker(event.gui); NeiTextCapture.end(event.gui, marker); NeiHoverCapture.end(event.gui, marker); NeiNaturalRenderCapture.end(event.gui, marker); }

    @SubscribeEvent
    public void guiChanged(GuiOpenEvent event) { PersonalSpaceGuiAdapter.clearPending(); LogicalGuiPointer.clear("gui_open_event"); NeiTextCapture.clear(); NeiHoverCapture.clear(); NeiNaturalRenderCapture.clear(); }

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
        if (!NeiUiAccess.is(screen, GUI)) return screen.getClass().getName() + '/' + NeiPreviewResources.context() + '/' + LogicalGuiPointer.context() + dimensions;
        try {
            ClassLoader loader = screen.getClass().getClassLoader();
            int selected = ((Number) NeiUiAccess.publicField(loader, GUI, screen, "recipetype")).intValue();
            Object page = NeiUiAccess.publicField(loader, GUI, screen, "page");
            Object values = NeiUiAccess.publicField(loader, GUI, screen, "currenthandlers");
            Object handler = values instanceof List<?> && selected >= 0 && selected < ((List<?>) values).size()
                    ? ((List<?>) values).get(selected) : null;
            StringBuilder marker = new StringBuilder(); marker.append(selected).append('/').append(page).append('/').append(System.identityHashCode(handler));
            Object visible = NeiUiAccess.publicCall(loader, GUI, screen, "getRecipeIndices", new Class<?>[0]);
            if (visible instanceof List<?>) {
                List<?> refs = (List<?>) visible; marker.append('/').append(refs.size());
                for (int i = 0; i < Math.min(refs.size(), 256); i++) marker.append('/').append(refs.get(i) instanceof Number ? refs.get(i) : "unsupported_ref");
            }
            marker.append('/').append(NeiPreviewProvider.frameContext(screen)).append('/').append(NeiPreviewResources.context()).append('/').append(LogicalGuiPointer.context());
            return marker.append(dimensions).toString();
        } catch (BridgeException failure) { return "unsupported_current_view_marker"; }
    }
}
