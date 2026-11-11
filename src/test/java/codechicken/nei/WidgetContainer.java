package codechicken.nei;
import java.util.ArrayList;
import java.util.List;
public class WidgetContainer {
    public final List<Widget> widgets = new ArrayList<Widget>();
    public List<Widget> getWidgets() { return widgets; }
    public Widget getWidgetUnderMouse(int x, int y) { for (Widget widget : widgets) if (x >= widget.x && y >= widget.y && x < widget.x + widget.w && y < widget.y + widget.h) return widget; return null; }
}
