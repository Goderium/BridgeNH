package net.minecraftforge.fluids;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.item.ItemStack;

/** Mock fixed-container public reads; avoids Forge/vanilla registration during plain-JVM tests. */
public final class FluidContainerRegistry {
    private static final List<Data> data = new ArrayList<Data>();
    public static int callsForTest, capacityOverrideForTest = Integer.MIN_VALUE;
    public static boolean failForTest;
    public static void resetForTest() { data.clear(); callsForTest = 0; capacityOverrideForTest = Integer.MIN_VALUE; failForTest = false; }
    public static boolean registerFluidContainer(FluidStack fluid, ItemStack filled, ItemStack empty) {
        data.add(new Data(fluid.copy(), filled.copy(), empty == null ? null : empty.copy())); return true;
    }
    public static FluidStack getFluidForFilledItem(ItemStack stack) {
        callsForTest++; if (failForTest) throw new IllegalStateException("fixture registry unavailable");
        for (Data entry : data) if (same(entry.filled, stack)) return entry.fluid.copy();
        return null;
    }
    public static boolean isEmptyContainer(ItemStack stack) {
        callsForTest++;
        for (Data entry : data) if (same(entry.empty, stack)) return true;
        return false;
    }
    public static int getContainerCapacity(ItemStack stack) {
        return getContainerCapacity(null, stack);
    }
    public static int getContainerCapacity(FluidStack fluid, ItemStack stack) {
        callsForTest++;
        if (capacityOverrideForTest != Integer.MIN_VALUE) return capacityOverrideForTest;
        for (Data entry : data) if (same(entry.filled, stack)) return entry.fluid.amount;
        if (fluid != null) for (Data entry : data)
            if (same(entry.empty, stack) && fluid.getFluid() == entry.fluid.getFluid()) return entry.fluid.amount;
        return 0;
    }
    private static boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null && a.getItem() == b.getItem() && a.getMetadata() == b.getMetadata();
    }
    private static final class Data {
        final FluidStack fluid; final ItemStack filled, empty;
        Data(FluidStack fluid, ItemStack filled, ItemStack empty) { this.fluid = fluid; this.filled = filled; this.empty = empty; }
    }
}
