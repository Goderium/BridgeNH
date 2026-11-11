package appeng.client.gui.implementations;
import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
public class GuiPatternTermEx extends GuiPatternTerm {
    public final AeGuiFixtures.Scroll processingScrollBar = new AeGuiFixtures.Scroll(); public int visibilityUpdates;
    public void onUpdateInvertedOrActivePage() { visibilityUpdates++; }
}
