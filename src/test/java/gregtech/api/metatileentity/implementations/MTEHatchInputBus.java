package gregtech.api.metatileentity.implementations;

public class MTEHatchInputBus {
    public int circuit;
    public MTEHatchInputBus(int circuit) { this.circuit = circuit; }
    public int getCircuitSlot() { return circuit; }
    public boolean isValidSlot(int index) { return index != circuit; }
}
