package gregtech.api.interfaces;

/** Fixed public branches; selection methods are tripwires in regression fixtures. */
public interface ISecondaryDescribable {
    String[] getPrimaryDescription();
    String[] getSecondaryDescription();
    boolean isDisplaySecondaryDescription();
}
