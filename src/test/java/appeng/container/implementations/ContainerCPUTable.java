package appeng.container.implementations;
import java.util.ArrayList;
import java.util.List;
public class ContainerCPUTable {
    public int selectedCpuSerial = 7; public final List<CraftingCPUStatus> cpus = new ArrayList<CraftingCPUStatus>();
    public List<CraftingCPUStatus> getCPUs() { return cpus; }
    public CraftingCPUStatus getSelectedCPU() { for (CraftingCPUStatus cpu : cpus) if (cpu.serial == selectedCpuSerial) return cpu; return null; }
}
