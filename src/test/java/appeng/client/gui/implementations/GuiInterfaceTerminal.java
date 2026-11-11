package appeng.client.gui.implementations;
import appeng.client.gui.widgets.MEGuiTextField;
import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
import java.util.HashMap;
import java.util.Map;
public class GuiInterfaceTerminal {
    public final MEGuiTextField searchFieldInputs = new MEGuiTextField(), searchFieldOutputs = new MEGuiTextField(), searchFieldNames = new MEGuiTextField();
    public boolean online = true, onlyMolecularAssemblers, onlyBrokenRecipes, onlySubstitute, showHidden;
    public final InterfaceTerminalList masterList = new InterfaceTerminalList();
    public static class InterfaceTerminalList { public final Map<Long, InterfaceTerminalEntry> list = new HashMap<Long, InterfaceTerminalEntry>(); }
    public static class InterfaceTerminalEntry {
        public long id; public String dispName = "interface fixture"; public int x = 3, y = 71, z = -8, dim = 0, side = 2, priority = 41, rows = 2, rowSize = 9, numSlots = 18;
        public boolean online = true, p2pOutput, terminalVisible = true, isCraftingPatternProvider = true;
        public Object[] supportedStackTypes = new Object[] {new AeGuiFixtures.Type("item"), new AeGuiFixtures.Type("fluid")};
        public final AeGuiFixtures.ItemInventory inv;
        public InterfaceTerminalEntry(long id) { this(id, 18); }
        public InterfaceTerminalEntry(long id, int size) { this.id = id; numSlots = size; inv = new AeGuiFixtures.ItemInventory(size); }
        public AeGuiFixtures.ItemInventory getInventory() { return inv; }
    }
}
