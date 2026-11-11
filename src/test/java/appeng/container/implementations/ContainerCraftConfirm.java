package appeng.container.implementations;
public class ContainerCraftConfirm extends appeng.container.AEBaseContainer {
    public Runnable onStart;
    public void startJob() { onStart.run(); }
    public final ContainerCPUTable cpuTable = new ContainerCPUTable(); public boolean simulation, noCpu; public Object requested;
    public String serializedItemToCraft = ""; public int targetReads; public boolean corruptTarget;
    public long getUsedBytes() { return 9007199254740993L; } public boolean isSimulation() { return simulation; } public boolean hasNoCPU() { return noCpu; }
    public int getSelectedCpu() { return cpuTable.selectedCpuSerial; }
    public Object getItemToCraft() {
        targetReads++;
        if (serializedItemToCraft.isEmpty() || corruptTarget) throw new RuntimeException("Native target buffer cannot be decoded");
        return requested;
    }
    public boolean cpuMatches(Object cpu) { return true; }
}
