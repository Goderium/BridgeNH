package com.Goderium.BridgeNH.common.dialogue;

import com.Goderium.BridgeNH.config.AgentPermissions;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-thread mailbox. Players never choose a host thread or supply another sender's identity. */
public final class AgentDialogueMailbox {
    public static final String OPERATION = "agent.dialogue";
    static final long LEASE_MILLIS = 60000;
    public static final String CONSOLE_ID = "00000000-0000-0000-0000-000000000000";
    interface Receipts {
        void send(String playerId, String text);
        default void debug(String text) { }
    }
    private final AgentPermissions permissions;
    private final Map<String, JsonObject> messages = new LinkedHashMap<String, JsonObject>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<String, Long>();
    private String owner = "", lease = "", thread = "";
    private long renewed;

    AgentDialogueMailbox(AgentPermissions permissions) { this.permissions = permissions; }
    boolean owns(String playerId) { return !lease.isEmpty() && owner.equals(playerId); }

    public static String text(String value, int maximum) {
        if (value == null || value.trim().isEmpty() || value.length() > maximum)
            throw new BridgeException("invalid_args", "Message is empty or too long (max " + maximum + ").");
        for (int i = 0; i < value.length(); i++)
            if (Character.isISOControl(value.charAt(i)) || value.charAt(i) == '\u00a7')
                throw new BridgeException("invalid_args", "Control and formatting characters are not accepted.");
        return value.trim();
    }

    void expire(long now, Receipts receipts) {
        if (!lease.isEmpty() && !permissions.canBind(owner)) close(receipts, "Agent permission was revoked.");
        if (!lease.isEmpty() && now - renewed >= LEASE_MILLIS) close(receipts, "Agent connection expired.");
        for (JsonObject row : messages.values()) {
            String player = row.get("playerUuid").getAsString();
            if ("queued".equals(state(row)) && !consoleRow(row) && !permissions.allows(player, owns(player), "send"))
                finish(row, "failed", "Message permission was revoked before delivery.", receipts);
            else if ("queued".equals(state(row)) && now - row.get("createdAt").getAsLong() >= 3600000)
                finish(row, "failed", "Message expired before delivery.", receipts);
        }
    }

    void close(Receipts receipts, String reason) {
        for (JsonObject row : messages.values()) {
            if ("queued".equals(state(row))) finish(row, "failed", reason, receipts);
            else if ("dispatching".equals(state(row)) || "replying".equals(state(row))) finish(row, "unknown", "Delivery uncertain; not resent. " + reason, receipts);
        }
        owner = ""; lease = ""; thread = "";
    }

    JsonObject submit(String playerId, String playerName, String mode, String content, long now, Receipts receipts) {
        return submit(playerId, playerName, mode, content, now, receipts, false);
    }
    JsonObject submit(String playerId, String playerName, String mode, String content, long now, Receipts receipts, boolean console) {
        expire(now, receipts);
        requireSender(playerId, console);
        if (lease.isEmpty()) throw new BridgeException("unavailable", "No agent is connected. Ask the agent to enable mcb_dialogue.");
        if (!"say".equals(mode) && !"steer".equals(mode)) throw new BridgeException("invalid_args", "Use say or steer.");
        content = text(content, 1024);
        if (cooldowns.containsKey(playerId) && now - cooldowns.get(playerId) < 1000)
            throw new BridgeException("rate_limit", "Wait one second before sending another message.");
        int pending = 0;
        for (JsonObject row : messages.values()) if (pending(row)) pending++;
        if (pending >= 32) throw new BridgeException("queue_full", "Agent inbox is full; message was not accepted.");
        while (messages.size() >= 128) {
            String remove = null;
            for (Map.Entry<String, JsonObject> row : messages.entrySet())
                if (!pending(row.getValue())) { remove = row.getKey(); break; }
            if (remove == null) throw new BridgeException("queue_full", "Agent inbox is full.");
            messages.remove(remove);
        }
        if (cooldowns.size() >= 128 && !cooldowns.containsKey(playerId)) cooldowns.remove(cooldowns.keySet().iterator().next());
        cooldowns.put(playerId, now);
        JsonObject row = new JsonObject();
        row.addProperty("id", UUID.randomUUID().toString()); row.addProperty("playerUuid", playerId);
        row.addProperty("playerName", playerName); row.addProperty("mode", mode); row.addProperty("text", content);
        row.addProperty("permissionGroup", console ? "console" : permissions.effective(playerId, owns(playerId)));
        row.addProperty("senderKind", console ? "console" : "player");
        row.addProperty("state", "queued"); row.addProperty("createdAt", now); row.addProperty("leaseId", lease);
        messages.put(row.get("id").getAsString(), row);
        receipts.send(playerId, "[MCB " + shortId(row) + "] 已接收, 等待 Agent 处理 / " + mode + ": " + content);
        receipts.debug("[MCB debug] " + row.get("id").getAsString() + " queued / " + playerName + " / " + row.get("permissionGroup").getAsString());
        return row.deepCopy();
    }

    JsonObject perform(String playerId, JsonObject args, long now, Receipts receipts) {
        expire(now, receipts);
        String action = JsonData.string(args, "action");
        RemoteReadProtocol.keys(args, "action", "leaseId", "threadId", "messageId", "state", "text");
        String requestedLease = RemoteReadProtocol.uuid(args, "leaseId");
        if ("bind".equals(action)) {
            if (!permissions.canBind(playerId)) throw new BridgeException("permission_denied", "Agent account must be assigned group agent in server config or by /mcb group set before binding. OP alone does not grant MCB permissions.");
            String target = RemoteReadProtocol.uuid(args, "threadId");
            if (!lease.isEmpty() && (!owner.equals(playerId) || !lease.equals(requestedLease) || !thread.equals(target)))
                throw new BridgeException("busy", "Another agent already owns this server inbox.");
            owner = playerId; lease = requestedLease; thread = target; renewed = now;
        } else {
            if (lease.isEmpty() || !owner.equals(playerId) || !lease.equals(requestedLease))
                throw new BridgeException("stale_session", "Agent inbox lease is no longer owned by this connection.");
            renewed = now;
            if ("close".equals(action)) close(receipts, "Agent disconnected.");
            else if ("prepare_chat_reply".equals(action)) {
                JsonObject row = messages.get(RemoteReadProtocol.uuid(args, "messageId"));
                if (row == null || !requestedLease.equals(row.get("leaseId").getAsString()))
                    throw new BridgeException("not_found", "Message does not belong to this lease.");
                String content = text(JsonData.string(args, "text"), 100);
                if (content.startsWith("/")) throw new BridgeException("invalid_args", "A public reply must be chat text, not a command.");
                if (row.has("chatReply")) {
                    if (!content.equals(row.get("chatReply").getAsString())) throw new BridgeException("invalid_state", "A different public reply was already claimed.");
                    JsonObject result = summary(); result.addProperty("claimed", false); return result;
                }
                if (!"dispatching".equals(state(row)) && !"delivered".equals(state(row)) && !"unknown".equals(state(row)))
                    throw new BridgeException("invalid_state", "Message is not awaiting an agent reply.");
                row.addProperty("chatReply", content);
                row.addProperty("state", "replying");
                JsonObject result = summary(); result.addProperty("claimed", true); return result;
            }
            else if ("receipt".equals(action)) {
                JsonObject row = messages.get(RemoteReadProtocol.uuid(args, "messageId"));
                if (row == null || !requestedLease.equals(row.get("leaseId").getAsString()))
                    throw new BridgeException("not_found", "Message does not belong to this lease.");
                String next = JsonData.string(args, "state"), content = text(JsonData.string(args, "text"), 4096);
                if (next.equals(state(row)) && row.has("receipt") && content.equals(row.get("receipt").getAsString())) return summary();
                if (("replied".equals(state(row)) || "replying".equals(state(row))) && "delivered".equals(next)) return summary();
                if (row.has("chatReply") && "replied".equals(next) && !content.equals(row.get("chatReply").getAsString()))
                    throw new BridgeException("invalid_state", "Public reply acknowledgement must match its claimed text.");
                if (!("queued".equals(state(row)) && ("dispatching".equals(next) || "failed".equals(next))
                        || "dispatching".equals(state(row)) && ("delivered".equals(next) || "failed".equals(next) || "unknown".equals(next) || "replied".equals(next))
                        || ("delivered".equals(state(row)) || "unknown".equals(state(row))) && "replied".equals(next)
                        || "replying".equals(state(row)) && ("replied".equals(next) || "unknown".equals(next))))
                    throw new BridgeException("invalid_state", "Receipt transition is not allowed; message was not replayed.");
                finish(row, next, content, receipts);
            } else if (!"poll".equals(action) && !"status".equals(action)) throw new BridgeException("invalid_args", "Unknown inbox action.");
        }
        JsonObject result = summary();
        if ("poll".equals(action)) {
            JsonArray rows = new JsonArray();
            int normal = 0, guidance = 0;
            for (JsonObject row : messages.values()) if (lease.equals(row.get("leaseId").getAsString()) && "queued".equals(state(row))) {
                String sender = row.get("playerUuid").getAsString();
                row.addProperty("permissionGroup", consoleRow(row) ? "console" : permissions.effective(sender, owns(sender)));
                if ("say".equals(row.get("mode").getAsString())) { if (normal >= 4) continue; normal++; }
                else { if (guidance >= 4) continue; guidance++; }
                rows.add(row.deepCopy());
            }
            result.add("messages", rows);
        }
        return result;
    }

    JsonObject summary() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("enabled", !lease.isEmpty());
        result.addProperty("leaseId", lease); result.addProperty("threadId", thread); result.addProperty("records", messages.size());
        return result;
    }

    java.util.List<String> playerStatus(String playerId, String id, long now, Receipts receipts) {
        return playerStatus(playerId, id, now, receipts, false);
    }
    java.util.List<String> playerStatus(String playerId, String id, long now, Receipts receipts, boolean console) {
        expire(now, receipts);
        requireSender(playerId, console);
        java.util.List<String> result = new ArrayList<String>();
        for (JsonObject row : messages.values()) if (playerId.equals(row.get("playerUuid").getAsString())
                && (id.isEmpty() || row.get("id").getAsString().equals(id) || shortId(row).equals(id))) {
            result.add("[MCB " + shortId(row) + "] " + state(row) + ": " + row.get("text").getAsString()
                    + (row.has("receipt") && (!"delivered".equals(state(row)) && !"dispatching".equals(state(row))
                            || console || permissions.debugEnabled(playerId, owns(playerId))) ? " | " + row.get("receipt").getAsString() : ""));
            if (result.size() > 4) result.remove(0);
        }
        if (result.isEmpty()) result.add(lease.isEmpty() ? "[MCB] Agent 未连接。" : "[MCB] Agent 已连接；尚无对应消息。");
        return result;
    }
    private void requireSender(String playerId, boolean console) {
        if (console != CONSOLE_ID.equals(playerId)) throw new BridgeException("permission_denied", "Server console identity is reserved.");
        permissions.require(playerId, owns(playerId), console, "send");
    }
    private static boolean consoleRow(JsonObject row) { return row.has("senderKind") && "console".equals(row.get("senderKind").getAsString()); }
    private static String state(JsonObject row) { return row.get("state").getAsString(); }
    private static boolean pending(JsonObject row) { return "queued".equals(state(row)) || "dispatching".equals(state(row)) || "replying".equals(state(row)); }
    private static String shortId(JsonObject row) { return row.get("id").getAsString().substring(0, 8); }
    private static void finish(JsonObject row, String state, String content, Receipts receipts) {
        row.addProperty("state", state); row.addProperty("receipt", content);
        receipts.debug("[MCB debug] " + row.get("id").getAsString() + " " + state);
        if ("delivered".equals(state)) {
            org.apache.logging.log4j.LogManager.getLogger("MCB").info("[MCB {}] delivered: {}", row.get("id").getAsString(), content);
        } else if ("replied".equals(state) && row.has("chatReply")) {
            org.apache.logging.log4j.LogManager.getLogger("MCB").info("[MCB {}] replied (server chat submitted): {}", row.get("id").getAsString(), content);
        } else if (!"dispatching".equals(state)) {
            receipts.send(row.get("playerUuid").getAsString(), "[MCB " + shortId(row) + "] " + state + ": " + content);
        }
    }
}
