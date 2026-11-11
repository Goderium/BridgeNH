package appeng.api.util;

/** Shape of the optional AE callback; Resource fixtures stand in for IAEStack. */
public interface CraftCompleteListener extends java.io.Serializable {
    void apply(Object output, long amount, long elapsedTime);
}
