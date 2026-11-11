package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.common.dialogue.AgentDialogueMailbox;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import io.netty.buffer.ByteBuf;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Side-neutral bounded data protocol. No world access, arbitrary commands, or client classes. */
public final class RemoteReadProtocol {
    public static final int VERSION=1, REQUEST_BYTES=16384, RESPONSE_BYTES=28672, MAX_POSITIONS=64;
    public static final String OPERATION="server.observe";
    public static final Set<String> READS=new HashSet<String>(Arrays.asList("hello","self","blocks","machine","gui.container"));
    public static final Set<String> QA=new HashSet<String>(Arrays.asList("qa.catalog","qa.run","qa.status","qa.results","qa.command","qa.cancel"));
    public static final Set<String> OBSERVATIONS=new HashSet<String>(Arrays.asList("world.scan","world.tiles","world.entities","recipes.query","machine.inspect","world.inspect","world.inspect_many"));
    static boolean large(String operation) { return QA.contains(operation) || OBSERVATIONS.contains(operation); }
    public static void keys(JsonObject value,String... allowed) {
        Set<String> names=new HashSet<String>(Arrays.asList(allowed));
        for(Map.Entry<String,JsonElement> entry:value.entrySet()) if(!names.contains(entry.getKey()))
            throw new BridgeException("invalid_args","Unrecognized remote read field: "+entry.getKey());
    }
    public static String uuid(JsonObject value,String key) {
        String text=JsonData.string(value,key);
        try { if(!UUID.fromString(text).toString().equals(text)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException failure) { throw new BridgeException("invalid_args","Invalid request/connection UUID."); }
        return text;
    }
    /** Login may assign the same player a standard offline UUID; never accept an arbitrary actor. */
    static boolean actorMatches(UUID clientId,String playerName,String serverId) {
        if(clientId==null || serverId==null) return false;
        if(clientId.toString().equals(serverId)) return true;
        return playerName!=null && !playerName.isEmpty() && playerName.length()<=16
                && UUID.nameUUIDFromBytes(("OfflinePlayer:"+playerName).getBytes(StandardCharsets.UTF_8)).toString().equals(serverId);
    }
    static String operation(JsonObject request) {
        keys(request,"version","requestId","nonce","connectionId","operation","dimension","args");
        if(JsonData.integer(request,"version",1,1)!=VERSION) throw new BridgeException("unsupported","Remote protocol version differs.");
        uuid(request,"requestId"); uuid(request,"nonce");
        String operation=JsonData.string(request,"operation");
        if(!READS.contains(operation) && !large(operation) && !AgentDialogueMailbox.OPERATION.equals(operation)) throw new BridgeException("unsupported","Only fixed observations and public QA operations are supported.");
        if(!request.has("args") || !request.get("args").isJsonObject()) throw new BridgeException("invalid_args","Remote args must be an object.");
        JsonData.integer(request,"dimension",Integer.MIN_VALUE,Integer.MAX_VALUE);
        if(!"hello".equals(operation)) uuid(request,"connectionId");
        return operation;
    }
    static JsonObject request(String operation,String nonce,String connection,int dimension,JsonObject args) {
        JsonObject row=new JsonObject(); row.addProperty("version",VERSION); row.addProperty("requestId",UUID.randomUUID().toString());
        row.addProperty("nonce",nonce); row.addProperty("connectionId",connection); row.addProperty("operation",operation);
        row.addProperty("dimension",dimension); row.add("args",args); operation(row); return row;
    }
    @com.google.common.annotations.VisibleForTesting
    public static byte[] encode(JsonObject value,int bound) {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>bound) throw new BridgeException("data_limit","Remote observation exceeds its packet bound; request a smaller page.");
        decode(bytes,bound); // Encoder and decoder must agree on nesting, strings and finite numbers.
        return bytes;
    }
    static void write(ByteBuf buffer,JsonObject value,int bound) {
        byte[] bytes=encode(value,bound); buffer.writeInt(bytes.length); buffer.writeBytes(bytes);
    }
    static JsonObject read(ByteBuf buffer,int bound) {
        if(buffer.readableBytes()<4) throw new BridgeException("invalid_packet","Remote packet length missing.");
        int size=buffer.readInt();
        if(size<2 || size>bound || size!=buffer.readableBytes()) throw new BridgeException("invalid_packet","Remote packet length invalid.");
        byte[] bytes=new byte[size]; buffer.readBytes(bytes); return decode(bytes,bound);
    }
    static JsonObject decode(byte[] bytes,int bound) {
        if(bytes.length<2 || bytes.length>bound) throw new BridgeException("invalid_packet","Remote packet size invalid.");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonReader reader=new JsonReader(new StringReader(text)); reader.setLenient(false);
            JsonElement value=parse(reader,0,new int[]{0},bound>RESPONSE_BYTES ? 65536 : 8192);
            if(!value.isJsonObject() || reader.peek()!=JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
            return value.getAsJsonObject();
        } catch(Exception failure) { throw new BridgeException("invalid_packet","Remote packet is not bounded strict UTF-8 JSON."); }
    }
    private static JsonElement parse(JsonReader reader,int depth,int[] nodes,int maximumNodes) throws java.io.IOException {
        if(depth>16 || ++nodes[0]>maximumNodes) throw new IllegalArgumentException();
        switch(reader.peek()) {
            case BEGIN_OBJECT:
                reader.beginObject(); JsonObject object=new JsonObject();
                while(reader.hasNext()) {
                    String name=reader.nextName(); if(name.length()>128 || object.has(name)) throw new IllegalArgumentException();
                    object.add(name,parse(reader,depth+1,nodes,maximumNodes));
                } reader.endObject(); return object;
            case BEGIN_ARRAY:
                reader.beginArray(); JsonArray array=new JsonArray();
                while(reader.hasNext()) array.add(parse(reader,depth+1,nodes,maximumNodes)); reader.endArray(); return array;
            case STRING:
                String text=reader.nextString(); if(text.length()>8192) throw new IllegalArgumentException(); return new JsonPrimitive(text);
            case NUMBER:
                String number=reader.nextString(); if(number.length()>64) throw new IllegalArgumentException();
                BigDecimal decimal=new BigDecimal(number); if(!Double.isFinite(decimal.doubleValue())) throw new IllegalArgumentException(); return new JsonPrimitive(decimal);
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IllegalArgumentException();
        }
    }
    public static JsonArray positions(JsonObject args) {
        keys(args,"positions");
        if(!args.has("positions") || !args.get("positions").isJsonArray()) throw new BridgeException("invalid_args","Block observations require positions.");
        JsonArray values=args.getAsJsonArray("positions");
        if(values.size()<1 || values.size()>MAX_POSITIONS) throw new BridgeException("invalid_args","Remote block page accepts 1..64 positions.");
        Set<String> seen=new HashSet<String>();
        for(JsonElement value:values) {
            if(!value.isJsonObject()) throw new BridgeException("invalid_args","Position must be an XYZ object.");
            JsonObject row=value.getAsJsonObject(); keys(row,"x","y","z");
            int x=JsonData.integer(row,"x",-29999999,29999999),y=JsonData.integer(row,"y",0,255),z=JsonData.integer(row,"z",-29999999,29999999);
            if(!seen.add(x+":"+y+":"+z)) throw new BridgeException("invalid_args","Duplicate block positions are not accepted.");
        } return values;
    }
    public static boolean booleanArg(JsonObject args,String key) {
        if(!args.has(key)) return false;
        if(!args.get(key).isJsonPrimitive() || !args.get(key).getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args",key+" must be a boolean.");
        return args.get(key).getAsBoolean();
    }
    @com.google.common.annotations.VisibleForTesting
    public static void permit(String operation,boolean operator) {
        if(("blocks".equals(operation) || "machine".equals(operation) || "gui.container".equals(operation) || large(operation) || AgentDialogueMailbox.OPERATION.equals(operation)) && !operator)
            throw new BridgeException("permission_denied","Authoritative block/machine reads require server operator level 2.");
    }
}
