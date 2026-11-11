package appeng.container;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/** Test-only native class-name fixture; excluded from the production JAR. */
public class AEBaseContainer extends Container {
    public Object owner;
    public net.minecraft.entity.player.InventoryPlayer playerInv;
    public Object openContext = new Object();
    public Object getOpenContext() { return openContext; }
    public net.minecraft.entity.player.InventoryPlayer getPlayerInv() { return playerInv; }
    public Object getTarget() { return owner; }
    public void append(Slot slot) { addSlotToContainer(slot); }
    @Override public boolean canInteractWith(EntityPlayer player) { return true; }
}
