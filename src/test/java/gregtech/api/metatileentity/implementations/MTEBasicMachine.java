package gregtech.api.metatileentity.implementations;

import gregtech.api.interfaces.IConfigurationCircuitSupport;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

/** Public input/output inventory boundary fixture; deliberately not a fixed GUI offset. */
public class MTEBasicMachine implements IConfigurationCircuitSupport {
    public int start, end;
    public int special, circuit, chargeStart, dischargeStart, chargeCount = 1, dischargeCount = 1;
    public final ItemStack[] mOutputItems = new ItemStack[2];
    public IFluidTank inputTank = new FluidTank(32000), outputTank = new FluidTank(64000);
    public MTEBasicMachine(int start, int end) {
        this.start = start; this.end = end; special = start - 2; circuit = start - 1;
        chargeStart = start - 4; dischargeStart = chargeStart;
    }
    public int getInputSlot() { return start; }
    public int getOutputSlot() { return end; }
    public IFluidTank getFluidTank() { return inputTank; }
    public IFluidTank getFluidOutputTank() { return outputTank; }
    public boolean isIOSlot(int index) { return index >= start && index < end; }
    public boolean allowSelectCircuit() { return true; }
    public int getCircuitSlot() { return circuit; }
    public int getSpecialSlotIndex() { return special; }
    public int rechargerSlotStartIndex() { return chargeStart; }
    public int rechargerSlotCount() { return chargeCount; }
    public int dechargerSlotStartIndex() { return dischargeStart; }
    public int dechargerSlotCount() { return dischargeCount; }
}
