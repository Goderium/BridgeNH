package mcp.mobius.waila.api;

import java.util.List;
import net.minecraft.item.ItemStack;

public interface IWailaDataProvider {
    List<String> getWailaBody(ItemStack stack, List<String> lines, IWailaDataAccessor accessor, IWailaConfigHandler config);
}
