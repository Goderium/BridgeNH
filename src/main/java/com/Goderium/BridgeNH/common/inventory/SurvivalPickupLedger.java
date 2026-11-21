package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.common.survival.SurvivalStorage;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

/** Own-player pickup evidence, acquired on the server thread, without altering normal pickup. */
public final class SurvivalPickupLedger {
    public static final String OPERATION = "survival.gather_observe";
    private static final SurvivalPickupLedger INSTANCE = new SurvivalPickupLedger();
    private static final int HISTORY = 128;
    private static final long IDLE_NANOS = 300_000_000_000L;
    private final WeakHashMap<EntityPlayerMP, Ledger> players = new WeakHashMap<>();

    public static void install() {
        MinecraftForge.EVENT_BUS.register(INSTANCE);
        FMLCommonHandler.instance().bus().register(INSTANCE);
    }

    private static final class Ledger {
        final String epoch = UUID.randomUUID().toString();
        final String owner;
        final ArrayDeque<Pickup> events = new ArrayDeque<>();
        int serial, lostThrough;
        long lastRead = System.nanoTime();
        Pickup before;
        Ledger(String owner) { this.owner = owner; }
    }

    private static final class Pickup {
        final int id, dimension, available;
        final long inventory;
        final double x, y, z;
        final ItemStack item;
        int amount, serial;
        Pickup(EntityPlayerMP p, EntityItem drop) {
            id = drop.getEntityId(); dimension = p.dimension;
            x = drop.posX; y = drop.posY; z = drop.posZ;
            item = drop.getEntityItem().copy();
            available = item.stackSize;
            item.stackSize = 1;
            inventory = total(p, item);
        }
    }

    static boolean same(ItemStack a, ItemStack b) {
        return a != null && b != null && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static long total(EntityPlayerMP player, ItemStack target) {
        long sum = 0;
        for (ItemStack stack : player.inventory.mainInventory) if (same(stack, target)) sum += stack.stackSize;
        return sum;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void beforePickup(EntityItemPickupEvent event) {
        if (!(event.entityPlayer instanceof EntityPlayerMP)) return;
        EntityPlayerMP actor = (EntityPlayerMP)event.entityPlayer;
        Ledger ledger = players.get(actor);
        if (ledger == null) return;
        if (System.nanoTime() - ledger.lastRead >= IDLE_NANOS) { players.remove(actor); return; }
        ItemStack stack = event.item.getEntityItem();
        ledger.before = stack != null && stack.stackSize > 0 && (!stack.hasTagCompound() || stack.getTagCompound().toString().length() <= 4096)
                ? new Pickup(actor, event.item) : null;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void afterPickup(PlayerEvent.ItemPickupEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP actor = (EntityPlayerMP)event.player;
        Ledger ledger = players.get(actor);
        if (ledger == null) return;
        Pickup before = ledger.before;
        ledger.before = null;
        int serial = ++ledger.serial;
        if (before == null || before.id != event.pickedUp.getEntityId() || before.dimension != actor.dimension) {
            ledger.lostThrough = serial; return;
        }
        // The FML post event fires after inventory insertion and before vanilla resets a dead stack's size.
        ItemStack remaining = event.pickedUp.getEntityItem();
        int moved = same(before.item, remaining) ? transferred(before.available, remaining.stackSize,
                before.inventory, total(actor, before.item)) : -1;
        if (moved < 0) { ledger.lostThrough = serial; return; }
        if (moved == 0) return;
        before.amount = moved; before.serial = serial;
        ledger.events.addLast(before);
        if (ledger.events.size() > HISTORY) ledger.lostThrough = Math.max(ledger.lostThrough, ledger.events.removeFirst().serial);
    }

    public static JsonObject observe(EntityPlayerMP actor, JsonObject args) {
        RemoteReadProtocol.keys(args, "expectedSessionId", "expectedConnectionId", "expectedWorld", "dimension", "item", "bounds", "epoch", "after", "observerId");
        String owner = JsonData.string(args, "observerId");
        if (!owner.matches("[0-9a-f]{32}")) throw new BridgeException("invalid_args", "Invalid pickup observer ID.");
        if (actor.isDead || actor.capabilities.isCreativeMode || actor.dimension != JsonData.integer(args, "dimension", -2147483648, 2147483647))
            throw new BridgeException("stale_world", "Pickup observation requires the living survival actor in the expected dimension.");
        ItemStack identity = SurvivalStorage.identity(args.getAsJsonObject("item"));
        JsonObject region = args.getAsJsonObject("bounds");
        RemoteReadProtocol.keys(region, "minX", "maxX", "minY", "maxY", "minZ", "maxZ");
        int x = JsonData.integer(region, "minX", -30000000, 29999999), y = JsonData.integer(region, "minY", 0, 255), z = JsonData.integer(region, "minZ", -30000000, 29999999);
        int endX = JsonData.integer(region, "maxX", x, Math.min(29999999, x+31)), endY = JsonData.integer(region, "maxY", y, Math.min(255, y+31)), endZ = JsonData.integer(region, "maxZ", z, Math.min(29999999, z+31));
        boolean resume = args.has("after") || args.has("epoch");
        Ledger ledger = INSTANCE.players.get(actor);
        if (ledger == null || System.nanoTime() - ledger.lastRead >= IDLE_NANOS || ledger.serial == Integer.MAX_VALUE
                || !ledger.owner.equals(owner)) {
            if (resume) throw new BridgeException("pickup_epoch_changed", "The original player's pickup observer is no longer current.");
            ledger = new Ledger(owner); INSTANCE.players.put(actor, ledger);
        }
        ledger.lastRead = System.nanoTime();
        int after = resume ? JsonData.integer(args, "after", 0, Integer.MAX_VALUE) : ledger.serial;
        boolean complete = !resume || ledger.epoch.equals(JsonData.string(args, "epoch")) && after >= ledger.lostThrough && after <= ledger.serial;
        int freeSlots = 0;
        long amount = 0, quantity = 0, stackSpace = 0;
        for (ItemStack stack : actor.inventory.mainInventory) {
            if (stack == null) freeSlots++;
            else if (same(stack, identity)) {
                quantity += stack.stackSize;
                stackSpace += Math.max(0, Math.min(stack.getMaxStackSize(), actor.inventory.getInventoryStackLimit()) - stack.stackSize);
            }
        }
        for (Pickup event : ledger.events) {
            if (event.serial > after && event.dimension == actor.dimension && event.x >= x && event.x < endX+1
                    && event.y >= y && event.y < endY+1 && event.z >= z && event.z < endZ+1 && same(event.item, identity))
                amount += event.amount;
        }
        JsonObject data = new JsonObject();
        data.addProperty("source", "server_player_pickups"); data.addProperty("playerUuid", actor.getUniqueID().toString());
        data.addProperty("dimension", actor.dimension); data.addProperty("epoch", ledger.epoch); data.addProperty("cursor", ledger.serial);
        data.addProperty("complete", complete); data.addProperty("inventoryCount", quantity); data.addProperty("freeSlots", freeSlots);
        data.addProperty("stackSpace", stackSpace); if (complete) data.addProperty("pickedUp", amount);
        JsonArray drops = new JsonArray();
        double minX=Math.max(x,actor.posX-16), minY=Math.max(y,actor.posY-16), minZ=Math.max(z,actor.posZ-16);
        double maxX=Math.min(endX+1,actor.posX+16), maxY=Math.min(endY+1,actor.posY+16), maxZ=Math.min(endZ+1,actor.posZ+16);
        boolean truncated = false;
        if(minX<maxX && minY<maxY && minZ<maxZ) {
            List<?> nearby = actor.worldObj.getEntitiesWithinAABB(EntityItem.class, AxisAlignedBB.getBoundingBox(minX,minY,minZ,maxX,maxY,maxZ));
            for (Object candidate : nearby) {
                EntityItem drop = (EntityItem)candidate;
                if (drop.isDead || !same(drop.getEntityItem(), identity) || drop.posX < minX || drop.posX >= maxX
                        || drop.posY < minY || drop.posY >= maxY || drop.posZ < minZ || drop.posZ >= maxZ) continue;
                if (drops.size() == 32) { truncated = true; break; }
                JsonObject row = new JsonObject(); row.addProperty("entityId",drop.getEntityId());
                row.addProperty("x",drop.posX); row.addProperty("y",drop.posY); row.addProperty("z",drop.posZ);
                row.addProperty("count",drop.getEntityItem().stackSize); drops.add(row);
            }
        }
        data.add("drops", drops); data.addProperty("dropsTruncated", truncated);
        data.addProperty("completeRegion", minX==x && minY==y && minZ==z && maxX==endX+1 && maxY==endY+1 && maxZ==endZ+1
                && actor.worldObj.checkChunksExist(x,y,z,endX,endY,endZ) && !truncated);
        return data;
    }

    static int transferred(int available, int remaining, long inventoryBefore, long inventoryAfter) {
        if (remaining < 0 || remaining > available || inventoryBefore < 0 || inventoryAfter < inventoryBefore) return -1;
        int moved = available - remaining;
        return inventoryAfter - inventoryBefore == moved ? moved : -1;
    }
}
