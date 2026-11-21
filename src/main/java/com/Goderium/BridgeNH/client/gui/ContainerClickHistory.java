package com.Goderium.BridgeNH.client.gui;

import cpw.mods.fml.relauncher.ReflectionHelper;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.inventory.GuiContainer;

/** Vanilla keeps the first half of a double-click on the screen after a complete click has returned. */
public final class ContainerClickHistory {
    private boolean lastPressFromAgent;

    public void press(GuiContainer gui, boolean fromAgent, Runnable nativeClick, BooleanSupplier stillCurrent) {
        if (lastPressFromAgent != fromAgent) clear(gui);
        lastPressFromAgent = fromAgent;
        try {
            nativeClick.run();
        } finally {
            // Vanilla writes the history at the end of mouseClicked, potentially after a button
            // or mod listener has already closed the screen or handed back control inside it.
            if (fromAgent && (!lastPressFromAgent || !stillCurrent.getAsBoolean())) {
                lastPressFromAgent = false;
                clear(gui);
            }
        }
    }

    public void handoff(GuiContainer gui) {
        if (lastPressFromAgent) {
            lastPressFromAgent = false;
            clear(gui);
        }
    }

    private static void clear(GuiContainer gui) {
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, null, "field_146998_K", "lastClickSlot");
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, 0L, "field_146997_J", "lastClickTime");
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, -1, "field_146992_L", "lastClickButton");
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, false, "field_146993_M", "doubleClick");
        // Vanilla's shift-double-click collection compares against this remembered stack.
        ReflectionHelper.setPrivateValue(GuiContainer.class, gui, null, "field_146994_N", "shiftClickedSlot");
    }
}
