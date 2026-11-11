package appeng.client.gui.implementations;
import java.util.ArrayList;
import java.util.List;
import appeng.client.gui.widgets.MEGuiTextField;
import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
public class GuiCraftConfirm {
    public final List<Object> storage = new ArrayList<Object>(), pending = new ArrayList<Object>(), missing = new ArrayList<Object>();
    public final MEGuiTextField searchField = new MEGuiTextField();
    public AeGuiFixtures.CpuTableHolder table;
    public AeGuiFixtures.CpuTableHolder getCPUTable() { return table; }
}
