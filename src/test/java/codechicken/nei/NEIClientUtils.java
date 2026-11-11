package codechicken.nei;
import net.minecraft.item.ItemStack;
public class NEIClientUtils {
    public static int acquisitions, button, mode;
    public static ItemStack requested;
    public static void cheatItem(ItemStack stack, int mouseButton, int cheatMode) {
        acquisitions++; requested = stack.copy(); button = mouseButton; mode = cheatMode;
    }
}
