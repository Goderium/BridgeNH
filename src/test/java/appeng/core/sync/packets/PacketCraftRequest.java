package appeng.core.sync.packets;
import appeng.api.config.CraftingMode;
public class PacketCraftRequest {
    public final long amount; public final boolean shift, ctrl, lite; public final CraftingMode mode;
    public PacketCraftRequest(long amount, boolean shift, boolean ctrl, CraftingMode mode, boolean lite) { this.amount = amount; this.shift = shift; this.ctrl = ctrl; this.mode = mode; this.lite = lite; }
}
