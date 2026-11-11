package appeng.client.gui.implementations;

import appeng.client.me.ItemRepo;
import appeng.client.gui.widgets.MEGuiTextField;
import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
import java.util.Map;

public class GuiMEMonitorable {
    public final ItemRepo repo = new ItemRepo();
    public final MEGuiTextField searchField = new MEGuiTextField();
    public final AeGuiFixtures.Config configSrc = new AeGuiFixtures.Config();
    public final appeng.util.AEStackTypeFilter typeFilters = new appeng.util.AEStackTypeFilter();
    public Object getSortBy() { return "NAME"; } public Object getSortDir() { return "ASCENDING"; }
    public Object getSortDisplay() { return "ALL"; } public Map<Object, Boolean> getTypeFilter() { return typeFilters.getFiltersMap(); }
}
