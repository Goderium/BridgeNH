package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Logical QA records are bounded independently of Minecraft 1.7.10 custom-payload packets. */
final class RemoteQaPackets {
    static final int CHUNK_BYTES=28000, REQUEST_BYTES=262144, RESPONSE_BYTES=1048576;
    static final long IDLE_NANOS=6500000000L;
    static Frame[] split(JsonObject value,int bound) {
        byte[] bytes=RemoteReadProtocol.encode(value,bound);
        UUID id=UUID.fromString(RemoteReadProtocol.uuid(value,"requestId"));
        Frame[] frames=new Frame[(bytes.length+CHUNK_BYTES-1)/CHUNK_BYTES];
        for(int i=0;i<frames.length;i++) frames[i]=new Frame(id,bytes.length,i*CHUNK_BYTES,
                Arrays.copyOfRange(bytes,i*CHUNK_BYTES,Math.min(bytes.length,(i+1)*CHUNK_BYTES)));
        return frames;
    }
    static final class Frame {
        final UUID id; final int total,offset; final byte[] bytes;
        Frame(UUID id,int total,int offset,byte[] bytes) { this.id=id; this.total=total; this.offset=offset; this.bytes=bytes; }
        void validate(int bound) {
            if(id==null || total<2 || total>bound || offset<0 || offset>=total || offset%CHUNK_BYTES!=0
                    || bytes==null || bytes.length!=Math.min(CHUNK_BYTES,total-offset))
                throw new BridgeException("invalid_packet","Invalid bounded QA fragment.");
        }
        void write(ByteBuf out,int bound) {
            validate(bound); out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
            out.writeInt(total); out.writeInt(offset); out.writeInt(bytes.length); out.writeBytes(bytes);
        }
        static Frame read(ByteBuf in,int bound) {
            if(in.readableBytes()<28) throw new BridgeException("invalid_packet","QA fragment header missing.");
            UUID id=new UUID(in.readLong(),in.readLong()); int total=in.readInt(),offset=in.readInt(),length=in.readInt();
            if(length<1 || length>CHUNK_BYTES || length!=in.readableBytes() || total<2 || total>bound
                    || offset<0 || offset>=total || offset%CHUNK_BYTES!=0 || length!=Math.min(CHUNK_BYTES,total-offset))
                throw new BridgeException("invalid_packet","QA fragment lengths invalid.");
            byte[] bytes=new byte[length]; in.readBytes(bytes); return new Frame(id,total,offset,bytes);
        }
    }
    static final class Assembler {
        private final int bound;
        private final Map<Object,Map<UUID,Assembly>> senders=new IdentityHashMap<Object,Map<UUID,Assembly>>();
        Assembler(int bound) { this.bound=bound; }
        synchronized JsonObject accept(Frame frame,Object sender,long now) {
            expire(now); frame.validate(bound);
            Map<UUID,Assembly> rows=senders.get(sender);
            if(rows==null) { rows=new HashMap<UUID,Assembly>(); senders.put(sender,rows); }
            Assembly assembly=rows.get(frame.id);
            if(assembly==null) {
                if(rows.size()>=4 || size()>=16) {
                    if(rows.isEmpty()) senders.remove(sender);
                    throw new BridgeException("busy","QA fragment assembly capacity reached.");
                }
                assembly=new Assembly(frame.total,now); rows.put(frame.id,assembly);
            }
            if(assembly.bytes.length!=frame.total) { remove(sender,frame.id); throw new BridgeException("invalid_packet","QA fragment total changed."); }
            int index=frame.offset/CHUNK_BYTES;
            if(assembly.parts.get(index)) {
                for(int i=0;i<frame.bytes.length;i++) if(assembly.bytes[frame.offset+i]!=frame.bytes[i]) {
                    remove(sender,frame.id); throw new BridgeException("invalid_packet","Conflicting duplicate QA fragment.");
                }
                return null;
            }
            System.arraycopy(frame.bytes,0,assembly.bytes,frame.offset,frame.bytes.length);
            assembly.parts.set(index); assembly.received+=frame.bytes.length;
            if(assembly.received!=assembly.bytes.length) return null;
            remove(sender,frame.id); JsonObject value=RemoteReadProtocol.decode(assembly.bytes,bound);
            if(!frame.id.toString().equals(RemoteReadProtocol.uuid(value,"requestId")))
                throw new BridgeException("invalid_packet","QA fragment request binding changed.");
            return value;
        }
        synchronized int size() { int count=0; for(Map<UUID,Assembly> rows:senders.values()) count+=rows.size(); return count; }
        synchronized void clear(Object sender) { senders.remove(sender); }
        synchronized void clear() { senders.clear(); }
        synchronized void expire(long now) {
            Iterator<Map<UUID,Assembly>> parents=senders.values().iterator();
            while(parents.hasNext()) {
                Map<UUID,Assembly> rows=parents.next(); rows.values().removeIf(value -> now-value.created>IDLE_NANOS);
                if(rows.isEmpty()) parents.remove();
            }
        }
        private void remove(Object sender,UUID id) { Map<UUID,Assembly> rows=senders.get(sender); rows.remove(id); if(rows.isEmpty()) senders.remove(sender); }
        private static final class Assembly {
            final byte[] bytes; final BitSet parts=new BitSet(); final long created; int received;
            Assembly(int total,long now) { bytes=new byte[total]; created=now; }
        }
    }
}
