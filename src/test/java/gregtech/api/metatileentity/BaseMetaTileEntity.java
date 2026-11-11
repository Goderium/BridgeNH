package gregtech.api.metatileentity;

public class BaseMetaTileEntity {
    public Object meta;
    private boolean mLockUpgrade = true;
    private byte mOtherUpgrades = 1;
    public Object getMetaTileEntity() { return meta; }
    public int getMetaTileID() { return 19420; }
    public boolean isActive() { return true; }
    public boolean isAllowedToWork() { return false; }
    public int getProgress() { return 12; }
    public int getMaxProgress() { return 40; }
    public long getStoredEU() { return 60000; }
    public long getEUCapacity() { return 100000; }
    public long getInputVoltage() { return 512; }
    public long getOutputVoltage() { return 0; }
    public int getUpgradeCount() { return 2; }
    public boolean isMuffled() { return true; }
    public boolean privateAccess() { return true; }
    public Message getLastShutDownReason() { return new Message(); }
    public void writeToNBT() { throw new AssertionError("Machine NBT write forbidden"); }
    public static class Message {
        public String getID() { return "fixture.shutdown"; }
        public String getDisplayString() { return "fixture stop reason"; }
        public boolean wasCritical() { return true; }
        public boolean wasSuccessful() { return false; }
        public boolean persistsOnShutdown() { return true; }
    }
}
