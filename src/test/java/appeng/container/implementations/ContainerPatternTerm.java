package appeng.container.implementations;

import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;

public class ContainerPatternTerm extends ContainerMEMonitorable {
    public final AeGuiFixtures.InventorySync inputsSync = new AeGuiFixtures.InventorySync(4);
    public final AeGuiFixtures.InventorySync outputsSync = new AeGuiFixtures.InventorySync(3);
    public final AeGuiFixtures.BooleanSync craftingModeSync = new AeGuiFixtures.BooleanSync();
    public final AeGuiFixtures.BooleanSync substituteSync = new AeGuiFixtures.BooleanSync();
    public final AeGuiFixtures.BooleanSync beSubstituteSync = new AeGuiFixtures.BooleanSync();
    public final AeGuiFixtures.Action encodeAction = new AeGuiFixtures.Action(), encodeAndMoveToInventoryAction = new AeGuiFixtures.Action(), clearAction = new AeGuiFixtures.Action(), doubleAction = new AeGuiFixtures.Action();
    public boolean getCraftingModeSupport() { return true; } public boolean isCraftingMode() { return craftingModeSync.get(); }
    public int getPatternInputsWidth() { return 2; } public int getPatternInputsHeigh() { return 1; } public int getPatternInputPages() { return 2; }
    public int getPatternOutputsWidth() { return 1; } public int getPatternOutputsHeigh() { return 1; } public int getPatternOutputPages() { return 3; }
}
