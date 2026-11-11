package com.cleanroommc.modularui.value.sync;

public class PanelSyncManager {
    public boolean initialized = true, client = true, registered = true;
    public Object registeredHandler;
    public final ModularSyncManager modular;
    public final java.util.Map<String,Object> syncHandlers = new java.util.LinkedHashMap<String,Object>();
    public Object getSyncHandlerFromMapKey(String key) { return syncHandlers.get(key); }
    public PanelSyncManager(ModularSyncManager modular) { this.modular = modular; }
    public boolean isInitialised() { return initialized; }
    public boolean isClient() { return client; }
    public String getPanelName() { return "ghost"; }
    public Object getContainer() { return modular.getContainer(); }
}
