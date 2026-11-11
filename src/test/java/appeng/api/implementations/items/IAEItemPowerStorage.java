package appeng.api.implementations.items;
public interface IAEItemPowerStorage {
    double getAECurrentPower(net.minecraft.item.ItemStack item);
    double extractAEPower(net.minecraft.item.ItemStack item, double amount);
}
