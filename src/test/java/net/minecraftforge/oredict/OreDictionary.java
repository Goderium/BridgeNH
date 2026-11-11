package net.minecraftforge.oredict;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Headless ore identity/membership fixture; no vanilla crafting initialization or full catalog expansion. */
public final class OreDictionary {
    public static final int WILDCARD_VALUE = 32767;
    private static final Map<String, Integer> names = new LinkedHashMap<String, Integer>();
    private static final Map<Item, Map<Integer, Set<Integer>>> memberships = new IdentityHashMap<Item, Map<Integer, Set<Integer>>>();

    public static int getOreID(String name) {
        Integer id = names.get(name);
        if (id == null) { id = names.size(); names.put(name, id); }
        return id;
    }

    public static void registerOre(String name, ItemStack stack) {
        Map<Integer, Set<Integer>> byMetadata = memberships.get(stack.getItem());
        if (byMetadata == null) { byMetadata = new LinkedHashMap<Integer, Set<Integer>>(); memberships.put(stack.getItem(), byMetadata); }
        Set<Integer> ids = byMetadata.get(stack.getMetadata());
        if (ids == null) { ids = new LinkedHashSet<Integer>(); byMetadata.put(stack.getMetadata(), ids); }
        ids.add(getOreID(name));
    }

    public static int[] getOreIDs(ItemStack stack) {
        Set<Integer> ids = new LinkedHashSet<Integer>();
        Map<Integer, Set<Integer>> byMetadata = stack == null ? null : memberships.get(stack.getItem());
        if (byMetadata != null) {
            Set<Integer> exact = byMetadata.get(stack.getMetadata()), wildcard = byMetadata.get(WILDCARD_VALUE);
            if (exact != null) ids.addAll(exact);
            if (wildcard != null) ids.addAll(wildcard);
        }
        int[] result = new int[ids.size()]; int index = 0;
        for (Integer id : ids) result[index++] = id;
        return result;
    }

    public static ArrayList<ItemStack> getOres(String name) { throw new AssertionError("Full ore catalog expansion is forbidden"); }
}
