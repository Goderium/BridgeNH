package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.dialogue.AgentDialogueCommand;
import com.Goderium.BridgeNH.common.dialogue.AgentDialogueMailbox;
import com.Goderium.BridgeNH.common.machine.DeveloperMachineTools;
import com.Goderium.BridgeNH.common.machine.GregTechAdapter;
import com.Goderium.BridgeNH.common.machine.MachineDataAdapter;
import com.Goderium.BridgeNH.common.qa.HorizonQaAdapter;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaAdapter;
import com.Goderium.BridgeNH.common.qa.RemoteQaAdapter;
import com.Goderium.BridgeNH.common.recipe.RecipeQueryAdapter;
import com.Goderium.BridgeNH.common.world.WorldInspectionAdapter;
import com.Goderium.BridgeNH.common.world.WorldObservationsAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.fluids.IFluidHandler;

/** Network threads enqueue only. Sampling is bounded/cooperative on the owning server tick. */
public final class RemoteReadServer {
    private final ArrayBlockingQueue<Work> queue=new ArrayBlockingQueue<Work>(64);
    private final Map<NetHandlerPlayServer,Integer> inflight=new IdentityHashMap<NetHandlerPlayServer,Integer>();
    private final Map<NetHandlerPlayServer,Session> sessions=new IdentityHashMap<NetHandlerPlayServer,Session>();
    private long tick;
    synchronized void enqueue(JsonObject request,NetHandlerPlayServer handler) {
        // Bound even malformed/HELLO requests; never read player/world on the network thread.
        if(inflight.getOrDefault(handler,0)>=4) return;
        if(queue.offer(new Work(request,handler))) inflight.put(handler,inflight.getOrDefault(handler,0)+1);
    }
    public synchronized void reset() { queue.clear(); inflight.clear(); sessions.clear(); RemoteReadNetwork.requests.clear(); tick=0; AgentDialogueCommand.closeDialogue("Server session ended."); }
    @SubscribeEvent public synchronized void disconnect(FMLNetworkEvent.ServerDisconnectionFromClientEvent event) {
        Session old=sessions.remove(event.handler); if(old!=null) old.scans.invalidate();
        RemoteReadNetwork.requests.clear(event.handler); inflight.remove(event.handler); queue.removeIf(work -> work.handler==event.handler);
    }
    @SubscribeEvent public synchronized void tick(TickEvent.ServerTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        tick++; RemoteReadNetwork.requests.expire(System.nanoTime());
        if(tick%20==0) AgentDialogueCommand.expireDialogue(System.currentTimeMillis());
        for(Session session:sessions.values()) session.scans.beginTick();
        long deadline=System.nanoTime()+4000000L; int count=Math.min(4,queue.size());
        for(int i=0;i<count && System.nanoTime()<deadline;i++) {
            Work work=queue.poll(); if(work==null) break;
            try {
                if(System.nanoTime()-work.created>6500000000L) throw new BridgeException("timeout","Server read expired before completion.");
                EntityPlayerMP player=work.handler.playerEntity;
                if(player==null || player.isDead || player.playerNetServerHandler!=work.handler || player.worldObj==null)
                    throw new BridgeException("stale_world","Server player connection is no longer live.");
                String operation=RemoteReadProtocol.operation(work.request);
                if(player.dimension!=work.request.get("dimension").getAsInt()) throw new BridgeException("stale_world","Server dimension changed.");
                RemoteReadProtocol.permit(operation,player.canCommandSenderUseCommand(2,"codexbridge"));
                if("hello".equals(operation)) {
                    RemoteReadProtocol.keys(work.request.getAsJsonObject("args"));
                    Session session=new Session(player,work.request.get("nonce").getAsString()); sessions.put(work.handler,session);
                    JsonObject data=new JsonObject(); data.addProperty("connectionId",session.id); data.addProperty("playerUuid",session.playerId);
                    data.addProperty("dimension",player.dimension); data.addProperty("operatorReads",player.canCommandSenderUseCommand(2,"codexbridge"));
                    data.addProperty("computationSide","local_client"); data.addProperty("mutationsSupported",false);
                    data.addProperty("guardedObservationVersion",1); data.addProperty("guiNativeWaitVersion",1);
                    data.addProperty("qaInstalled",cpw.mods.fml.common.Loader.isModLoaded("horizonqa"));
                    data.addProperty("qaMutationsSupported",player.canCommandSenderUseCommand(2,"codexbridge") && cpw.mods.fml.common.Loader.isModLoaded("horizonqa"));
                    net.minecraft.server.MinecraftServer server=net.minecraft.server.MinecraftServer.getServer();
                    JsonObject world=new JsonObject(); world.addProperty("worldName",HorizonQaAdapter.serverWorldName(server)); world.addProperty("worldFolder",server.getFolderName());
                    world.addProperty("dimension",player.dimension); world.addProperty("providerClass",player.worldObj.provider.getClass().getName()); data.add("world",world);
                    JsonArray capabilities=new JsonArray(); for(String read:new String[]{"self","blocks","machine"}) capabilities.add(new com.google.gson.JsonPrimitive(read));
                    data.add("readOperations",capabilities); complete(work,data,null); continue;
                }
                Session session=sessions.get(work.handler);
                requireSession(session,player,work.request);
                if(work.session==null) work.session=session;
                if(work.session!=session) throw new BridgeException("stale_world","Remote read connection epoch changed.");
                JsonObject args=work.request.getAsJsonObject("args");
                if(AgentDialogueMailbox.OPERATION.equals(operation)) {
                    complete(work,AgentDialogueCommand.performDialogue(player.getUniqueID().toString(),args,System.currentTimeMillis()),null);
                    continue;
                }
                if(RemoteReadProtocol.QA.contains(operation)) {
                    net.minecraft.server.MinecraftServer server=net.minecraft.server.MinecraftServer.getServer();
                    if(work.qaArgs==null) work.qaArgs=session.qa.prepare(operation,args,work.request.get("requestId").getAsString(),HorizonQaAdapter.serverWorldName(server),server.getFolderName());
                    JsonObject policy = args.getAsJsonObject("qaPolicy");
                    QaPolicy.check(operation, work.qaArgs, JsonData.string(policy, "level"), policy.get("readOnly").getAsBoolean(),
                            JsonData.string(policy, "movement"), true, player.capabilities.isCreativeMode || player.canCommandSenderUseCommand(2, "codexbridge"),
                            player.canCommandSenderUseCommand(2, "tp"));
                    int wait="qa.status".equals(operation) ? PrivateDimensionQaAdapter.waitMillis(work.qaArgs) : 0;
                    if(wait>0 && player.dimension!=0) {
                        if(work.completion==null) {
                            work.waitStarted=System.nanoTime();
                            work.completion=PrivateDimensionQaAdapter.completion(player,session.id,CatalogData.text(work.qaArgs,"runId",128,""));
                        }
                        if(!work.completion.isDone() && System.nanoTime()-work.waitStarted<java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(wait,5000))) {
                            queue.offer(work); continue;
                        }
                    }
                    JsonObject data=session.qa.nativeQa.perform(operation,server,player,session.id,work.qaArgs,policy);
                    if(wait>0) {
                        JsonObject receipt=new JsonObject(); receipt.addProperty("requestedMillis",wait); receipt.addProperty("effectiveMillis",player.dimension==0 ? 0 : Math.min(wait,5000));
                        receipt.addProperty("supported",player.dimension!=0); receipt.addProperty("completed",work.completion!=null && work.completion.isDone() && !work.completion.isCompletedExceptionally());
                        receipt.addProperty("timedOut",work.completion!=null && !work.completion.isDone()); data.add("statusWait",receipt);
                    }
                    complete(work,session.qa.result(data,session.id,session.id,session.playerId),null);
                } else if(RemoteReadProtocol.OBSERVATIONS.contains(operation)) {
                    if(args.has("dimension") && args.get("dimension").getAsInt()!=player.dimension) throw new BridgeException("stale_world","Observation dimension differs from the current player.");
                    JsonObject data;
                    if("world.scan".equals(operation)) {
                        work.scanControl=new GameQueue.Control();
                        data=session.scans.page(new WorldScan.MinecraftAccess(player.worldObj,player),args,session.scanSettings,0,
                                new int[]{(int)Math.floor(player.posX),(int)Math.floor(player.boundingBox.minY),(int)Math.floor(player.posZ)},work.scanControl);
                    } else if("world.tiles".equals(operation)) data=DeveloperMachineTools.tiles(player.worldObj,args);
                    else if("world.entities".equals(operation)) data=WorldObservationsAdapter.entities(player.worldObj,player,args,session.scanSettings);
                    else if ("machine.inspect".equals(operation)) {
                        RemoteReadProtocol.keys(args,"x","y","z","dimension","componentOffset","componentLimit","inputOffset","inputLimit","outputQueries");
                        int x=JsonData.integer(args,"x",-30000000,30000000), y=JsonData.integer(args,"y",0,255), z=JsonData.integer(args,"z",-30000000,30000000);
                        if (!player.worldObj.getChunkProvider().chunkExists(x>>4,z>>4)) throw new BridgeException("chunk_unloaded","Machine query requires an existing loaded chunk.");
                        data=MachineDataAdapter.inspect(player.worldObj,player.worldObj.getTileEntity(x,y,z),args);
                    } else if ("world.inspect_many".equals(operation)) {
                        RemoteReadProtocol.keys(args,"positions","dimension","compact","includeAe");
                        data=WorldInspectionAdapter.inspectMany(player.worldObj,args,true);
                        for (JsonElement row:data.getAsJsonArray("results")) if (row.getAsJsonObject().has("data"))
                            row.getAsJsonObject().getAsJsonObject("data").addProperty("source","remote_server_tick");
                    } else if ("world.inspect".equals(operation)) {
                        RemoteReadProtocol.keys(args,"x","y","z","dimension","includeAe");
                        int x=JsonData.integer(args,"x",-30000000,30000000), y=JsonData.integer(args,"y",0,255), z=JsonData.integer(args,"z",-30000000,30000000);
                        data=WorldInspectionAdapter.aeDetails(player.worldObj,x,y,z,true,args,WorldInspectionAdapter.block(player.worldObj,x,y,z,true));
                    } else data=session.recipes.read(player.worldObj,session.id,player.dimension,player.inventory,(x,y,z) -> {
                        if(!player.worldObj.getChunkProvider().chunkExists(x>>4,z>>4)) throw new BridgeException("unloaded","Recipe controller chunk is not loaded.");
                        return player.worldObj.getTileEntity(x,y,z);
                    },args,System.nanoTime());
                    data.addProperty("source","remote_server_tick"); data.addProperty("dimension",player.dimension);
                    data.addProperty("worldTick",player.worldObj.getTotalWorldTime()); complete(work,data,null);
                } else if ("gui.container".equals(operation)) {
                    complete(work,session.container.read(player,args),null);
                } else if("self".equals(operation)) {
                    RemoteReadProtocol.keys(args,"includeInventory");
                    JsonObject data=pose(player);
                    data.addProperty("operatorReads",player.canCommandSenderUseCommand(2,"codexbridge"));
                    if(RemoteReadProtocol.booleanArg(args,"includeInventory")) data.add("inventory",JsonData.inventory(player.inventory));
                    complete(work,data,null);
                } else if("blocks".equals(operation)) {
                    if(work.positions==null) { work.positions=RemoteReadProtocol.positions(args); work.firstTick=tick; }
                    int sampled=0;
                    while(work.next<work.positions.size() && sampled++<64 && System.nanoTime()<deadline) {
                        JsonObject xyz=work.positions.get(work.next++).getAsJsonObject();
                        work.blocks.add(block(player,xyz.get("x").getAsInt(),xyz.get("y").getAsInt(),xyz.get("z").getAsInt()));
                    }
                    if(work.next<work.positions.size()) { queue.offer(work); continue; }
                    JsonObject data=pose(player); data.add("blocks",work.blocks); data.addProperty("firstServerTick",work.firstTick);
                    data.addProperty("complete",true); data.addProperty("nonAtomic",work.firstTick!=tick); complete(work,data,null);
                } else {
                    RemoteReadProtocol.keys(args,"x","y","z","componentOffset","componentLimit");
                    int x=JsonData.integer(args,"x",-29999999,29999999),y=JsonData.integer(args,"y",0,255),z=JsonData.integer(args,"z",-29999999,29999999);
                    JsonObject data=block(player,x,y,z);
                    if(!data.get("loaded").getAsBoolean()) throw new BridgeException("unloaded","Machine chunk is not loaded; no chunk load was requested.");
                    TileEntity tile=player.worldObj.getTileEntity(x,y,z);
                    if(tile==null) throw new BridgeException("not_found","No machine tile exists at this loaded position.");
                    data.add("machine",MachineDataAdapter.inspect(player.worldObj,tile,args));
                    if(tile instanceof IInventory) data.add("inventory",JsonData.inventory((IInventory)tile));
                    if(tile instanceof IFluidHandler) data.add("fluids",JsonData.fluids((IFluidHandler)tile));
                    complete(work,data,null);
                }
            } catch(BridgeException failure) { complete(work,null,failure); }
            catch(RuntimeException | LinkageError failure) { complete(work,null,new BridgeException("adapter_error","Server observation unavailable: "+failure.getClass().getSimpleName())); }
        }
    }
    private void complete(Work work,JsonObject data,BridgeException error) {
        try {
            JsonObject response=new JsonObject(); response.addProperty("version",1);
            for(String key:new String[]{"requestId","nonce","operation"}) if(work.request.has(key)) response.add(key,work.request.get(key));
            Session session=sessions.get(work.handler); response.addProperty("connectionId",session==null ? "" : session.id);
            response.addProperty("dimension",work.request.has("dimension") ? work.request.get("dimension").getAsInt() : 0);
            response.addProperty("serverTick",tick); response.addProperty("source","remote_server_tick");
            response.addProperty("ok",error==null);
            if(error==null) response.add("data",data); else { response.addProperty("code",error.code); response.addProperty("message",JsonData.bounded(error.getMessage(),256)); }
            try { RemoteReadProtocol.encode(response,RemoteReadProtocol.large(work.request.get("operation").getAsString()) ? RemoteQaPackets.RESPONSE_BYTES : RemoteReadProtocol.RESPONSE_BYTES); }
            catch(BridgeException tooLarge) {
                response.remove("data"); response.addProperty("ok",false);
                response.addProperty("code",RemoteQaAdapter.action(work.request.get("operation").getAsString()) ? "qa_outcome_unknown" : "data_limit");
                response.addProperty("message",RemoteQaAdapter.action(work.request.get("operation").getAsString()) ? "QA ran but its receipt exceeded the bound. Inspect status; do not replay." : tooLarge.getMessage());
            }
            RemoteReadNetwork.sendResponse(response,work.handler.playerEntity);
            if(work.scanControl!=null && session!=null) session.scans.responseFinished(work.scanControl,response.get("ok").getAsBoolean());
        } catch(RuntimeException ignored) { /* A disconnected recipient has no live result. */ }
        finally {
            Session session=sessions.get(work.handler);
            if(work.scanControl!=null && session!=null) session.scans.responseFinished(work.scanControl,false);
            int count=inflight.getOrDefault(work.handler,0)-1; if(count<=0) inflight.remove(work.handler); else inflight.put(work.handler,count); }
    }
    static void requireSession(Session session,EntityPlayerMP player,JsonObject request) {
        if(session==null || !session.id.equals(request.get("connectionId").getAsString()) || !session.nonce.equals(request.get("nonce").getAsString())
                || !session.playerId.equals(player.getUniqueID().toString()) || session.dimension!=player.dimension || session.world!=player.worldObj)
            throw new BridgeException("stale_world","Remote connection/player/world identity changed; reconnect the bridge.");
    }
    static JsonObject pose(EntityPlayerMP player) {
        JsonObject data=new JsonObject(); data.addProperty("playerUuid",player.getUniqueID().toString()); data.addProperty("dimension",player.dimension);
        data.addProperty("x",player.posX); data.addProperty("y",player.boundingBox.minY); data.addProperty("z",player.posZ);
        data.addProperty("yaw",player.rotationYaw); data.addProperty("pitch",player.rotationPitch); data.addProperty("onGround",player.onGround);
        data.addProperty("creative",player.capabilities.isCreativeMode); data.addProperty("allowFlying",player.capabilities.allowFlying);
        data.addProperty("flying",player.capabilities.isFlying); data.addProperty("worldTime",player.worldObj.getTotalWorldTime()); data.addProperty("worldTick",player.worldObj.getTotalWorldTime()); return data;
    }
    public static JsonObject block(EntityPlayerMP player,int x,int y,int z) {
        World world=player.worldObj; JsonObject data=new JsonObject(); data.addProperty("x",x); data.addProperty("y",y); data.addProperty("z",z);
        boolean loaded=y>=0 && y<=255 && world.getChunkProvider().chunkExists(x>>4,z>>4);
        data.addProperty("loaded",loaded); if(!loaded) return data;
        Block block=world.getBlock(x,y,z); boolean air=block.isAir(world,x,y,z);
        data.addProperty("registryName",JsonData.bounded(String.valueOf(Block.blockRegistry.getNameForObject(block)),256));
        data.addProperty("metadata",world.getBlockMetadata(x,y,z)); data.addProperty("air",air); data.addProperty("replaceable",air || block.isReplaceable(world,x,y,z));
        TileEntity tile=world.getTileEntity(x,y,z); data.addProperty("tilePresent",tile!=null);
        if(tile!=null) { data.addProperty("tileClass",JsonData.bounded(tile.getClass().getName(),256)); JsonObject gt=GregTechAdapter.identity(tile); if(gt!=null) data.add("gregtech",gt); }
        else if(!air) { float relative=block.getPlayerRelativeBlockHardness(player,world,x,y,z); if(Float.isFinite(relative)) data.addProperty("relativeHardness",relative); }
        return data;
    }
    static final class Session {
        final String id=UUID.randomUUID().toString(),nonce,playerId; final int dimension; final World world;
        final RemoteContainerObservation container=new RemoteContainerObservation();
        final RemoteQaAdapter qa=new RemoteQaAdapter(); final WorldScan scans=new WorldScan();
        final ScanSettings scanSettings=new ScanSettings(1,1,1,1,64); final RecipeQueryAdapter recipes=new RecipeQueryAdapter();
        Session(EntityPlayerMP player,String nonce) { this.nonce=nonce; playerId=player.getUniqueID().toString(); dimension=player.dimension; world=player.worldObj; }
    }
    private static final class Work {
        final JsonObject request; final NetHandlerPlayServer handler; final long created=System.nanoTime();
        final JsonArray blocks=new JsonArray(); JsonArray positions; Session session; int next; long firstTick;
        JsonObject qaArgs; GameQueue.Control scanControl; java.util.concurrent.CompletableFuture<?> completion; long waitStarted;
        Work(JsonObject request,NetHandlerPlayServer handler) { this.request=request; this.handler=handler; }
    }
}
