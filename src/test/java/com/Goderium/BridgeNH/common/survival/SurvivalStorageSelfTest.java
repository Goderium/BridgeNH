package com.Goderium.BridgeNH.common.survival;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldSettings.GameType;

public final class SurvivalStorageSelfTest {
    public static int run() {
        int actorChecks = actorEligibility();
        NBTTagCompound original=new NBTTagCompound();
        JsonObject receipt=new JsonObject();
        receipt.addProperty("actorId","actor"); receipt.addProperty("folder","world");
        receipt.addProperty("dimension",7); receipt.addProperty("intentId","a");
        receipt.addProperty("reason","reconcile_required");
        SurvivalStorage.append(original,"a",receipt);
        NBTTagCompound afterReservation=(NBTTagCompound)original.copy();
        receipt.addProperty("reason","transferred"); receipt.addProperty("moved",3);
        SurvivalStorage.append(original,"a",receipt);
        NBTTagCompound afterSave=(NBTTagCompound)original.copy();
        assertTrue("reconcile_required".equals(SurvivalStorage.stored(afterReservation,"a","actor","world",7).get("reason").getAsString()));
        assertTrue(SurvivalStorage.stored(afterSave,"a","actor","world",7).equals(receipt));
        assertTrue(SurvivalStorage.stored(afterSave,"a","neighbor","world",7)==null);
        assertTrue(SurvivalStorage.stored(afterSave,"a","actor","other",7)==null);
        assertTrue(SurvivalStorage.stored(afterSave,"a","actor","world",0)==null);
        for(int i=0;i<32;i++) {
            String id="i"+i;
            receipt.addProperty("intentId",id);
            SurvivalStorage.append(afterSave,id,receipt);
        }
        assertTrue(SurvivalStorage.stored(afterSave,"a","actor","world",7)==null);
        assertTrue(SurvivalStorage.stored(afterSave,"i31","actor","world",7).equals(receipt));
        assertTrue(StorageItemNbt.roundTrips("{b:{z:2s,y:1b},a:[0:\"x,y\",1:\"b\"]}","{a:[0:\"x,y\",1:\"b\"],b:{y:1b,z:2s}}"));
        assertTrue(!StorageItemNbt.roundTrips("{a:1b}","{a:1}"));
        assertTrue(!StorageItemNbt.roundTrips("{a:1,a:2}","{a:2}"));
        assertTrue(!StorageItemNbt.roundTrips("{list:[0:1,1:2]}","{list:[0:2,1:1]}"));
        assertTrue(!StorageItemNbt.roundTrips("{a:[4 bytes]}","{a:\"[4 bytes]\"}"));
        return 12 + actorChecks;
    }

    private static int actorEligibility() {
        PlayerCapabilities caps = new PlayerCapabilities();
        caps.disableDamage = true;
        if (!SurvivalStorage.eligibleActor(false, 20, caps, GameType.SURVIVAL))
            throw new AssertionError("A living survival player with spawn protection must be eligible for storage");
        assertTrue(caps.disableDamage);
        assertTrue(SurvivalStorage.eligibleActor(false, 20, caps, GameType.ADVENTURE));
        assertTrue(!SurvivalStorage.eligibleActor(true, 20, caps, GameType.SURVIVAL));
        assertTrue(!SurvivalStorage.eligibleActor(false, 0, caps, GameType.SURVIVAL));
        caps.disableDamage = false;
        assertTrue(SurvivalStorage.eligibleActor(false, 20, caps, GameType.SURVIVAL));
        assertTrue(!SurvivalStorage.eligibleActor(false, 20, caps, GameType.CREATIVE));
        assertTrue(!SurvivalStorage.eligibleActor(false, 20, caps, null));
        assertTrue(!SurvivalStorage.eligibleActor(false, 20, caps, GameType.NOT_SET));
        caps.isCreativeMode = true;
        assertTrue(!SurvivalStorage.eligibleActor(false, 20, caps, GameType.SURVIVAL));
        return 10;
    }
    private static void assertTrue(boolean value) {
        if(!value) throw new AssertionError("Survival storage receipt or item identity mismatch");
    }
}
