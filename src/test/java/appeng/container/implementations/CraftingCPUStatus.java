package appeng.container.implementations;
public class CraftingCPUStatus {
    public Object serverCluster; public Object getServerCluster() { return serverCluster; }
    public int serial = 7; public boolean busy = true, paused; public Object crafting;
    public String getName() { return "fixture CPU"; } public int getSerial() { return serial; }
    public long getStorage() { return 9007199254740993L; } public long getUsedStorage() { return 77L; } public long getCoprocessors() { return 3L; }
    public long getTotalItems() { return Long.MAX_VALUE; } public long getRemainingItems() { return 42L; } public long getCraftingElapsedTime() { return 99L; }
    public Object getCrafting() { return crafting; } public boolean isBusy() { return busy; } public boolean isSuspended() { return paused; } public Object allowMode() { return "ALLOW_ALL"; }
}
