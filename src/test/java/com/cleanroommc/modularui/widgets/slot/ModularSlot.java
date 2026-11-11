package com.cleanroommc.modularui.widgets.slot;

import com.cleanroommc.modularui.utils.item.SlotItemHandler;
import java.util.function.Predicate;
import net.minecraft.item.ItemStack;

public class ModularSlot extends SlotItemHandler {
    public boolean phantom, canPut = true, canTake = true;
    public Predicate<ItemStack> filter = item -> { throw new AssertionError("Unknown native filter must never be invoked by display inspection"); };
    public ModularSlot(Object handler, int index) { super(handler, index); }
    public boolean isPhantom() { return phantom; }
    public boolean isCanPut() { return canPut; }
    public boolean isCanTake() { return canTake; }
    public Predicate<ItemStack> getFilter() { return filter; }
    @Override public boolean isItemValid(ItemStack item) { throw new AssertionError("Native slot acceptance may write inventory and must not be called by display inspection"); }
}
