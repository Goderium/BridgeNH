package com.Goderium.BridgeNH.network;

import com.Goderium.BridgeNH.common.qa.RemoteQaAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Pure connection-bound reply lifecycle. Futures never execute world access callbacks. */
public final class RemoteReadMailbox {
    public static final int MAX_PENDING=4;
    private final Map<String,Pending> pending=new LinkedHashMap<String,Pending>();
    synchronized Pending submit(JsonObject request,Object handler,long now) {
        expire(now); if(pending.size()>=MAX_PENDING) throw new BridgeException("busy","Remote bridge has four pending reads.");
        String id=request.get("requestId").getAsString();
        if(pending.containsKey(id)) throw new BridgeException("invalid_args","Duplicate pending request ID.");
        Pending value=new Pending(request,handler,now); pending.put(id,value); return value;
    }
    synchronized boolean receive(JsonObject response,Object handler,long now) {
        expire(now);
        if(!response.has("requestId")) return false;
        Pending value=pending.get(response.get("requestId").getAsString());
        if(value==null || value.handler!=handler || !same(value.request,response,"nonce") || !same(value.request,response,"operation")
                || !same(value.request,response,"dimension") || !response.has("version") || response.get("version").getAsInt()!=1) return false;
        if(!response.has("ok") || !response.get("ok").isJsonPrimitive() || !response.get("ok").getAsJsonPrimitive().isBoolean()) return false;
        if(response.get("ok").getAsBoolean() && !"hello".equals(value.request.get("operation").getAsString())
                && !same(value.request,response,"connectionId")) return false;
        pending.remove(response.get("requestId").getAsString());
        if(!response.get("ok").getAsBoolean()) value.future.completeExceptionally(new BridgeException(
                response.has("code") ? response.get("code").getAsString() : "adapter_error",
                response.has("message") ? response.get("message").getAsString() : "Remote server refused the read."));
        else { response.addProperty("roundTripMillis",Math.max(0,(now-value.started)/1000000.0)); value.future.complete(response); }
        return true;
    }
    synchronized void expire(long now) {
        java.util.Iterator<Pending> iterator=pending.values().iterator();
        while(iterator.hasNext()) { Pending value=iterator.next(); if(now-value.started>6500000000L) {
            iterator.remove(); value.future.completeExceptionally(new BridgeException("timeout",RemoteQaAdapter.action(value.request.get("operation").getAsString()) ? "QA reply expired after submission; outcome is unknown. Do not replay." : "Remote read expired; no mutation was sent."));
        } }
    }
    public synchronized void cancel(Pending value) {
        pending.values().remove(value); value.future.completeExceptionally(new BridgeException("cancelled",RemoteQaAdapter.action(value.request.get("operation").getAsString()) ? "QA reply observation released; this does not cancel or replay the submitted action." : "Remote read cancelled; no mutation was sent."));
    }
    synchronized void clear() {
        for(Pending value:pending.values()) value.future.completeExceptionally(new BridgeException("stale_world","Disconnected, changed dimension, or changed server/world connection."));
        pending.clear();
    }
    synchronized int size() { return pending.size(); }
    private static boolean same(JsonObject request,JsonObject response,String key) { return request.has(key) && response.has(key) && request.get(key).equals(response.get(key)); }
    public static final class Pending {
        final JsonObject request; final Object handler; final long started; public final CompletableFuture<JsonObject> future=new CompletableFuture<JsonObject>();
        Pending(JsonObject request,Object handler,long started) { this.request=request; this.handler=handler; this.started=started; }
    }
}
