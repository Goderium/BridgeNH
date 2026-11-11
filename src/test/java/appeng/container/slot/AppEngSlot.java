package appeng.container.slot;

import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Public slot API fixture; no native window, click or packet is emitted. */
public class AppEngSlot extends Slot {
    public boolean enabled = true, accepts = true;
    public AppEngSlot() { super(new InventoryBasic("AE slot fixture", true, 1), 0, 0, 0); }
    public boolean isEnabled() { return enabled; }
    @Override public boolean isItemValid(ItemStack item) { return accepts && item.hasTagCompound() && item.getTagCompound().getBoolean("accepted"); }
}
