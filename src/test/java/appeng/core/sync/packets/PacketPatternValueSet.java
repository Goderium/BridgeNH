package appeng.core.sync.packets;
import appeng.api.storage.StorageName;
public class PacketPatternValueSet {
    public final Object stack; public final StorageName side; public final int slot; public final boolean onlyUpdate;
    public PacketPatternValueSet(Object stack, StorageName side, int slot, boolean onlyUpdate) { this.stack = stack; this.side = side; this.slot = slot; this.onlyUpdate = onlyUpdate; }
}
