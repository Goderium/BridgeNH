package appeng.client.gui.slots;

import com.Goderium.BridgeNH.common.ae.CellWorkbenchFixtures;

/** Fixed native public slot API fixture, excluded from the mod JAR. */
public class VirtualMEPhantomSlot {
    public final CellWorkbenchFixtures.Inventory inventory;
    public final int index;
    public boolean hidden;
    public VirtualMEPhantomSlot(CellWorkbenchFixtures.Inventory inventory, int index) {
        this.inventory = inventory; this.index = index;
    }
    public int getSlotIndex() { return index; }
    public String getStorageName() { return inventory.getStorageName(); }
    public boolean isHidden() { return hidden; }
    public int getX() { return 8 + (index % 9) * 18; }
    public int getY() { return 29 + (index / 9) * 18; }
    public Object getAEStack() { return inventory.getAEStackInSlot(index); }
}
