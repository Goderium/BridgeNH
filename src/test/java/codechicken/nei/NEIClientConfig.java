package codechicken.nei;
import net.minecraft.item.ItemStack;
public class NEIClientConfig {
    public static boolean allowed = true;
    public static boolean canCheatItem(ItemStack stack) { return allowed; }
}
