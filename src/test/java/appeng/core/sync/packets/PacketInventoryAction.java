package appeng.core.sync.packets;

import appeng.helpers.InventoryAction;

/** Public normal-packet constructor contract, without a network or crafting side effect. */
public class PacketInventoryAction {
    public final InventoryAction action;
    public final int slot;
    public final long id;
    public PacketInventoryAction(InventoryAction action, int slot, long id) {
        this.action = action; this.slot = slot; this.id = id;
    }
}
