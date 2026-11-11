package com.cleanroommc.modularui.screen;

import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

/** Test-only public native slot lookup fixture; not a real game GUI. */
public class ModularContainer extends Container {
    private final java.util.List<Slot> phantomSlots = new java.util.ArrayList<Slot>();
    private final com.cleanroommc.modularui.value.sync.ModularSyncManager syncManager = new com.cleanroommc.modularui.value.sync.ModularSyncManager();
    public ModularContainer() { syncManager.container=this; }
    public com.cleanroommc.modularui.value.sync.ModularSyncManager getSyncManager() { return syncManager; }
    public void addPhantom(Slot slot) { phantomSlots.add(slot); }
    public void add(Slot slot) { addSlotToContainer(slot); }
    public ModularSlot getModularSlot(int id) { return (ModularSlot) inventorySlots.get(id); }
    @Override public boolean canInteractWith(EntityPlayer player) { return true; }
}
