package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** Same optional Forge channel on both sides; only the client has a loopback MCP listener. */
public final class RemoteReadNetwork {
    public static final String CHANNEL="codexbr_read_v1";
    static SimpleNetworkWrapper network;
    private static final RemoteReadServer SERVER=new RemoteReadServer();

    public static void resetServer() { SERVER.reset(); }
    interface ClientSink { void receive(JsonObject response,Object handler); }
    static volatile ClientSink clientSink;
    static final RemoteQaPackets.Assembler requests=new RemoteQaPackets.Assembler(RemoteQaPackets.REQUEST_BYTES);
    static final RemoteQaPackets.Assembler responses=new RemoteQaPackets.Assembler(RemoteQaPackets.RESPONSE_BYTES);
    static void sendRequest(JsonObject value) {
        if(RemoteReadProtocol.large(value.get("operation").getAsString()))
            for(RemoteQaPackets.Frame frame:RemoteQaPackets.split(value,RemoteQaPackets.REQUEST_BYTES)) network.sendToServer(new LargeRequest(frame));
        else network.sendToServer(new Request(value));
    }
    static void sendResponse(JsonObject value,net.minecraft.entity.player.EntityPlayerMP player) {
        if(RemoteReadProtocol.large(value.get("operation").getAsString()))
            for(RemoteQaPackets.Frame frame:RemoteQaPackets.split(value,RemoteQaPackets.RESPONSE_BYTES)) network.sendTo(new LargeResponse(frame),player);
        else network.sendTo(new Response(value),player);
    }
    public static void initialize() {
        network=NetworkRegistry.INSTANCE.newSimpleChannel(CHANNEL);
        network.registerMessage(RequestHandler.class,Request.class,0,Side.SERVER);
        network.registerMessage(ResponseHandler.class,Response.class,1,Side.CLIENT);
        network.registerMessage(LargeRequestHandler.class,LargeRequest.class,2,Side.SERVER);
        network.registerMessage(LargeResponseHandler.class,LargeResponse.class,3,Side.CLIENT);
        FMLCommonHandler.instance().bus().register(SERVER);
    }
    public static final class Request implements IMessage {
        JsonObject value;
        public Request() { }
        Request(JsonObject value) { this.value=value; }
        public void fromBytes(ByteBuf bytes) { value=RemoteReadProtocol.read(bytes,RemoteReadProtocol.REQUEST_BYTES); }
        public void toBytes(ByteBuf bytes) { RemoteReadProtocol.write(bytes,value,RemoteReadProtocol.REQUEST_BYTES); }
    }
    public static final class Response implements IMessage {
        JsonObject value;
        public Response() { }
        Response(JsonObject value) { this.value=value; }
        public void fromBytes(ByteBuf bytes) { value=RemoteReadProtocol.read(bytes,RemoteReadProtocol.RESPONSE_BYTES); }
        public void toBytes(ByteBuf bytes) { RemoteReadProtocol.write(bytes,value,RemoteReadProtocol.RESPONSE_BYTES); }
    }
    public static final class LargeRequest implements IMessage {
        RemoteQaPackets.Frame frame;
        public LargeRequest() { }
        LargeRequest(RemoteQaPackets.Frame frame) { this.frame=frame; }
        public void fromBytes(ByteBuf bytes) { frame=RemoteQaPackets.Frame.read(bytes,RemoteQaPackets.REQUEST_BYTES); }
        public void toBytes(ByteBuf bytes) { frame.write(bytes,RemoteQaPackets.REQUEST_BYTES); }
    }
    public static final class LargeResponse implements IMessage {
        RemoteQaPackets.Frame frame;
        public LargeResponse() { }
        LargeResponse(RemoteQaPackets.Frame frame) { this.frame=frame; }
        public void fromBytes(ByteBuf bytes) { frame=RemoteQaPackets.Frame.read(bytes,RemoteQaPackets.RESPONSE_BYTES); }
        public void toBytes(ByteBuf bytes) { frame.write(bytes,RemoteQaPackets.RESPONSE_BYTES); }
    }
    public static final class LargeRequestHandler implements IMessageHandler<LargeRequest,IMessage> {
        public IMessage onMessage(LargeRequest message,MessageContext context) {
            try {
                JsonObject value=requests.accept(message.frame,context.getServerHandler(),System.nanoTime());
                if(value!=null) SERVER.enqueue(value,context.getServerHandler());
            } catch(BridgeException invalid) { /* Invalid or full assemblies never execute. */ }
            return null;
        }
    }
    public static final class LargeResponseHandler implements IMessageHandler<LargeResponse,IMessage> {
        public IMessage onMessage(LargeResponse message,MessageContext context) {
            try {
                JsonObject value=responses.accept(message.frame,context.netHandler,System.nanoTime());
                ClientSink sink=clientSink; if(value!=null && sink!=null) sink.receive(value,context.netHandler);
            } catch(BridgeException invalid) { /* Invalid responses never certify QA completion. */ }
            return null;
        }
    }
    public static final class RequestHandler implements IMessageHandler<Request,IMessage> {
        public IMessage onMessage(Request message,MessageContext context) {
            SERVER.enqueue(message.value,context.getServerHandler()); return null;
        }
    }
    public static final class ResponseHandler implements IMessageHandler<Response,IMessage> {
        public IMessage onMessage(Response message,MessageContext context) {
            ClientSink sink=clientSink; if(sink!=null) sink.receive(message.value,context.netHandler); return null;
        }
    }
}
