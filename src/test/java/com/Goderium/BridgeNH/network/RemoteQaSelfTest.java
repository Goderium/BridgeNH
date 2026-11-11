package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.common.qa.RemoteQaAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.UUID;

/** Transport and ownership tests do not substitute for a real dedicated-server run. */
public final class RemoteQaSelfTest {
    private static int checks;
    private static void check(boolean value,String label) { checks++; if(!value) throw new AssertionError(label); }
    private static void refused(Runnable action,String label) {
        try { action.run(); throw new AssertionError(label); }
        catch(BridgeException expected) { checks++; }
    }
    public static int run() {
        checks=0;
        JsonObject value=new JsonObject(); value.addProperty("requestId",UUID.randomUUID().toString());
        JsonArray rows=new JsonArray();
        for(int i=0;i<1800;i++) { JsonObject row=new JsonObject(); row.addProperty("slot",i); row.addProperty("text","authoritative-item-"+i+"-"+repeat("测试数据",24)); rows.add(row); }
        value.add("entries",rows);
        RemoteQaPackets.Frame[] frames=RemoteQaPackets.split(value,RemoteQaPackets.RESPONSE_BYTES);
        check(frames.length>4,"QA evidence genuinely exceeds old custom-payload limit");
        Object sender=new Object(); RemoteQaPackets.Assembler assembler=new RemoteQaPackets.Assembler(RemoteQaPackets.RESPONSE_BYTES);
        JsonObject reconstructed=null;
        for(int i=frames.length-1;i>=0;i--) {
            ByteBuf bytes=Unpooled.buffer();
            try {
                frames[i].write(bytes,RemoteQaPackets.RESPONSE_BYTES);
                check(bytes.readableBytes()<32767,"Each 1.7.10 packet remains below its limit");
                RemoteQaPackets.Frame frame=RemoteQaPackets.Frame.read(bytes,RemoteQaPackets.RESPONSE_BYTES);
                reconstructed=assembler.accept(frame,sender,100+i);
                if(i>0) check(assembler.accept(frame,sender,100+i)==null,"Exact duplicate fragment is harmless before completion");
            } finally { bytes.release(); }
        }
        check(reconstructed!=null && reconstructed.toString().equals(value.toString()),"Large UTF8 evidence retains all rows and quantities");
        check(assembler.size()==0,"Completed evidence releases assembly memory");
        refused(() -> frames[0].validate(1),"Logical byte bound is checked");
        refused(() -> new RemoteQaPackets.Frame(frames[0].id,frames[0].total,-1,frames[0].bytes).validate(RemoteQaPackets.RESPONSE_BYTES),"Negative fragment offset refused");
        ByteBuf malicious=Unpooled.buffer();
        try {
            malicious.writeLong(0); malicious.writeLong(0); malicious.writeInt(Integer.MAX_VALUE); malicious.writeInt(0); malicious.writeInt(Integer.MAX_VALUE);
            refused(() -> RemoteQaPackets.Frame.read(malicious,RemoteQaPackets.RESPONSE_BYTES),"Malicious lengths rejected before allocation");
        } finally { malicious.release(); }
        assembler.accept(frames[0],sender,0);
        assembler.accept(frames[1],new Object(),0);
        check(assembler.size()==2,"Different connections cannot combine fragment records");
        assembler.clear(sender); check(assembler.size()==1,"Disconnect releases only its own assemblies");
        assembler.expire(RemoteQaPackets.IDLE_NANOS+1); check(assembler.size()==0,"Expired incomplete records release memory");
        assembler.accept(frames[0],sender,0);
        byte[] conflicting=frames[0].bytes.clone(); conflicting[0]^=1;
        refused(() -> assembler.accept(new RemoteQaPackets.Frame(frames[0].id,frames[0].total,frames[0].offset,conflicting),sender,1),"Conflicting duplicate cannot replace earlier evidence");
        check(assembler.size()==0,"Rejected conflicting record releases memory");
        for(int i=0;i<4;i++) assembler.accept(new RemoteQaPackets.Frame(UUID.randomUUID(),frames[0].total,0,frames[0].bytes),sender,0);
        refused(() -> assembler.accept(new RemoteQaPackets.Frame(UUID.randomUUID(),frames[0].total,0,frames[0].bytes),sender,0),"Four logical in-flight records per sender");
        assembler.clear(); check(assembler.size()==0,"Stop releases all fragment buffers");
        for(String operation:RemoteReadProtocol.QA) {
            refused(() -> RemoteReadProtocol.permit(operation,false),"Every remote QA endpoint requires operator permission");
            RemoteReadProtocol.permit(operation,true); check(true,"Operator fixed QA operation accepted");
        }
        ownership(); return checks;
    }
    private static void ownership() {
        RemoteQaAdapter adapter=new RemoteQaAdapter();
        String runtime=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString(),requestId=UUID.randomUUID().toString();
        JsonObject envelope=new JsonObject(); envelope.addProperty("clientSessionId",runtime);
        envelope.add("qaPolicy", new JsonParser().parse("{level:'test',readOnly:false,movement:'path'}").getAsJsonObject());
        JsonObject args=new JsonObject(); args.addProperty("expectedWorld","QAWorld"); args.addProperty("selector","tstqa:test"); envelope.add("arguments",args);
        refused(() -> adapter.prepare("qa.run",envelope,requestId,"DifferentWorld","OtherFolder"),"Wrong authoritative world cannot submit a run");
        check(adapter.prepare("qa.run",envelope,requestId,"QAWorld","QAWorld").get("selector").getAsString().equals("tstqa:test"),"First exact-world action accepted");
        refused(() -> adapter.prepare("qa.run",envelope,requestId,"QAWorld","QAWorld"),"Same action ID can never execute twice, including a lost receipt");
        JsonObject changed=new JsonParser().parse(envelope.toString()).getAsJsonObject(); changed.addProperty("clientSessionId",UUID.randomUUID().toString());
        refused(() -> adapter.prepare("qa.status",changed,UUID.randomUUID().toString(),"QAWorld","QAWorld"),"Caller cannot switch runtime within an authenticated connection");
        JsonObject source=new JsonObject(); source.addProperty("sessionId",owner); source.addProperty("source","tstqa_public_instance_adapter"); source.addProperty("boundedStatusWait",true);
        JsonObject evidence=new JsonObject(); evidence.addProperty("sessionId",owner); evidence.addProperty("quantity","9007199254740993"); source.add("evidence",evidence);
        JsonObject result=adapter.result(source,owner,owner,"actor");
        check(result.get("sessionId").getAsString().equals(runtime) && result.getAsJsonObject("evidence").get("sessionId").getAsString().equals(runtime),"Server ownership token is mapped consistently into client QA DTOs");
        check(source.get("sessionId").getAsString().equals(owner),"Response adaptation never mutates public runner evidence");
        check(result.get("source").getAsString().equals("tstqa_public_instance_adapter"),"Public QA source remains distinct from network transport");
        check(result.getAsJsonObject("evidence").get("quantity").getAsString().equals("9007199254740993"),"Exact large quantities survive transport adaptation");
        check(result.get("maxStatusWaitMillis").getAsInt()==5000,"Remote wait stays below its connection timeout");
    }
    private static String repeat(String value,int count) { StringBuilder result=new StringBuilder(); for(int i=0;i<count;i++) result.append(value); return result.toString(); }
}
