package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class SurvivalPickupSelfTest {
    public static int run() {
        check(SurvivalPickupLedger.transferred(12, 9, 27, 30) == 3, "A partial stack credits exactly what entered the player.");
        check(SurvivalPickupLedger.transferred(12, 0, 27, 39) == 12, "A full pickup credits the whole stack.");
        check(SurvivalPickupLedger.transferred(12, 12, 27, 27) == 0, "A Forge allow event alone credits nothing.");
        check(SurvivalPickupLedger.transferred(12, 1, 27, 39) < 0, "A simultaneous unrelated gain invalidates evidence.");
        check(SurvivalPickupLedger.transferred(12, 0, 27, 27) < 0, "Deleted items are not pickups.");
        check(SurvivalPickupLedger.transferred(12, 13, 27, 26) < 0, "Reverse transfers cannot become pickups.");
        check(SurvivalPickupLedger.transferred(12, 9, 3_000_000_000L, 3_000_000_003L) == 3, "Long totals remain exact.");
        Item item = new Item();
        ItemStack a = new ItemStack(item, 1, 3), b = new ItemStack(item, 7, 3);
        NBTTagCompound first = new NBTTagCompound(), reverse = new NBTTagCompound();
        first.setInteger("value", 2); first.setString("name", "fixture");
        reverse.setString("name", "fixture"); reverse.setInteger("value", 2);
        a.setTagCompound(first); b.setTagCompound(reverse);
        check(SurvivalPickupLedger.same(a,b), "Compound ordering and stack quantity do not distinguish identities.");
        reverse.setByte("value", (byte)2);
        check(!SurvivalPickupLedger.same(a,b), "A distinct native numeric tag type must not match.");
        b.setTagCompound(null);
        check(!SurvivalPickupLedger.same(a,b), "An absent tag is an exact identity.");
        a.setTagCompound(null);
        check(!SurvivalPickupLedger.same(a, new ItemStack(item, 1, 4)), "Item damage is exact.");
        check(RequestRegistry.readOnly(SurvivalPickupLedger.OPERATION), "Own-pickup evidence is read-only.");
        RemoteReadProtocol.permit(SurvivalPickupLedger.OPERATION, false);
        return 12;
    }

    private static void check(boolean ok, String meaning) { if (!ok) throw new AssertionError(meaning); }
}
