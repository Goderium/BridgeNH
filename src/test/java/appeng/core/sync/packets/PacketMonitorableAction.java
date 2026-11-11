package appeng.core.sync.packets;
import appeng.helpers.MonitorableAction;
public class PacketMonitorableAction {
    public final MonitorableAction action; public final int custom;
    public PacketMonitorableAction(MonitorableAction action, int custom) { this.action = action; this.custom = custom; com.Goderium.BridgeNH.common.ae.AeGuiFixtures.events.add("action_constructed"); }
}
