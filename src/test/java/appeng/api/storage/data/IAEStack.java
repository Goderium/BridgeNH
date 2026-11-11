package appeng.api.storage.data;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Exact getter/mutator surface fixture, excluded from production JAR. */
public interface IAEStack {
    long getStackSize(); Object setStackSize(long value);
    long getCountRequestable(); Object setCountRequestable(long value);
    Object setCountRequestableCrafts(long value); boolean isCraftable(); Object setCraftable(boolean value);
    Object copy(); Object getStackType(); String getDisplayName(); boolean isItem();
    ItemStack getItemStackForNEI(); int getAmountPerUnit(); void writeToNBTGeneric(NBTTagCompound tag);
    default boolean isFluid() { return !isItem(); }
    default Object getChannel() { return null; }
}
