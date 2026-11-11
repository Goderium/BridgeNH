package gregtech.common.modularui2.widget;

/** Public shared-widget fixture: native click queues synchronization without changing client inventory. */
public class GhostCircuitSlotWidget extends com.cleanroommc.modularui.widget.Widget {
    public Handler handler = new Handler(); public int presses, releases; public boolean mouseBlocked;
    public Handler getSyncHandler() { return handler; }
    public Object onMousePressed(int button) { if (button != 0) throw new AssertionError("Only a neutral left click is supported"); if (mouseBlocked) throw new IllegalStateException("Physical mouse path is blocked"); presses++; return null; }
    public boolean onMouseRelease(int button) { releases++; return true; }
    public static class Handler extends gregtech.common.modularui2.sync.GhostCircuitSyncHandler {
        public Circuit circuit = new Circuit(); public Circuit getGhostCircuitHandler() { return circuit; }
    }
    public static class Circuit { public int config = -1; public int getCircuitConfig() { return config; } public void setCircuitConfig(int config) { throw new AssertionError("Direct ghost inventory write forbidden"); } }
    public static class Screen extends com.Goderium.BridgeNH.client.gui.WidgetFixtures.Screen {
        public final Modular modular = new Modular(root);
        @Override public Modular getScreen() { return modular; }
    }
    public static class Modular extends com.Goderium.BridgeNH.client.gui.WidgetFixtures.Modular {
        public final com.cleanroommc.modularui.value.sync.ModularSyncManager sync = new com.cleanroommc.modularui.value.sync.ModularSyncManager();
        public Object container = sync.container;
        public Modular(com.cleanroommc.modularui.widget.Widget root) { super(root); }
        public com.cleanroommc.modularui.value.sync.ModularSyncManager getSyncManager() { return sync; }
        public Object getContainer() { return container; }
    }
}
