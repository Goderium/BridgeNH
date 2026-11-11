package net.minecraft.init;

import net.minecraft.item.Item;
import net.minecraft.item.ItemPotion;

/** Headless initialization fixtures only; native registry class bytes remain unchanged. */
public final class Items {
    public static final Item bucket = new Item(), glass_bottle = new Item(), water_bucket = new Item(), lava_bucket = new Item();
    public static final ItemPotion potionitem = new ItemPotion();
    public static final Item stick = new Item();
    public static final net.minecraft.item.ItemMap filled_map = null;
}
