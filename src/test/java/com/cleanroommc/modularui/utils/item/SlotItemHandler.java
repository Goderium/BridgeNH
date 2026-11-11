package com.cleanroommc.modularui.utils.item;

import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;

/** Public handler identity fixture matching the proven constructor-index contract. */
public class SlotItemHandler extends Slot {
    private final Object handler;
    public SlotItemHandler(Object handler, int index) { super(new InventoryBasic("shadow", false, 256), index, 0, 0); this.handler = handler; }
    public Object getItemHandler() { return handler; }
}
