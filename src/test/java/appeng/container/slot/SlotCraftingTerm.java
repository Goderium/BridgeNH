package appeng.container.slot;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Real Minecraft Slot, with AE's explicit refusal of vanilla output pickup. */
public class SlotCraftingTerm extends Slot {
    public SlotCraftingTerm(ItemStack output) {
        super(new InventoryBasic("AE output fixture", false, 1), 0, 0, 0);
        putStack(output);
    }
    @Override public boolean canTakeStack(EntityPlayer player) { return false; }
}
