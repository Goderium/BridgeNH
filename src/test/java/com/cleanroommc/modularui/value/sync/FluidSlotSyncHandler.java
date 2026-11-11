package com.cleanroommc.modularui.value.sync;

import net.minecraftforge.fluids.FluidStack;

/** Offline registered phantom-fluid cache fixture, not a real tank or native GUI. */
public class FluidSlotSyncHandler {
    public FluidStack value;
    public boolean phantom=true;
    public final PanelSyncManager manager;
    public final String key;
    public FluidSlotSyncHandler(PanelSyncManager manager,String key) { this.manager=manager; this.key=key; }
    public FluidStack getValue() { return value; }
    public boolean isPhantom() { return phantom; }
    public String getKey() { return key; }
    public PanelSyncManager getSyncManager() { return manager; }
    public boolean isRegistered(PanelSyncManager actual) { return actual==manager && manager.getSyncHandlerFromMapKey(key)==this; }
}
