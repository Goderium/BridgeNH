package appeng.core.sync.packets;
import java.util.Map;
public class PacketMonitorableTypeFilter {
    public final Map<Object, Boolean> filters; public final int windowId;
    public PacketMonitorableTypeFilter(Map<Object, Boolean> filters, int windowId) { this.filters = filters; this.windowId = windowId; }
}
