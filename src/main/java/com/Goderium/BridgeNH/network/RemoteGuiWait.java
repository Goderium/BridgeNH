package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.client.gui.GuiObservationWait;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.concurrent.CompletionException;

/** One client-owner wait's bounded remote samples; never blocks a game tick or replays GUI input. */
public final class RemoteGuiWait {
    private final String connection;
    private volatile RemoteReadMailbox.Pending pending;
    private JsonObject sample;
    private long captured, nextSample;
    private boolean resyncRequested;
    public RemoteGuiWait() {
        if (!RemoteReadClient.INSTANCE.operatorReady()) throw new BridgeException("permission_denied","Remote GUI synchronization needs a current authenticated server operator connection.");
        connection=RemoteReadClient.INSTANCE.connectionId();
    }
    public void check() { RemoteReadClient.INSTANCE.requireCurrent(connection); }
    public synchronized void cancel() { if (pending!=null) RemoteReadClient.INSTANCE.mailbox.cancel(pending); pending=null; }
    public synchronized JsonObject read(GuiObservationWait.ContainerEvidence local,JsonObject diagnostic) {
        check(); long now=System.nanoTime();
        if (pending!=null && pending.future.isDone()) {
            try { sample=RemoteReadClient.INSTANCE.accept(pending,pending.future.join(),connection); captured=pending.started; }
            catch (CompletionException error) {
                Throwable cause=error.getCause(); if (cause instanceof RuntimeException) throw (RuntimeException)cause;
                throw new BridgeException("adapter_error","Remote container sample failed.");
            } finally { cancel(); }
        }
        JsonObject expected=local.summary();
        boolean fresh=sample!=null && now-captured<=1000000000L;
        int displayInitializations = !fresh ? -1 : GuiObservationWait.ContainerEvidence.sameSummary(expected,sample) ? 0
                : local.displayInitializationCount(expected,sample);
        boolean matched=displayInitializations>=0;
        diagnostic.addProperty("source","remote_server_tick"); diagnostic.addProperty("serverSampleAvailable",sample!=null);
        diagnostic.addProperty("reason",matched ? "synchronized" : fresh ? "slot_contents_pending" : "server_sample_pending");
        diagnostic.addProperty("phantomDisplayInitializedCount",Math.max(0,displayInitializations));
        if (!matched && pending==null && now>=nextSample) {
            JsonObject args=new JsonObject(); args.addProperty("windowId",local.window); args.addProperty("containerClass",local.className);
            if (sample!=null) {
                args.add("containerToken",sample.get("containerToken"));
                if (!resyncRequested && local.window==JsonData.integer(sample,"windowId",0,255)
                        && local.className.equals(JsonData.string(sample,"containerClass"))) {
                    args.addProperty("resync",true); resyncRequested=true;
                }
            }
            pending=RemoteReadClient.INSTANCE.begin("gui.container",args,connection);
            nextSample=now+250000000L;
        }
        if (!matched) return null;
        JsonObject sync=local.synchronization("client_and_remote_server_owner_threads",now-captured,
                sample.get("nativeResyncSubmitted").getAsBoolean(),displayInitializations);
        sync.addProperty("comparison",displayInitializations==0 ? "sha256_canonical_full_nbt" : "sha256_full_nbt_with_verified_phantom_display_initialization"); sync.addProperty("connectionId",connection);
        sync.add("containerToken",sample.get("containerToken")); sync.add("serverTick",sample.get("serverTick"));
        return sync;
    }
}
