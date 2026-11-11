package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.IOException;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraftforge.fluids.FluidStack;

/** Fixed framework compatibility only. Copies and resends run on the respective container owner thread. */
public final class ContainerSyncAdapter {
    private static final String MUI2 = "com.cleanroommc.modularui.screen.ModularContainer";
    private static final String FLUID_HANDLER = "com.cleanroommc.modularui.value.sync.FluidSlotSyncHandler";

    /** Fluid widgets are registered value handlers, never vanilla Slot/phantomSlots entries. */
    static Map<String,Object> phantomFluids(Container container) {
        Map<String,Object> result=new TreeMap<String,Object>();
        if(!ExpandedGuiApi.is(container,MUI2)) return result;
        Object manager=ExpandedGuiApi.call(container,"getSyncManager");
        Object panels=ExpandedGuiApi.field(manager,"com.cleanroommc.modularui.value.sync.ModularSyncManager","panelSyncManagerMap");
        if(!(panels instanceof Map<?,?>) || ((Map<?,?>)panels).size()>32)
            throw new BridgeException("unsupported","Registered native sync panels are unavailable or exceed the observation bound.");
        int visited=0;
        for(Map.Entry<?,?> panel:((Map<?,?>)panels).entrySet()) {
            if(!(panel.getKey() instanceof String) || panel.getValue()==null
                    || ExpandedGuiApi.call(manager,"getPanelSyncManager",panel.getKey())!=panel.getValue()
                    || ExpandedGuiApi.call(panel.getValue(),"getContainer")!=container)
                throw new BridgeException("unsupported","Native fluid sync panel does not belong to this actual container.");
            Object handlers=ExpandedGuiApi.field(panel.getValue(),"com.cleanroommc.modularui.value.sync.PanelSyncManager","syncHandlers");
            if(!(handlers instanceof Map<?,?>)) throw new BridgeException("unsupported","Registered native handler enumeration is unavailable.");
            for(Map.Entry<?,?> entry:((Map<?,?>)handlers).entrySet()) {
                if(++visited>2048) throw new BridgeException("result_too_large","Native handler enumeration exceeds the bounded observation policy.");
                Object handler=entry.getValue();
                if(!ExpandedGuiApi.is(handler,FLUID_HANDLER) || !Boolean.TRUE.equals(ExpandedGuiApi.call(handler,"isPhantom"))) continue;
                if(!(entry.getKey() instanceof String) || ExpandedGuiApi.call(panel.getValue(),"getSyncHandlerFromMapKey",entry.getKey())!=handler
                        || ExpandedGuiApi.call(handler,"getSyncManager")!=panel.getValue()
                        || !Boolean.TRUE.equals(ExpandedGuiApi.call(handler,"isRegistered",panel.getValue())))
                    throw new BridgeException("unsupported","Phantom fluid handler identity is not registered in the actual panel.");
                String key=panel.getKey()+":"+entry.getKey();
                if(key.length()>256 || result.size()>=512 || result.put(key,handler)!=null)
                    throw new BridgeException("result_too_large","Native phantom fluid keys exceed the bounded observation policy.");
            }
        }
        return result;
    }
    static FluidStack fluidValue(Object handler) {
        Object value=ExpandedGuiApi.call(handler,"getValue");
        if(value!=null && (!(value instanceof FluidStack) || ((FluidStack)value).getFluid()==null))
            throw new BridgeException("unsupported","Native phantom fluid value cannot be read completely.");
        return value==null ? null : ((FluidStack)value).copy();
    }

    static List<Slot> phantomSlots(Container container) {
        List<Slot> result = new ArrayList<Slot>();
        if (!ExpandedGuiApi.is(container, MUI2)) return result;
        // MUI2 has no public getter for registered phantom slots. No field is written or caller-selected.
        Object raw = ExpandedGuiApi.field(container, MUI2, "phantomSlots");
        if (!(raw instanceof List<?>) || ((List<?>) raw).size() > 512)
            throw new BridgeException("unsupported", "Registered phantom slot layout is unavailable or exceeds its bound.");
        for (Object entry : (List<?>) raw) {
            if (!(entry instanceof Slot) || !Boolean.TRUE.equals(ExpandedGuiApi.call(entry,"isPhantom")))
                throw new BridgeException("unsupported", "Registered phantom slot identity is unavailable.");
            result.add((Slot)entry);
        }
        return result;
    }
    static String phantomKey(Slot slot) {
        Object handler = ExpandedGuiApi.call(slot,"getSyncHandler"), manager = ExpandedGuiApi.call(handler,"getSyncManager");
        if (!Boolean.TRUE.equals(ExpandedGuiApi.call(handler,"isRegistered",manager)))
            throw new BridgeException("unsupported", "Phantom handler is not registered in this native panel.");
        return ExpandedGuiApi.call(manager,"getPanelName") + ":" + ExpandedGuiApi.call(handler,"getKey");
    }
    public static void resend(EntityPlayerMP player, Container container, GuiObservationWait.ContainerEvidence expected) {
        if (player.openContainer != container || expected == null
                || expected.owner != container || expected.window != container.windowId || !expected.className.equals(container.getClass().getName()))
            throw new BridgeException("stale_window", "Container changed before the single native synchronization request.");
        List<Slot> ghosts = phantomSlots(container);
        Map<String,Object> fluids=phantomFluids(container);
        if (container.inventorySlots.size() != expected.slots.length || !java.util.Arrays.equals(expected.ghostKeys,
                ghosts.stream().map(ContainerSyncAdapter::phantomKey).toArray(String[]::new))
                || !java.util.Arrays.equals(expected.fluidKeys,fluids.keySet().toArray(new String[0])))
            throw new BridgeException("stale_window", "Native slot layout changed before the single synchronization request.");
        List<S2FPacketSetSlot> packets=new ArrayList<S2FPacketSetSlot>();
        for(int i=0;i<container.inventorySlots.size();i++)
            packets.add(new S2FPacketSetSlot(container.windowId,i,(ItemStack)container.inventoryItemStacks.get(i)));
        packets.add(new S2FPacketSetSlot(-1,-1,player.inventory.getItemStack()));
        // Validate every serialized stack before any packet. Never aggregate a large inventory into one 32k-limited message.
        int bytes=0;
        for(S2FPacketSetSlot packet:packets) bytes=Math.addExact(bytes,packetBytes(packet));
        for(Slot slot:ghosts) {
            String key=phantomKey(slot);
            if(key.length()>256) throw new BridgeException("unsupported","Native phantom sync key exceeds its fixed packet allowance.");
            bytes=Math.addExact(bytes,packetBytes(new S2FPacketSetSlot(0,0,slot.getStack()))+1024);
        }
        for(Object handler:fluids.values()) {
            PacketBuffer buffer=new PacketBuffer(Unpooled.buffer());
            try {
                // Public native write serializes the full cached value without filling/draining the tank.
                ExpandedGuiApi.call(handler,"write",buffer);
                if(buffer.readableBytes()>27648) throw new BridgeException("result_too_large","One native fluid synchronization exceeds its reserved allowance.");
                bytes=Math.addExact(bytes,buffer.readableBytes()+1024);
            } finally {buffer.release();}
        }
        if(bytes>262144) throw new BridgeException("result_too_large","Native container synchronization exceeds the total bounded packet budget; no input was replayed.");
        // Packets carry authoritative contents; this does not execute clicks or mutate stock/configuration.
        for(S2FPacketSetSlot packet:packets) player.playerNetServerHandler.sendPacket(packet);
        for (Slot slot : ghosts) ExpandedGuiApi.call(ExpandedGuiApi.call(slot,"getSyncHandler"),"forceSyncItem");
        // Public notifyUpdate refreshes the cache from the tank and sends it, setSource=false: no tank mutation.
        for(Object handler:fluids.values()) ExpandedGuiApi.call(handler,"notifyUpdate");
    }
    static int packetBytes(S2FPacketSetSlot packet) {
        PacketBuffer buffer=new PacketBuffer(Unpooled.buffer());
        try {
            packet.writePacketData(buffer);
            if(buffer.readableBytes()>27648) throw new BridgeException("result_too_large","One native item synchronization exceeds the reserved 32k packet allowance.");
            return buffer.readableBytes();
        } catch(IOException error) { throw new BridgeException("unsupported","Native item synchronization serialization is unavailable."); }
        finally { buffer.release(); }
    }
}
