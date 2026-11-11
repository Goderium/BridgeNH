package codechicken.nei;
import java.util.Comparator;
import java.util.TreeSet;
import java.util.Set;
public class LayoutManager {
    public static TextField searchField = new TextField();
    private static Set<Widget> drawWidgets = widgets(false);
    private static Set<Widget> controlWidgets = widgets(true);
    private static final LayoutManager INSTANCE = new LayoutManager();
    public static LayoutManager instance() { return INSTANCE; }
    public Widget getWidgetUnderMouse(int x, int y) { return searchField; }
    public static Widget getInputFocused() { return null; }
    public static void layout() { throw new AssertionError("No layout update"); }
    // NEI 2.8.155 keeps equal-z widgets distinct, even when comparing a widget with itself.
    private static Set<Widget> widgets(final boolean topFirst, Widget... entries) {
        Set<Widget> result = new TreeSet<Widget>(new Comparator<Widget>() {
            @Override public int compare(Widget left, Widget right) {
                if (left.z == right.z) return 1;
                return topFirst ? Integer.compare(right.z, left.z) : Integer.compare(left.z, right.z);
            }
        });
        result.addAll(java.util.Arrays.asList(entries)); return result;
    }
    public static void fixtureWidgets(Widget... entries) { drawWidgets = widgets(false, entries); controlWidgets = widgets(true, entries); }
    public static void fixtureRecreate() { drawWidgets = widgets(false, drawWidgets.toArray(new Widget[0])); controlWidgets = widgets(true, controlWidgets.toArray(new Widget[0])); }
    public static boolean fixtureControlContains(Widget widget) { return controlWidgets.contains(widget); }
}
