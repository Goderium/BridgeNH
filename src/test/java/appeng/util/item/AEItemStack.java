package appeng.util.item;

import com.Goderium.BridgeNH.common.ae.AeGuiFixtures;
import net.minecraft.item.ItemStack;

/** Public native ItemStack factory fallback; copies actual identity/count for the fixture. */
public final class AEItemStack {
    public static Object create(ItemStack item) {
        return new AeGuiFixtures.Stack(AEItemStackType.ITEM_STACK_TYPE, item.copy(),
            item.getMetadata() + ":" + String.valueOf(item.getTagCompound()), item.stackSize);
    }
}
