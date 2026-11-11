package com.gtnewhorizons.modularui.common.internal.wrapper;
import com.gtnewhorizons.modularui.api.screen.ModularWindow;
import com.gtnewhorizons.modularui.api.widget.Widget;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiScreen;
public class ModularGui extends GuiScreen {
    public final Context context = new Context();
    public final ModularWindow main = new ModularWindow();
    public ModularGui() { main.context = context; context.windows.add(main); }
    public Context getContext() { return context; }
    public static class Context {
        public final List<ModularWindow> windows = new ArrayList<ModularWindow>();
        public final Cursor cursor = new Cursor();
        public Iterable<ModularWindow> getOpenWindows() { return windows; }
        public ModularWindow getCurrentWindow() { return windows.get(windows.size() - 1); }
        public Cursor getCursor() { return cursor; }
    }
    public static class Cursor {
        public Widget focused;
        public Widget getFocused() { return focused; }
        public void updateFocused(Widget widget) {
            if (focused == widget) return;
            if (focused != null) focused.onRemoveFocus();
            focused = widget.shouldGetFocus() ? widget : null;
        }
        public void removeFocus(Widget widget) { if (focused == widget) { widget.onRemoveFocus(); focused = null; } }
    }
}
