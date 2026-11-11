package com.gtnewhorizon.structurelib.structure;

import java.util.List;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Public planning API fixture; all state-changing methods trip a test immediately. */
public interface IStructureElement {
    default boolean isNavigating() { return false; }
    default boolean resetA() { return false; }
    default boolean resetB() { return false; }
    default boolean resetC() { return false; }
    default int getStepA() { return 1; }
    default int getStepB() { return 0; }
    default int getStepC() { return 0; }
    List<String> getDescription(Object context);
    default BlocksToPlace getBlocksToPlace(Object context, World world, int x, int y, int z,
                                          ItemStack trigger, AutoPlaceEnvironment environment) { return null; }
    default boolean check(Object context, World world, int x, int y, int z) { throw new AssertionError("checking is forbidden"); }
    default void spawnHint() { throw new AssertionError("hinting is forbidden"); }
    default void placeBlock() { throw new AssertionError("placement is forbidden"); }
    final class BlocksToPlace {
        private final Iterable<ItemStack> stacks;
        public BlocksToPlace(Iterable<ItemStack> stacks) { this.stacks = stacks; }
        public Iterable<ItemStack> getStacks() { return stacks; }
    }
}
