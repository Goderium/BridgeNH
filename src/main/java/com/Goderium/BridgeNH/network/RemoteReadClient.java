package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.relauncher.Side;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import net.minecraft.client.Minecraft;

/** Client owning-thread handshake/submission/acceptance; the HTTP thread alone waits for replies. */
public final class RemoteReadClient implements RemoteReadNetwork.ClientSink {
    public static final RemoteReadClient INSTANCE=new RemoteReadClient();
    public final RemoteReadMailbox mailbox=new RemoteReadMailbox();
    private final ArrayBlockingQueue<Runnable> events=new ArrayBlockingQueue<Runnable>(16);
    private final ArrayBlockingQueue<Reply> replies=new ArrayBlockingQueue<Reply>(64);
    private Object handler,world;
    private int dimension;
    private boolean advertised;
    private String nonce=UUID.randomUUID().toString(),connection="",state="disconnected";
    private JsonObject serverInfo,handshakeError;
    private RemoteReadMailbox.Pending hello,poseRead;
    private JsonObject authoritativePose;
    private long poseReceived;
    private int sampleTicks;
    public boolean operatorReady() { return "ready".equals(state) && operatorSample(authoritativePose, System.nanoTime()-poseReceived); }
    @com.google.common.annotations.VisibleForTesting
    public static boolean operatorSample(JsonObject pose, long ageNanos) {
        return pose != null && ageNanos >= 0 && ageNanos <= 1000000000L && pose.has("operatorReads")
                && pose.get("operatorReads").isJsonPrimitive() && pose.get("operatorReads").getAsJsonPrimitive().isBoolean()
                && pose.get("operatorReads").getAsBoolean();
    }
    public boolean qaInstalled() { return "ready".equals(state) && serverInfo != null && serverInfo.has("qaInstalled") && serverInfo.get("qaInstalled").getAsBoolean(); }
    public boolean qaReady() { return operatorReady() && qaInstalled(); }
    public JsonObject worldIdentity() { return serverInfo==null ? null : serverInfo.getAsJsonObject("world"); }
    public JsonObject poseObservation() {
        JsonObject value=new JsonObject(); value.addProperty("available",false);
        if(authoritativePose==null) return value;
        long age=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-poseReceived);
        if(age>1000) return value;
        value=new com.google.gson.JsonParser().parse(authoritativePose.toString()).getAsJsonObject();
        value.addProperty("available",true); value.addProperty("source","remote_server_tick"); value.addProperty("ageMillis",age);
        value.addProperty("connectionId",connection); return value;
    }
    public static void initialize() { RemoteReadNetwork.clientSink=INSTANCE; FMLCommonHandler.instance().bus().register(INSTANCE); }
    public void receive(JsonObject response,Object sender) { replies.offer(new Reply(response,sender)); }
    @SubscribeEvent public void connected(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        events.offer(() -> {
            if(event.manager.isChannelOpen() && (Minecraft.getMinecraft().getNetHandler()==null || event.handler==Minecraft.getMinecraft().getNetHandler())) channelConnected(event.handler,event.isLocal);
        });
    }
    @SubscribeEvent public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        events.offer(() -> { if(handler==event.handler) { reset(); handler=null; world=null; advertised=false; state="disconnected"; } });
    }
    @SubscribeEvent public void registration(FMLNetworkEvent.CustomPacketRegistrationEvent<?> event) {
        if(event.side!=Side.CLIENT || !event.registrations.contains(RemoteReadNetwork.CHANNEL)) return;
        events.offer(() -> { if(event.manager.isChannelOpen()) channelRegistration(Minecraft.getMinecraft().getNetHandler(),event.handler,event.operation); });
    }
    // Forge REGISTER normally precedes ClientConnected; retain only this exact live handler's advertisement.
    void channelConnected(Object current,boolean local) {
        boolean known=handler==current && advertised;
        if(handler!=current) world=null;
        reset(); handler=current; advertised=local || known; state="awaiting_channel";
    }
    void channelRegistration(Object current,Object registered,String operation) {
        if(registered==null || current!=null && current!=registered) return;
        if(handler!=registered) { reset(); handler=registered; world=null; }
        advertised="REGISTER".equals(operation);
        if(!advertised) { reset(); state="unsupported"; }
    }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END) return;
        for(int i=0;i<16;i++) { Runnable task=events.poll(); if(task==null) break; task.run(); }
        Minecraft client=Minecraft.getMinecraft(); Object actual=client.getNetHandler();
        if(handler!=actual && (actual!=null || world!=null)) { reset(); handler=actual; world=null; advertised=actual!=null && client.isSingleplayer(); state=actual==null ? "disconnected" : "awaiting_channel"; }
        if(client.theWorld!=null && client.thePlayer!=null && actual!=null) {
            if(world!=client.theWorld || dimension!=client.thePlayer.dimension) {
                reset(); world=client.theWorld; dimension=client.thePlayer.dimension; state=advertised ? "awaiting_handshake" : "unsupported";
            }
            if(advertised && hello==null && connection.isEmpty() && !"handshake_failed".equals(state)) {
                hello=send("hello",new JsonObject()); state="handshaking";
            }
        } else if(world!=null) { reset(); world=null; state="awaiting_world"; }
        mailbox.expire(System.nanoTime()); RemoteReadNetwork.responses.expire(System.nanoTime());
        for(int i=0;i<16;i++) { Reply reply=replies.poll(); if(reply==null) break;
            try { mailbox.receive(reply.value,reply.handler,System.nanoTime()); } catch(RuntimeException invalid) { /* Malformed server replies do not certify observations. */ }
        }
        if(hello!=null && hello.future.isDone() && connection.isEmpty() && "handshaking".equals(state)) {
            try {
                JsonObject response=hello.future.join(),data=response.getAsJsonObject("data");
                String id=RemoteReadProtocol.uuid(data,"connectionId");
                if(!id.equals(response.get("connectionId").getAsString()) || data.get("dimension").getAsInt()!=dimension
                        || !RemoteReadProtocol.actorMatches(client.thePlayer.getUniqueID(),client.thePlayer.getCommandSenderName(),
                                RemoteReadProtocol.uuid(data,"playerUuid"))) throw new BridgeException("stale_world","Server handshake actor differs.");
                connection=id; serverInfo=data; state="ready";
            } catch(RuntimeException failure) {
                state="handshake_failed"; serverInfo=null;
                Throwable cause=failure; while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null) cause=cause.getCause();
                handshakeError=new JsonObject();
                handshakeError.addProperty("code",cause instanceof BridgeException ? ((BridgeException)cause).code : "adapter_error");
                handshakeError.addProperty("message",JsonData.bounded(cause.getMessage(),256));
            }
        }
        if(poseRead!=null && poseRead.future.isDone()) {
            try {
                JsonObject response=poseRead.future.join();
                requireCurrent(response.get("connectionId").getAsString());
                authoritativePose=response.getAsJsonObject("data"); poseReceived=System.nanoTime();
            } catch(RuntimeException failed) { authoritativePose=null; }
            poseRead=null;
        }
        if("ready".equals(state) && poseSampleDue(++sampleTicks,poseRead!=null,mailbox.size())) poseRead=send("self",new JsonObject());
    }
    // A waiting QA status/read must not starve the authoritative actor sample used by its guards.
    static boolean poseSampleDue(int ticks,boolean pending,int inFlight) {
        return ticks%10==0 && !pending && inFlight<RemoteReadMailbox.MAX_PENDING;
    }
    private void reset() {
        if(handler!=null) RemoteReadNetwork.responses.clear(handler);
        mailbox.clear(); replies.clear(); nonce=UUID.randomUUID().toString(); connection=""; hello=null; serverInfo=null;
        poseRead=null; authoritativePose=null; poseReceived=0; sampleTicks=0; handshakeError=null;
    }
    public JsonObject status() {
        JsonObject data=new JsonObject(); data.addProperty("version",1); data.addProperty("state",state); data.addProperty("ready","ready".equals(state));
        data.addProperty("channelAdvertised",advertised); data.addProperty("connectionId",connection); data.addProperty("dimension",dimension);
        data.addProperty("pendingReads",mailbox.size()); data.addProperty("computationSide","local_client");
        data.addProperty("maxPositionsPerPage",RemoteReadProtocol.MAX_POSITIONS); data.addProperty("packetBytes",RemoteReadProtocol.RESPONSE_BYTES);
        data.addProperty("navigationExecutionSupported",false); data.addProperty("qaExecutionSupported",qaReady()); data.addProperty("fullBuildRecipeWorkflowSupported",false);
        if(handshakeError!=null) data.add("handshakeError",handshakeError.deepCopy());
        if(serverInfo!=null) {
            JsonObject current=serverInfo.deepCopy(); current.addProperty("operatorReads",operatorReady());
            current.addProperty("qaMutationsSupported",qaReady()); data.add("server",current);
        }
        return data;
    }
    public String connectionId() { requireReady(); return connection; }
    public void requireCurrent(String expected) {
        requireReady(); Minecraft client=Minecraft.getMinecraft();
        if(!connection.equals(expected) || handler!=client.getNetHandler() || world!=client.theWorld
                || client.thePlayer==null || client.thePlayer.dimension!=dimension) throw new BridgeException("stale_world","Expected server connection changed.");
    }
    public RemoteReadMailbox.Pending begin(String operation,JsonObject args,String expected) { requireCurrent(expected); return send(operation,args); }
    private RemoteReadMailbox.Pending send(String operation,JsonObject args) {
        JsonObject request=RemoteReadProtocol.request(operation,nonce,connection,dimension,args);
        RemoteReadProtocol.encode(request,RemoteReadProtocol.large(operation) ? RemoteQaPackets.REQUEST_BYTES : RemoteReadProtocol.REQUEST_BYTES);
        RemoteReadMailbox.Pending pending=mailbox.submit(request,handler,System.nanoTime());
        try { RemoteReadNetwork.sendRequest(request); }
        catch(RuntimeException failure) { mailbox.cancel(pending); throw failure; } return pending;
    }
    public JsonObject accept(RemoteReadMailbox.Pending pending,JsonObject response,String expected) {
        requireCurrent(expected);
        if(pending.handler!=handler || !nonce.equals(response.get("nonce").getAsString()) || !connection.equals(response.get("connectionId").getAsString()))
            throw new BridgeException("stale_world","Remote observation no longer belongs to this connection.");
        JsonObject data=response.getAsJsonObject("data");
        if(RemoteReadProtocol.QA.contains(pending.request.get("operation").getAsString())) data.addProperty("transportSource","remote_server_tick");
        else data.addProperty("source","remote_server_tick");
        data.addProperty("connectionId",connection);
        data.addProperty("dimension",dimension); data.add("serverTick",response.get("serverTick")); data.add("roundTripMillis",response.get("roundTripMillis"));
        data.addProperty("receivedAgeMillis",Math.max(0,(System.nanoTime()-pending.started)/1000000.0-response.get("roundTripMillis").getAsDouble())); return data;
    }
    private void requireReady() { if(!"ready".equals(state)) throw new BridgeException("unsupported","Server bridge is not ready ("+state+"). Install the same MCB jar on the server and reconnect; no action was sent."); }
    private static final class Reply {
        final JsonObject value; final Object handler;
        Reply(JsonObject value,Object handler) { this.value=value; this.handler=handler; }
    }
}
