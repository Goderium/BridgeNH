package com.cleanroommc.modularui.widgets.slot;

import com.cleanroommc.modularui.widget.Widget;
import net.minecraft.inventory.Slot;

/** Public widget-to-native-slot fixture. Predicate execution remains forbidden in ModularSlot. */
public class ItemSlot extends Widget {
    public ModularSlot slot;
    public ItemSlot(ModularSlot slot) { this.slot = slot; }
    public ModularSlot getSlot() { return slot; }
    public Slot getVanillaSlot() { return slot; }
}
