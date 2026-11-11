package gregtech.api.interfaces;

/** Fixed public configuration API fixture; no private machine fields or GUI offsets. */
public interface IConfigurationCircuitSupport {
    boolean allowSelectCircuit();
    int getCircuitSlot();
}
