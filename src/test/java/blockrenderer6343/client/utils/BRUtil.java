package blockrenderer6343.client.utils;

import com.gtnewhorizon.gtnhlib.blockpos.BlockPos;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.item.ItemStack;

public class BRUtil {
    public static int fixtureScans;
    public static Object fixtureController;
    public static ItemStack fixtureTrigger;
    public static List<List<ItemStack>> scanCandidates(Object controller, IStructureElement element, ItemStack trigger, BlockPos selected) {
        fixtureScans++; fixtureController = controller; fixtureTrigger = trigger;
        List<List<ItemStack>> result = new ArrayList<>();
        if (element != null) { result.add(Collections.singletonList(trigger)); result.add(Collections.singletonList(trigger)); }
        return result;
    }
}
