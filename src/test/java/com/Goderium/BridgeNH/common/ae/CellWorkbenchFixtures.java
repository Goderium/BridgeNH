package com.Goderium.BridgeNH.common.ae;

import appeng.container.sync.deltas.AEStackInventoryDelta;
import net.minecraft.item.ItemStack;

/** State fixtures exercise the actual adapters, while native networking remains mocked. */
public final class CellWorkbenchFixtures {
    public static class Inventory extends AeGuiFixtures.Inventory {
        public Inventory(int size) { super(size); }
        public String getStorageName() { return "CONFIG"; }
    }
    public static class Sync {
        public final Inventory inventory;
        public final java.util.List<AEStackInventoryDelta> pending = new java.util.ArrayList<AEStackInventoryDelta>();
        public int writes;
        public AEStackInventoryDelta last;
        public Sync(int size) { inventory = new Inventory(size); }
        public Inventory get() { return inventory; }
        public void applyAndQueueDelta(AEStackInventoryDelta delta) {
            writes++; last = delta; inventory.slots[delta.slotIndex] = delta.stack; pending.add(delta);
        }
    }
    public static class SyncManager {
        public final Sync sync;
        public final Inventory serverInventory;
        public int flushes, sentDeltas;
        public boolean failFlush;
        public SyncManager(Sync sync) { this.sync = sync; serverInventory = new Inventory(sync.inventory.slots.length); }
        public void flushSync() {
            flushes++;
            if (failFlush) throw new RuntimeException("Native flush failure fixture");
            for (AEStackInventoryDelta delta : sync.pending) { serverInventory.slots[delta.slotIndex] = delta.stack; sentDeltas++; }
            sync.pending.clear();
        }
    }
    public static class Type extends AeGuiFixtures.Type {
        public boolean directConversion, containerConversion, mutateHand;
        public long containerAmount = 64000;
        public Type(String id) { super(id); }
        public Object convertStackFromItem(ItemStack hand) {
            return directConversion ? convert(hand, 1) : null;
        }
        public Object getStackFromContainerItem(ItemStack hand) {
            return containerConversion ? convert(hand, containerAmount) : null;
        }
        private Object convert(ItemStack hand, long amount) {
            String identity = hand.getMetadata() + ":" + String.valueOf(hand.getTagCompound());
            ItemStack copied = hand.copy();
            if (mutateHand) { hand.stackSize = 99; if (hand.hasTagCompound()) hand.getTagCompound().setString("fixture", "conversion changed copy"); }
            return new AeGuiFixtures.Stack(this, copied, identity, amount);
        }
    }
}
