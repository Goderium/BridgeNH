package appeng.container.sync.deltas;

import appeng.api.storage.data.IAEStack;

/** Native constructor copies the stack before synchronization; test-only fixture. */
public final class AEStackInventoryDelta {
    public final int slotIndex;
    public final IAEStack stack;
    public AEStackInventoryDelta(int slotIndex, IAEStack stack) {
        this.slotIndex = slotIndex;
        this.stack = stack == null ? null : (IAEStack) stack.copy();
    }
}
