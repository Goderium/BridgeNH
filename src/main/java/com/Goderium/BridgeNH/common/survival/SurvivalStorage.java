package com.Goderium.BridgeNH.common.survival;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.common.util.ForgeDirection;

/** Runs on the actual player's server thread. Open through normal block interaction; count only the bound window. */
public final class SurvivalStorage {
    public static final String COUNT="survival.storage_count", OPEN="survival.storage_open",
        MOVE="survival.storage_move", RECEIPT="survival.storage_receipt";
    private static final Map<EntityPlayerMP, Ledger> ACTORS=new WeakHashMap<EntityPlayerMP, Ledger>();
    private static final String SAVED="BridgeNHStorageReceiptsV1";

    public static boolean handles(String op) { return COUNT.equals(op)||OPEN.equals(op)||MOVE.equals(op)||RECEIPT.equals(op); }
    public static boolean write(String op) { return MOVE.equals(op)||OPEN.equals(op); }

    static void need(boolean yes,String code,String message) { if (!yes) throw new BridgeException(code,message); }
    private static JsonObject reason(String code) { JsonObject r=new JsonObject(); r.addProperty("reason",code); return r; }

    static boolean eligibleActor(boolean dead, float health, PlayerCapabilities capabilities, WorldSettings.GameType gameType) {
        // Spawn protection can set disableDamage on an ordinary survival player.
        return !dead && health > 0 && !capabilities.isCreativeMode
                && (gameType == WorldSettings.GameType.SURVIVAL || gameType == WorldSettings.GameType.ADVENTURE);
    }
    private static final class Ledger {
        String observation;
        JsonObject request;
        long playerCount, endpointCount;
        WeakReference<Container> window=new WeakReference<Container>(null);
        WeakReference<TileEntity> tile=new WeakReference<TileEntity>(null);
        String key;
    }

    // Kept with the server's normal player save, including normal logout/save-all. No disk scan on ticks.
    private static JsonObject stored(EntityPlayerMP player,String id) {
        return stored(player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG),id,
            player.getUniqueID().toString(),MinecraftServer.getServer().getFolderName(),player.dimension);
    }
    static JsonObject stored(NBTTagCompound persistent,String id,String actor,String folder,int dimension) {
        NBTTagList rows=persistent.getTagList(SAVED,8);
        for(int i=rows.tagCount()-1;i>=0;i--) {
            JsonObject row=new JsonParser().parse(rows.getStringTagAt(i)).getAsJsonObject();
            if(id.equals(row.get("intentId").getAsString()) && actor.equals(row.get("actorId").getAsString())
                && dimension==row.get("dimension").getAsInt() && folder.equals(row.get("folder").getAsString()))
                return row;
        }
        return null;
    }
    private static void save(EntityPlayerMP player,String id,JsonObject receipt) {
        receipt.addProperty("intentId",id); receipt.addProperty("actorId",player.getUniqueID().toString());
        receipt.addProperty("dimension",player.dimension); receipt.addProperty("folder",MinecraftServer.getServer().getFolderName());
        NBTTagCompound persistent=player.getEntityData().getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        append(persistent,id,receipt);
        player.getEntityData().setTag(EntityPlayer.PERSISTED_NBT_TAG,persistent);
    }
    static void append(NBTTagCompound persistent,String id,JsonObject receipt) {
        NBTTagList old=persistent.getTagList(SAVED,8), next=new NBTTagList();
        String now=receipt.toString();
        java.util.ArrayList<String> retained=new java.util.ArrayList<String>();
        int chars=now.length();
        for(int i=old.tagCount()-1;i>=0 && retained.size()<31;i--) {
            String raw=old.getStringTagAt(i);
            if(id.equals(new JsonParser().parse(raw).getAsJsonObject().get("intentId").getAsString())) continue;
            if(chars+raw.length()>65536) break;
            retained.add(raw); chars+=raw.length();
        }
        for(int i=retained.size()-1;i>=0;i--) next.appendTag(new NBTTagString(retained.get(i)));
        next.appendTag(new NBTTagString(now)); persistent.setTag(SAVED,next);
    }

    public static JsonObject run(String op,JsonObject args,EntityPlayerMP player) {
        if(!MOVE.equals(op)) return runChecked(op,args,player);
        String id=RemoteReadProtocol.uuid(args,"intentId"), key=JsonData.string(args,"storageKey");
        need(key.length()<=160 && player!=null && player.getUniqueID().toString().equals(JsonData.string(args,"actorId"))
                && MinecraftServer.getServer().getFolderName().equals(JsonData.string(args,"worldFolder"))
                && player.dimension==JsonData.integer(args,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE),
                "identity_changed","The declared storage actor, save or dimension changed.");
        JsonObject old=stored(player,id);
        if(old!=null) return old;
        String why;
        try {
            JsonObject result=runChecked(op,args,player);
            if(result.has("intentId")) return result;
            why=result.get("reason").getAsString();
        } catch(BridgeException e) { why=e.code; }
        // A native callback can fail after changing a stack. Its durable reservation
        // takes precedence over a returned error or a later unrelated inventory state.
        old=stored(player,id);
        if(old!=null) return old;
        JsonObject refused=reason(why);
        refused.addProperty("source","server_survival_storage"); refused.addProperty("notSubmitted",true);
        refused.addProperty("storageKey",key); refused.addProperty("direction",JsonData.string(args,"direction"));
        refused.add("item",object(args,"item").deepCopy());
        save(player,id,refused); return refused;
    }

    private static JsonObject runChecked(String op,JsonObject args,EntityPlayerMP player) {
        if(RECEIPT.equals(op)) RemoteReadProtocol.keys(args,"expectedSessionId","expectedConnectionId","worldFolder","dimension","intentId");
        else if(MOVE.equals(op)) RemoteReadProtocol.keys(args,"expectedSessionId","expectedConnectionId","worldFolder","dimension","endpoint","item","direction","intentId","observationId","limit","target","actorId","storageKey");
        else RemoteReadProtocol.keys(args,"expectedSessionId","expectedConnectionId","worldFolder","dimension","endpoint","item","direction");
        need(player!=null && eligibleActor(player.isDead, player.getHealth(), player.capabilities,
                player.theItemInWorldManager == null ? null : player.theItemInWorldManager.getGameType()),
                "survival_actor_required","A living survival actor is required.");
        need(MinecraftServer.getServer().getFolderName().equals(JsonData.string(args,"worldFolder"))
             && player.dimension==JsonData.integer(args,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE),"stale_world","Storage world or dimension changed.");
        Ledger ledger=ACTORS.get(player);
        if (ledger==null) { ledger=new Ledger(); ACTORS.put(player,ledger); }
        if (RECEIPT.equals(op)) {
            JsonObject receipt=stored(player,RemoteReadProtocol.uuid(args,"intentId"));
            return receipt==null?reason("receipt_unavailable"):receipt.deepCopy();
        }
        JsonObject ep=object(args,"endpoint");
        String kind=JsonData.string(ep,"kind"), direction=JsonData.string(args,"direction");
        if("drawer".equals(kind)) RemoteReadProtocol.keys(ep,"kind","x","y","z","drawer","side");
        else RemoteReadProtocol.keys(ep,"kind","x","y","z","side");
        need("deposit".equals(direction)||"withdraw".equals(direction),"invalid_args","Unknown storage direction.");
        boolean deposit="deposit".equals(direction);
        ItemStack type=identity(object(args,"item"));
        int x=JsonData.integer(ep,"x",-30000000,30000000), y=JsonData.integer(ep,"y",0,255), z=JsonData.integer(ep,"z",-30000000,30000000);
        if (!player.worldObj.blockExists(x,y,z)) return reason("unreachable");
        TileEntity tile=player.worldObj.getTileEntity(x,y,z);
        if (!StorageWindow.target(tile,kind,ep)) return reason("endpoint_unavailable");
        MovingObjectPosition hit=visible(player,tile,ep);
        if (hit==null) return reason("unreachable");
        if (player.inventory.getItemStack()!=null) return reason("cursor_occupied");
        boolean bound=StorageWindow.bound(player,tile,kind,ep);
        if (OPEN.equals(op)) {
            if (bound) return reason("already_open");
            if (player.getHeldItem()!=null) return reason("empty_hand_required");
            if (player.openContainer!=player.inventoryContainer) return reason("unrelated_window");
            if ("drawer".equals(kind)) StorageWindow.drawerAccess(player,tile);
            if (MinecraftServer.getServer().isBlockProtected(player.worldObj,x,y,z,player)) return reason("endpoint_denied");
            boolean sneak=player.isSneaking();
            try {
                player.setSneaking("drawer".equals(kind));
                player.theItemInWorldManager.activateBlockOrUseItem(player,player.worldObj,null,x,y,z,hit.sideHit,
                    (float)(hit.hitVec.xCoord-x),(float)(hit.hitVec.yCoord-y),(float)(hit.hitVec.zCoord-z));
            } finally { player.setSneaking(sneak); }
            if (!StorageWindow.bound(player,tile,kind,ep)) return reason("endpoint_denied");
            return reason("opened");
        }
        if (!bound) {
            JsonObject missing=reason("endpoint_not_open");
            missing.addProperty("windowClosed",player.openContainer==player.inventoryContainer);
            missing.addProperty("held",player.getHeldItem()!=null); missing.addProperty("selectedSlot",player.inventory.currentItem);
            for (int i=0;i<9;i++) if(player.inventory.mainInventory[i]==null) { missing.addProperty("emptyHotbarSlot",i); break; }
            return missing;
        }
        need(player.openContainer.canInteractWith(player),"endpoint_denied","The native server container no longer permits interaction.");
        StorageWindow window=new StorageWindow(player,tile,kind,ep,deposit,type);
        long count=window.count(), inventory=playerCount(player,type);
        need(count>=0,"endpoint_unavailable","Storage did not provide a finite quantity.");
        JsonObject r=new JsonObject();
        r.addProperty("source","server_survival_storage"); r.addProperty("storageKey",window.key);
        r.addProperty("windowId",player.openContainer.windowId); r.addProperty("containerClass",player.openContainer.getClass().getName());
        r.addProperty("actorId",player.getUniqueID().toString()); r.addProperty("direction",direction);
        r.add("item",args.get("item").deepCopy());
        if (COUNT.equals(op)) {
            ledger.observation=UUID.randomUUID().toString();
            ledger.window=new WeakReference<Container>(player.openContainer); ledger.tile=new WeakReference<TileEntity>(tile); ledger.key=window.key;
            ledger.endpointCount=count; ledger.playerCount=inventory; ledger.request=ep.deepCopy();
            ledger.request.add("item",r.get("item").deepCopy()); ledger.request.addProperty("direction",direction);
            r.addProperty("observationId",ledger.observation); r.addProperty("endpointCount",count); r.addProperty("playerCount",inventory);
            return r;
        }
        need(MOVE.equals(op),"unsupported","Unknown storage operation.");
        String id=RemoteReadProtocol.uuid(args,"intentId");
        JsonObject previous=stored(player,id);
        if (previous!=null) return previous;
        JsonObject binding=ep.deepCopy(); binding.add("item",r.get("item").deepCopy()); binding.addProperty("direction",direction);
        need(RemoteReadProtocol.uuid(args,"observationId").equals(ledger.observation) && binding.equals(ledger.request)
             && ledger.window.get()==player.openContainer && ledger.tile.get()==tile && window.key.equals(ledger.key)
             && window.key.equals(JsonData.string(args,"storageKey")),
             "identity_changed","Original storage observation, owner or server container changed.");
        int limit=JsonData.integer(args,"limit",1,64);
        long target=args.has("target")?positiveLong(args,"target"):Long.MAX_VALUE;
        need(!args.has("target")||deposit,"invalid_args","Only a deposit can declare a destination target.");
        ledger.observation=null;
        r.addProperty("intentId",id); r.addProperty("endpointBefore",count); r.addProperty("playerBefore",inventory);
        if (count!=ledger.endpointCount || inventory!=ledger.playerCount) {
            r.addProperty("notSubmitted",true); r.addProperty("reason",deposit && count>=target?"target_already_met":"counts_changed");
            save(player,id,r); return r;
        }
        if (deposit) limit=(int)Math.min(limit,Math.max(0,target-count));
        JsonObject unknown=reason("reconcile_required");
        save(player,id,unknown); // Reserve before touching any real stack. A failed callback is never retried.
        ItemStack[] inventoryBefore=new ItemStack[player.inventory.getSizeInventory()];
        for(int i=0;i<inventoryBefore.length;i++) inventoryBefore[i]=ItemStack.copyItemStack(player.inventory.getStackInSlot(i));
        String outcome=limit==0?"target_already_met":window.move(limit);
        player.openContainer.detectAndSendChanges(); player.updateHeldItem();
        long after=window.count(), playerAfter=playerCount(player,type);
        long moved=deposit?after-count:count-after;
        if (moved<0 || moved>limit || moved!=(deposit?inventory-playerAfter:playerAfter-inventory) || player.inventory.getItemStack()!=null)
            return unknown;
        for(int i=0;i<inventoryBefore.length;i++) {
            ItemStack a=inventoryBefore[i], b=player.inventory.getStackInSlot(i);
            if(ItemStack.areItemStacksEqual(a,b)) continue;
            if(i>=player.inventory.mainInventory.length || (a!=null&&!same(a,type)) || (b!=null&&!same(b,type))) return unknown;
        }
        r.addProperty("endpointAfter",after); r.addProperty("playerAfter",playerAfter); r.addProperty("moved",moved);
        r.addProperty("conserved",true); r.addProperty("cursorEmpty",true); r.addProperty("reason",moved>0?"transferred":outcome);
        save(player,id,r); return r;
    }

    static long positiveLong(JsonObject args,String key) {
        try { long v=Long.parseLong(JsonData.string(args,key)); if (v>0) return v; }
        catch (NumberFormatException ignored) {}
        throw new BridgeException("invalid_args","Expected a positive signed 64-bit storage target.");
    }
    static JsonObject object(JsonObject parent,String key) {
        need(parent.has(key)&&parent.get(key).isJsonObject(),"invalid_args","Missing "+key); return parent.getAsJsonObject(key);
    }
    static boolean same(ItemStack a,ItemStack b) { return a!=null && b!=null && a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a,b); }
    public static ItemStack identity(JsonObject row) {
        RemoteReadProtocol.keys(row,"registryName","damage","nbt");
        String name=JsonData.string(row,"registryName");
        Item item=(Item)Item.itemRegistry.getObject(name);
        need(item!=null && name.equals(Item.itemRegistry.getNameForObject(item)),"invalid_args","Unknown registered item.");
        ItemStack stack=new ItemStack(item,1,JsonData.integer(row,"damage",0,32767));
        if(row.has("nbt")) {
            String raw=JsonData.string(row,"nbt");
            need(raw.length()<=4096,"invalid_args","Full bounded NBT is required.");
            try {
                NBTBase parsed=JsonToNBT.func_150315_a(raw);
                need(parsed instanceof NBTTagCompound && StorageItemNbt.roundTrips(raw,parsed.toString()),"invalid_args","Use complete round-trippable native SNBT with exact scalar types.");
                stack.setTagCompound((NBTTagCompound)parsed);
            } catch(net.minecraft.nbt.NBTException e) { throw new BridgeException("invalid_args","Invalid item NBT."); }
        }
        return stack;
    }
    static int playerCount(EntityPlayerMP p,ItemStack type) {
        int count=0;
        for(ItemStack stack:p.inventory.mainInventory) if(same(stack,type)) count+=stack.stackSize;
        return count;
    }
    static MovingObjectPosition visible(EntityPlayerMP player,TileEntity tile,JsonObject ep) {
        double reach=player.theItemInWorldManager.getBlockReachDistance();
        Vec3 eye=Vec3.createVectorHelper(player.posX,player.posY+player.getEyeHeight(),player.posZ);
        for(int i=0;i<6;i++) {
            if(ep.has("side")&&JsonData.integer(ep,"side",0,5)!=i) continue;
            ForgeDirection face=ForgeDirection.getOrientation(i);
            Vec3 end=Vec3.createVectorHelper(tile.xCoord+.5+face.offsetX*.49,tile.yCoord+.5+face.offsetY*.49,tile.zCoord+.5+face.offsetZ*.49);
            // Extend into the shape so recessed chests and terminal parts can be hit.
            MovingObjectPosition hit=player.worldObj.rayTraceBlocks(eye,end,false,true,false);
            if (hit==null || hit.blockX!=tile.xCoord || hit.blockY!=tile.yCoord || hit.blockZ!=tile.zCoord)
                hit=player.worldObj.rayTraceBlocks(eye,Vec3.createVectorHelper(tile.xCoord+.5,tile.yCoord+.5,tile.zCoord+.5),false,true,false);
            if(hit!=null&&hit.blockX==tile.xCoord&&hit.blockY==tile.yCoord&&hit.blockZ==tile.zCoord&&hit.sideHit==i
                    &&eye.distanceTo(hit.hitVec)<=reach) return hit;
        }
        return null;
    }
}
