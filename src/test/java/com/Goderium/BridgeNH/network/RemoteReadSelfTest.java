package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.client.navigation.SchematicGoal;
import com.Goderium.BridgeNH.common.qa.HorizonQaAdapter;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class RemoteReadSelfTest {
    private static int checks;
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static void refused(Runnable action,String label) { boolean failed=false; try { action.run(); } catch(BridgeException expected) { failed=true; } check(failed,label); }
    public static int run() throws Exception {
        // Forge generates wrappers in a different package/loader: invoke the real wrapper, not reflection.
        cpw.mods.fml.common.eventhandler.ASMEventHandler serverHandler = new cpw.mods.fml.common.eventhandler.ASMEventHandler(
                new RemoteReadServer(), RemoteReadServer.class.getMethod("tick", cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent.class), null);
        serverHandler.invoke(new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(cpw.mods.fml.common.gameevent.TickEvent.Phase.START));
        check(true, "Dedicated server listener accessible through real Forge ASM wrapper");
        cpw.mods.fml.common.eventhandler.ASMEventHandler clientHandler = new cpw.mods.fml.common.eventhandler.ASMEventHandler(
                new RemoteReadClient(), RemoteReadClient.class.getMethod("tick", cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent.class), null);
        clientHandler.invoke(new cpw.mods.fml.common.gameevent.TickEvent.ClientTickEvent(cpw.mods.fml.common.gameevent.TickEvent.Phase.START));
        check(true, "Remote client listener accessible through real Forge ASM wrapper");
        UUID account=UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        String offline="b50ad385-829d-3141-a216-7e7d7539ba7f";
        check(RemoteReadProtocol.actorMatches(account,"Notch",account.toString()),"Account UUID remains accepted");
        check(RemoteReadProtocol.actorMatches(account,"Notch",offline),"Standard same-name offline UUID accepted");
        check(!RemoteReadProtocol.actorMatches(account,"notch",offline),"Offline actor name remains case sensitive");
        check(!RemoteReadProtocol.actorMatches(account,"OtherPlayer",offline),"Other player offline UUID refused");
        check(!RemoteReadProtocol.actorMatches(account,"Notch","00000000-0000-0000-0000-000000000000"),"Arbitrary server actor refused");
        check(!RemoteReadProtocol.actorMatches(null,"Notch",offline),"Missing current account actor refused");
        check(!RemoteReadProtocol.actorMatches(account,null,offline),"Missing offline player name refused");
        check(!RemoteReadProtocol.actorMatches(account,"",offline),"Empty offline player name refused");
        check(!RemoteReadProtocol.actorMatches(account,"Notch",null),"Missing server actor refused");
        JsonObject request=RemoteReadProtocol.request("self",UUID.randomUUID().toString(),UUID.randomUUID().toString(),180,new JsonObject());
        byte[] bytes=RemoteReadProtocol.encode(request,RemoteReadProtocol.REQUEST_BYTES);
        check(RemoteReadProtocol.decode(bytes,RemoteReadProtocol.REQUEST_BYTES).equals(request),"Strict UTF-8 envelope round trip");
        io.netty.buffer.ByteBuf buffer=Unpooled.buffer();
        try { RemoteReadProtocol.write(buffer,request,RemoteReadProtocol.REQUEST_BYTES); check(RemoteReadProtocol.read(buffer,RemoteReadProtocol.REQUEST_BYTES).equals(request),"Length-prefixed round trip"); }
        finally { buffer.release(); }
        for(String invalid:new String[]{"{\"x\":1,\"x\":2}","{x:1}","{\"x\":NaN}","{} trailing","[1]","{\"x\":1e999}","{\"x\":/* comment */1}"})
            refused(() -> RemoteReadProtocol.decode(invalid.getBytes(StandardCharsets.UTF_8),16384),"Malformed strict JSON rejected: "+invalid);
        refused(() -> RemoteReadProtocol.decode(new byte[]{'{','"','x','"',':','"',(byte)0xc0,(byte)0xaf,'"','}'},16384),"Malformed UTF-8 rejected");
        String nested="0"; for(int i=0;i<18;i++) nested="["+nested+"]"; final String deep="{\"x\":"+nested+"}";
        refused(() -> RemoteReadProtocol.decode(deep.getBytes(StandardCharsets.UTF_8),16384),"Depth bound rejects malicious nesting");
        refused(() -> RemoteReadProtocol.encode(request,4),"Encoder byte bound");
        io.netty.buffer.ByteBuf invalidLength=Unpooled.buffer(); invalidLength.writeInt(Integer.MAX_VALUE);
        try { refused(() -> RemoteReadProtocol.read(invalidLength,16384),"Length rejected before allocation"); } finally { invalidLength.release(); }
        io.netty.buffer.ByteBuf suffix=Unpooled.buffer(); suffix.writeInt(2); suffix.writeBytes(new byte[]{'{','}',0});
        try { refused(() -> RemoteReadProtocol.read(suffix,16384),"Packet trailing bytes rejected"); } finally { suffix.release(); }
        refused(() -> RemoteReadProtocol.request("setblock",UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,new JsonObject()),"Mutation operation refused");
        RemoteReadProtocol.permit("self",false); check(true,"Own-player observation needs no operator");
        refused(() -> RemoteReadProtocol.permit("blocks",false),"Nonoperator block read refused");
        refused(() -> RemoteReadProtocol.permit("machine",false),"Nonoperator machine read refused");
        RemoteReadProtocol.permit("machine",true); check(true,"Operator fixed machine read");
        for (String operation:new String[]{"machine.inspect","world.inspect","world.inspect_many","gui.container"}) {
            check(operation.equals(RemoteReadProtocol.operation(RemoteReadProtocol.request(operation,UUID.randomUUID().toString(),UUID.randomUUID().toString(),180,new JsonObject()))),"Native guarded observation retains its operation identity");
            refused(() -> RemoteReadProtocol.permit(operation,false),"Remote owner observation rejects nonoperators");
        }
        JsonObject args=new JsonObject(); JsonArray positions=new JsonArray(); args.add("positions",positions);
        refused(() -> RemoteReadProtocol.positions(args),"Empty page refused");
        for(int i=0;i<64;i++) positions.add(xyz(i,4,0)); check(RemoteReadProtocol.positions(args).size()==64,"64-position page allowed");
        positions.add(xyz(64,4,0)); refused(() -> RemoteReadProtocol.positions(args),"Oversized page refused");
        JsonObject duplicates=new JsonObject(); JsonArray rows=new JsonArray(); rows.add(xyz(1,2,3)); rows.add(xyz(1,2,3)); duplicates.add("positions",rows);
        refused(() -> RemoteReadProtocol.positions(duplicates),"Duplicate coordinates refused");
        JsonObject wrongBoolean=new JsonObject(); wrongBoolean.addProperty("includeInventory","true");
        refused(() -> RemoteReadProtocol.booleanArg(wrongBoolean,"includeInventory"),"String boolean refused");
        RemoteReadClient channel = new RemoteReadClient();
        Object first = new Object(), second = new Object();
        channel.channelRegistration(first,first,"REGISTER");
        channel.channelConnected(first,false);
        check(channel.status().get("channelAdvertised").getAsBoolean(),"Forge REGISTER before CONNECTED retains live advertisement");
        channel.channelConnected(second,false);
        check(!channel.status().get("channelAdvertised").getAsBoolean(),"Different handler never inherits old connection capability");
        channel.channelRegistration(second,first,"REGISTER");
        check(!channel.status().get("channelAdvertised").getAsBoolean(),"Delayed old-handler registration is ignored");
        channel.channelRegistration(second,second,"REGISTER");
        check(channel.status().get("channelAdvertised").getAsBoolean(),"CONNECTED before REGISTER also advertises exact handler");
        channel.channelRegistration(second,second,"UNREGISTER");
        check(!channel.status().get("channelAdvertised").getAsBoolean(),"Unregister revokes capability and mailbox");
        channel = new RemoteReadClient();
        channel.channelRegistration(null,first,"REGISTER");
        channel.channelConnected(first,false);
        check(channel.status().get("channelAdvertised").getAsBoolean(),"Login channel advertisement retained before Minecraft has a player/sendQueue");
        for(Class<?> common:new Class<?>[]{RemoteReadServer.class,HorizonQaAdapter.class,PrivateDimensionQaAdapter.class}) {
            try(java.io.InputStream classBytes=common.getResourceAsStream("/"+common.getName().replace('.','/')+".class")) {
                new org.objectweb.asm.ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode,String owner,String name,String descriptor,boolean isInterface) {
                                check(!owner.equals("net/minecraft/server/MinecraftServer") || !name.equals("getWorldName"),"Common QA code cannot call Forge-stripped CLIENT-only world identity API");
                            }
                        };
                    }
                },0);
            }
        }
        poseSampling();
        lifecycle(request);
        schematic();
        // Resolve common entrypoints with client implementation/classes unavailable. This is not a real server launch.
        java.net.URL[] urls=((java.net.URLClassLoader)RemoteReadSelfTest.class.getClassLoader()).getURLs();
        try(java.net.URLClassLoader isolated=new java.net.URLClassLoader(urls,null) {
            protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("net.minecraft.client.") || name.equals("com.Goderium.BridgeNH.loader.ClientBootstrap")
                        || name.equals("com.Goderium.BridgeNH.network.RemoteReadClient") || name.equals("com.Goderium.BridgeNH.common.bridge.GameBridge")) throw new ClassNotFoundException("Client unavailable: "+name);
                return super.loadClass(name,resolve);
            }
        }) {
            for(String name:new String[]{"network.RemoteQaPackets","common.qa.RemoteQaAdapter","common.qa.HorizonQaAdapter",
                    "common.qa.PrivateDimensionQaAdapter","common.world.WorldScan","common.world.WorldScan$MinecraftAccess",
                    "common.world.WorldObservationsAdapter","common.machine.DeveloperMachineTools","common.recipe.RecipeQueryAdapter",
                    "BridgeNH","network.RemoteReadProtocol","network.RemoteReadNetwork","network.RemoteReadNetwork$RequestHandler",
                    "network.RemoteReadNetwork$ResponseHandler","network.RemoteReadServer","util.JsonData","common.machine.GregTechAdapter",
                    "common.machine.MachineDataAdapter","common.recipe.GTRecipeAdapter","common.machine.MachineEnergyAdapter"}) {
                Class<?> type=Class.forName("com.Goderium.BridgeNH."+name,true,isolated); type.getDeclaredMethods(); type.getDeclaredFields();
                check(true,"Common entrypoint resolves without client: "+name);
            }
        }
        return checks;
    }
    private static void poseSampling() {
        RemoteReadMailbox box=new RemoteReadMailbox(); Object handler=new Object();
        String nonce=UUID.randomUUID().toString(),connection=UUID.randomUUID().toString();
        JsonObject qa=RemoteReadProtocol.request("qa.status",nonce,connection,180,new JsonObject());
        RemoteReadMailbox.Pending waiting=box.submit(qa,handler,0);
        int samples=0;
        // Keep one real QA future pending for five seconds while normal actor observations finish.
        for(int tick=1;tick<=100;tick++) if(RemoteReadClient.poseSampleDue(tick,false,box.size())) {
            JsonObject request=RemoteReadProtocol.request("self",nonce,connection,180,new JsonObject());
            RemoteReadMailbox.Pending pose=box.submit(request,handler,tick*50000000L);
            check(!RemoteReadClient.poseSampleDue(tick,true,box.size()),"Pending pose never duplicated");
            check(box.receive(reply(request),handler,tick*50000000L+1000) && pose.future.isDone(),"Actor sample completes beside waiting QA status");
            samples++;
        }
        check(samples==10 && !waiting.future.isDone() && box.size()==1,"Five-second QA wait retains half-second actor sampling without replay");
        java.util.List<JsonObject> busy=new java.util.ArrayList<JsonObject>();
        for(int i=0;i<3;i++) {
            JsonObject request=RemoteReadProtocol.request("self",nonce,connection,180,new JsonObject());
            busy.add(request); box.submit(request,handler,5000000000L);
        }
        check(!RemoteReadClient.poseSampleDue(110,false,box.size()),"Full four-request window preserves bound");
        check(box.receive(reply(busy.get(0)),handler,5000000001L)
                && RemoteReadClient.poseSampleDue(120,false,box.size()),"Sampling resumes when a request slot opens");
        box.clear(); check(waiting.future.isCompletedExceptionally() && box.size()==0,"Disconnect releases wait and sample state");
    }
    private static void lifecycle(JsonObject request) {
        RemoteReadMailbox box=new RemoteReadMailbox(); Object handler=new Object();
        RemoteReadMailbox.Pending pending=box.submit(request,handler,100);
        JsonObject reply=reply(request);
        check(!box.receive(reply,new Object(),200),"Different connection handler ignored");
        JsonObject stale=new JsonParser().parse(reply.toString()).getAsJsonObject(); stale.addProperty("nonce",UUID.randomUUID().toString());
        check(!box.receive(stale,handler,200),"Stale nonce ignored");
        stale.add("nonce",request.get("nonce")); stale.addProperty("dimension",181);
        check(!box.receive(stale,handler,200),"Different dimension ignored");
        stale.add("dimension",request.get("dimension")); stale.addProperty("connectionId",UUID.randomUUID().toString());
        check(!box.receive(stale,handler,200),"Different server epoch ignored");
        check(box.receive(reply,handler,200) && pending.future.isDone() && box.size()==0,"Exact connection reply completed once");
        check(!box.receive(reply,handler,300),"Duplicate reply ignored");
        for(int i=0;i<4;i++) box.submit(RemoteReadProtocol.request("self",request.get("nonce").getAsString(),request.get("connectionId").getAsString(),180,new JsonObject()),handler,100);
        refused(() -> box.submit(RemoteReadProtocol.request("self",request.get("nonce").getAsString(),request.get("connectionId").getAsString(),180,new JsonObject()),handler,100),"Four-request bound");
        box.expire(6500000101L); check(box.size()==0,"Expired reads released");
        java.util.List<JsonObject> pages=new java.util.ArrayList<JsonObject>();
        java.util.List<RemoteReadMailbox.Pending> issued=new java.util.ArrayList<RemoteReadMailbox.Pending>();
        for(int i=0;i<4;i++) {
            JsonObject page=RemoteReadProtocol.request("blocks",request.get("nonce").getAsString(),request.get("connectionId").getAsString(),180,new JsonObject());
            pages.add(page); issued.add(box.submit(page,handler,100));
        }
        for(int i=3;i>=0;i--) check(box.receive(reply(pages.get(i)),handler,200+i),"Out-of-order pipeline page matched");
        for(int i=0;i<4;i++) check(issued.get(i).future.join().get("requestId").equals(pages.get(i).get("requestId")),"Pipeline result retains page identity");
        check(box.size()==0,"Pipeline releases all four pending pages");
        pending=box.submit(request,handler,100); box.clear(); check(pending.future.isCompletedExceptionally() && box.size()==0,"Disconnect invalidates pending reads");
        pending=box.submit(request,handler,100); box.cancel(pending); check(pending.future.isCompletedExceptionally() && box.size()==0,"Cancellation releases one pending read");
        pending=box.submit(request,handler,100); reply=reply(request); reply.addProperty("ok",false); reply.addProperty("code","permission_denied"); reply.addProperty("message","No operator");
        check(box.receive(reply,handler,200) && pending.future.isCompletedExceptionally(),"Server refusal preserved as failure");
    }
    private static JsonObject reply(JsonObject request) {
        JsonObject result=new JsonParser().parse(request.toString()).getAsJsonObject(); result.addProperty("ok",true); result.add("data",new JsonObject()); return result;
    }
    private static JsonObject xyz(int x,int y,int z) { JsonObject result=new JsonObject(); result.addProperty("x",x); result.addProperty("y",y); result.addProperty("z",z); return result; }
    private static void schematic() {
        JsonObject args=new JsonParser().parse("{\"anchor\":{\"x\":0,\"y\":4,\"z\":0},\"cells\":[{\"x\":0,\"y\":0,\"z\":0,\"kind\":\"block\",\"alternatives\":[{\"registryName\":\"minecraft:stone\",\"metadata\":0}]},{\"x\":0,\"y\":1,\"z\":0,\"kind\":\"block\",\"alternatives\":[{\"registryName\":\"minecraft:stone\",\"metadata\":0}]},{\"x\":1,\"y\":0,\"z\":0,\"kind\":\"air\"}]}").getAsJsonObject();
        SchematicGoal.Comparison comparison=new SchematicGoal.Comparison(args); Map<String,JsonObject> facts=new LinkedHashMap<String,JsonObject>();
        for(com.google.gson.JsonElement value:comparison.observationPositions()) { JsonObject p=value.getAsJsonObject(); JsonObject state=xyz(p.get("x").getAsInt(),p.get("y").getAsInt(),p.get("z").getAsInt());
            state.addProperty("loaded",true); state.addProperty("air",true); state.addProperty("replaceable",true); state.addProperty("registryName","minecraft:air"); state.addProperty("metadata",0); state.addProperty("tilePresent",false);
            facts.put(state.get("x").getAsInt()+":"+state.get("y").getAsInt()+":"+state.get("z").getAsInt(),state);
        }
        JsonObject support=xyz(0,3,0); support.addProperty("loaded",true); facts.put("0:3:0",support); support.addProperty("air",false); support.addProperty("replaceable",false); support.addProperty("registryName","minecraft:stone");
        while(!comparison.advance((x,y,z)->facts.get(x+":"+y+":"+z),0,4,0,false,System.nanoTime()+10000000L)) { }
        JsonObject result=comparison.finish(); check(result.get("computationSide").getAsString().equals("local_client"),"Snapshot comparator computes locally");
        check(result.getAsJsonObject("counts").get("missing").getAsInt()==2,"Snapshot missing counts");
        check(result.get("plannedPlacementCount").getAsInt()==2,"Existing support seeds two-layer attachment order");
        check(!result.get("formationProved").getAsBoolean() && !result.get("worldMutated").getAsBoolean(),"Snapshot predictions never infer formation or mutate");
        check(result.getAsJsonArray("materials").get(0).getAsJsonObject().get("count").getAsInt()==2,"Material counts aggregated on client");
        facts.remove("0:4:0"); comparison=new SchematicGoal.Comparison(args);
        while(!comparison.advance((x,y,z)->facts.get(x+":"+y+":"+z),0,4,0,false,System.nanoTime()+10000000L)) { }
        check(comparison.finish().getAsJsonObject("counts").get("unknown").getAsInt()==1,"Missing observation remains unknown");
        JsonObject floor=facts.get("0:5:0"); floor.addProperty("air",false); floor.addProperty("replaceable",false); floor.addProperty("registryName","minecraft:dirt"); floor.addProperty("relativeHardness",0.1);
        comparison=new SchematicGoal.Comparison(args);
        while(!comparison.advance((x,y,z)->facts.get(x+":"+y+":"+z),0,4,0,false,System.nanoTime()+10000000L)) { }
        result=comparison.finish(); check(result.getAsJsonObject("counts").get("conflict").getAsInt()==1,"Snapshot conflicts preserved");
        check(result.getAsJsonArray("differences").get(1).getAsJsonObject().has("breakCost"),"Observed hardness cost computed on client");
    }
}
