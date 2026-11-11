package appeng.container.implementations;

import com.Goderium.BridgeNH.common.ae.CellWorkbenchFixtures;

/** Public CellWorkbench container/sync names checked against AE rv3-beta-1073 sources. */
public class ContainerCellWorkbench {
    public final CellWorkbenchFixtures.Sync configSync;
    public final CellWorkbenchFixtures.SyncManager syncManager;
    public boolean haveCell = true;
    public Object acceptedType;
    public int clearActions;
    public ContainerCellWorkbench(int size) { configSync = new CellWorkbenchFixtures.Sync(size); syncManager = new CellWorkbenchFixtures.SyncManager(configSync); }
    public CellWorkbenchFixtures.SyncManager getSyncManager() { return syncManager; }
    public boolean haveCell() { return haveCell; }
    public Object getStackType() { return acceptedType; }
}
