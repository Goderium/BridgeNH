package gregtech.common.tileentities.machines.outputme.base;

/** Public optional-interface fixture; no runtime GT linkage. */
public class MTEHatchOutputMEBase {
    public interface Environment { Object getProvider(); default Object getChannel() { return null; } }
    public long getCachedAmount(appeng.api.storage.data.IAEStack key) { return 0; }
}
