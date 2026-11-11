package gregtech.api.util;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.Block;

/** Mapping getter fixture only; never installed or bundled as a GregTech API. */
public final class GlassTier {
    private static final Map<Block, Map<Integer, int[]>> mappings = new IdentityHashMap<Block, Map<Integer, int[]>>();
    public static int calls;
    public static void mapping(Block block, int meta, int channel, int tier) {
        Map<Integer, int[]> variants = mappings.get(block);
        if (variants == null) { variants = new HashMap<Integer, int[]>(); mappings.put(block, variants); }
        variants.put(meta, new int[] {channel, tier});
    }
    public static int getGlassChannelValue(Block block, int meta) { calls++; return value(block, meta, 0); }
    public static Integer getGlassBlockTier(Block block, int meta) { calls++; Map<Integer, int[]> variants = mappings.get(block); return variants == null || !variants.containsKey(meta) ? null : variants.get(meta)[1]; }
    private static int value(Block block, int meta, int index) { Map<Integer, int[]> variants = mappings.get(block); return variants == null || !variants.containsKey(meta) ? 0 : variants.get(meta)[index]; }
    public static Object getGlassList() { throw new AssertionError("Glass mapping read cannot initialize native caches"); }
    public static void clearFixture() { mappings.clear(); calls = 0; }
}
