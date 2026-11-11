package com.cleanroommc.modularui.value.sync;

public class ModularSyncManager {
    public Object container = new Object();
    public final PanelSyncManager panel = new PanelSyncManager(this);
    private final java.util.Map<String,PanelSyncManager> panelSyncManagerMap = new java.util.LinkedHashMap<String,PanelSyncManager>();
    public ModularSyncManager() { panelSyncManagerMap.put("ghost",panel); }
    public PanelSyncManager getPanelSyncManager(String name) { return panelSyncManagerMap.get(name); }
    public Object getContainer() { return container; }
}
