package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.client.gui.ContainerSyncAdapter;
import com.Goderium.BridgeNH.client.gui.GuiObservationWait;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

/** Server-owned current-window observation, scoped to the authenticated connection and container object. */
final class RemoteContainerObservation {
    private Container owner;
    private String token;
    private boolean resynced;
    JsonObject read(EntityPlayerMP player,JsonObject args) {
        RemoteReadProtocol.keys(args,"windowId","containerClass","containerToken","resync");
        Container current=player.openContainer;
        if (current==null || current.windowId!=JsonData.integer(args,"windowId",0,255)
                || !current.getClass().getName().equals(JsonData.string(args,"containerClass")))
            throw new BridgeException("stale_window","Server current window does not match the observed client container.");
        if (owner!=current) { owner=current; token=UUID.randomUUID().toString(); resynced=false; }
        if (args.has("containerToken") && !token.equals(JsonData.string(args,"containerToken")))
            throw new BridgeException("stale_window","The native server container was replaced during observation.");
        GuiObservationWait.ContainerEvidence evidence=new GuiObservationWait.ContainerEvidence(current,System.nanoTime(),true);
        if (RemoteReadProtocol.booleanArg(args,"resync")) {
            if (!args.has("containerToken")) throw new BridgeException("invalid_args","Resynchronization requires this server container token.");
            if (!resynced) { ContainerSyncAdapter.resend(player,current,evidence); resynced=true; }
        }
        JsonObject result=evidence.summary(); result.addProperty("containerToken",token);
        result.addProperty("nativeResyncSubmitted",resynced); return result;
    }
}
