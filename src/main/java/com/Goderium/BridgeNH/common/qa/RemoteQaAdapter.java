package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Connection-owned public QA adapter. The server token, never a caller-selected session, owns runs. */
public final class RemoteQaAdapter {
    private final Set<String> actions=new HashSet<String>();
    private String runtime;
    public final HorizonQaAdapter nativeQa=new HorizonQaAdapter();
    public static boolean action(String operation) {
        return "qa.run".equals(operation) || "qa.command".equals(operation) || "qa.cancel".equals(operation);
    }
    public JsonObject prepare(String operation,JsonObject envelope,String requestId,String worldName,String folder) {
        if(!RemoteReadProtocol.QA.contains(operation)) throw new BridgeException("unsupported","Unknown remote QA operation.");
        RemoteReadProtocol.keys(envelope,"clientSessionId","arguments","qaPolicy");
        if (!envelope.has("qaPolicy") || !envelope.get("qaPolicy").isJsonObject())
            throw new BridgeException("qa_policy_unavailable", "Matching client/server QA policy support is required.");
        QaPolicy.validateContext(envelope.getAsJsonObject("qaPolicy"));
        String session=RemoteReadProtocol.uuid(envelope,"clientSessionId");
        if(runtime!=null && !runtime.equals(session)) throw new BridgeException("stale_session","QA runtime changed within this server connection; reconnect.");
        if(!envelope.has("arguments") || !envelope.get("arguments").isJsonObject()) throw new BridgeException("invalid_args","QA arguments must be an object.");
        JsonObject args=new JsonParser().parse(envelope.get("arguments").toString()).getAsJsonObject();
        if(action(operation)) {
            String expected=CatalogData.text(args,"expectedWorld",128,"");
            if(expected.isEmpty() || !expected.equals(worldName) && !expected.equals(folder))
                throw new BridgeException("stale_world","QA action expectedWorld differs from the authoritative server.");
            if(actions.size()>=1024) throw new BridgeException("data_limit","Connection QA action ledger is full; reconnect before a new run.");
            if(!actions.add(requestId)) throw new BridgeException("duplicate_action","QA action was already submitted; inspect its result instead of replaying it.");
        }
        runtime=session; return args;
    }
    public JsonObject result(JsonObject value,String owner,String connection,String actor) {
        JsonObject copy=new JsonParser().parse(value.toString()).getAsJsonObject();
        normalize(copy,owner,runtime);
        copy.addProperty("connectionId",connection); copy.addProperty("playerUuid",actor);
        copy.addProperty("transportSource","remote_server_tick"); copy.addProperty("computationSide","local_client");
        if(copy.has("boundedStatusWait") && copy.get("boundedStatusWait").getAsBoolean()) copy.addProperty("maxStatusWaitMillis",5000);
        return copy;
    }
    private static void normalize(JsonElement value,String owner,String runtime) {
        if(value.isJsonObject()) {
            for(Map.Entry<String,JsonElement> entry:value.getAsJsonObject().entrySet()) {
                if("sessionId".equals(entry.getKey()) && entry.getValue().isJsonPrimitive() && owner.equals(entry.getValue().getAsString()))
                    entry.setValue(new com.google.gson.JsonPrimitive(runtime));
                else normalize(entry.getValue(),owner,runtime);
            }
        } else if(value.isJsonArray()) for(JsonElement row:value.getAsJsonArray()) normalize(row,owner,runtime);
    }
}
