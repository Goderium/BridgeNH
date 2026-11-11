package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.common.dialogue.AgentDialogueMailbox;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/** Server-owned config. Minecraft OP is deliberately not an input to this policy. */
public final class AgentPermissions {
    private Path path;
    private byte[] saved;
    private JsonObject config = defaults();
    private boolean available;

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject defaults() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("defaultGroup", "guest");
        root.addProperty("opInheritsAdmin", false);
        root.addProperty("debugDefault", false);
        root.addProperty("agentRequiresBinding", true);
        JsonObject groups = new JsonObject();
        String[] ids = {"guest", "user", "admin", "agent"}, names = {"访客", "用户", "管理员", "AGENT"};
        for (int i = 0; i < ids.length; i++) {
            JsonObject group = new JsonObject(); group.addProperty("label", names[i]);
            group.addProperty("send", i >= 1); group.addProperty("debug", i >= 2); group.addProperty("manage", i >= 2);
            groups.add(ids[i], group);
        }
        root.add("groups", groups); root.add("players", new JsonObject());
        return root;
    }

    public void initialize(Path file) { path = file; reload(); }

    public void reload() {
        available = false;
        try {
            if (path == null) throw new IOException("Config path unavailable");
            if (!Files.exists(path)) {
                config = defaults(); saved = null; persist(config);
            } else {
                if (Files.size(path) > 262144) throw new IOException("Config exceeds limit");
                byte[] raw = Files.readAllBytes(path);
                JsonObject next = new JsonParser().parse(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                validate(next); config = next; saved = raw;
            }
            available = true;
        } catch (IOException | RuntimeException failure) {
            throw new BridgeException("config_unavailable", "MCB permissions config is invalid or unreadable; access disabled. Fix permissions.json and reload from server console.");
        }
    }

    public static String groupId(String value) {
        String id = value.toLowerCase(java.util.Locale.ROOT);
        if (!Arrays.asList("guest", "user", "admin", "agent").contains(id))
            throw new BridgeException("invalid_args", "Use guest, user, admin or agent.");
        return id;
    }

    public static String playerId(String value) {
        try {
            String id = UUID.fromString(value).toString();
            if (!id.equalsIgnoreCase(value) || AgentDialogueMailbox.CONSOLE_ID.equals(id)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) { throw new BridgeException("invalid_args", "Use an online player name or full player UUID."); }
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key);
        return value.getAsBoolean();
    }

    private static void validate(JsonObject root) {
        if (root.get("version").getAsInt() != 1 || !"guest".equals(root.get("defaultGroup").getAsString())
                || bool(root, "opInheritsAdmin") || !bool(root, "agentRequiresBinding")) throw new IllegalArgumentException("policy");
        bool(root, "debugDefault");
        JsonObject groups = root.getAsJsonObject("groups");
        for (String id : new String[]{"guest", "user", "admin", "agent"}) {
            JsonObject group = groups.getAsJsonObject(id);
            AgentDialogueMailbox.text(group.get("label").getAsString(), 16);
            boolean send = bool(group, "send"), debug = bool(group, "debug"), manage = bool(group, "manage");
            if ("guest".equals(id) && (send || debug || manage) || "user".equals(id) && (debug || manage))
                throw new IllegalArgumentException("group privileges exceed role");
        }
        JsonObject players = root.getAsJsonObject("players");
        if (players.entrySet().size() > 2048) throw new IllegalArgumentException("too many players");
        for (Map.Entry<String, JsonElement> entry : players.entrySet()) {
            if (!playerId(entry.getKey()).equals(entry.getKey())) throw new IllegalArgumentException("UUID must be canonical");
            JsonObject player = entry.getValue().getAsJsonObject();
            groupId(player.get("group").getAsString());
            if (player.has("debug")) bool(player, "debug");
        }
    }

    public String assigned(String uuid) {
        if (!available) return "guest";
        JsonObject players = config.getAsJsonObject("players");
        return players.has(uuid) ? groupId(players.getAsJsonObject(uuid).get("group").getAsString()) : "guest";
    }

    public String effective(String uuid, boolean bound) {
        String group = assigned(uuid);
        return "agent".equals(group) && !bound ? "guest" : group;
    }

    public String label(String group) { return config.getAsJsonObject("groups").getAsJsonObject(group).get("label").getAsString(); }
    public boolean canBind(String uuid) { return available && "agent".equals(assigned(uuid)); }
    public boolean allows(String uuid, boolean bound, String capability) {
        return available && config.getAsJsonObject("groups").getAsJsonObject(effective(uuid, bound)).get(capability).getAsBoolean();
    }
    public void require(String uuid, boolean bound, boolean console, String capability) {
        if (!console && !allows(uuid, bound, capability)) throw new BridgeException("permission_denied", "MCB 权限不足, 请联系管理员或服务器后台.");
    }
    public boolean debugEnabled(String uuid, boolean bound) {
        if (!allows(uuid, bound, "debug")) return false;
        JsonObject player = config.getAsJsonObject("players").getAsJsonObject(uuid);
        return player != null && player.has("debug") ? player.get("debug").getAsBoolean() : config.get("debugDefault").getAsBoolean();
    }

    public void setGroup(String uuid, String group) {
        uuid = playerId(uuid); group = groupId(group);
        JsonObject next = config.deepCopy(), players = next.getAsJsonObject("players");
        JsonObject player = players.has(uuid) ? players.getAsJsonObject(uuid) : new JsonObject();
        player.addProperty("group", group);
        // Demotion also clears a previous debug opt-in; re-promotion must opt in again.
        player.addProperty("debug", false); players.add(uuid, player); persist(next);
    }
    public void setDebug(String uuid, boolean enabled) {
        JsonObject next = config.deepCopy(), players = next.getAsJsonObject("players");
        JsonObject player = players.has(uuid) ? players.getAsJsonObject(uuid) : new JsonObject();
        player.addProperty("group", assigned(uuid)); player.addProperty("debug", enabled);
        players.add(playerId(uuid), player); persist(next);
    }
    private void persist(JsonObject next) {
        validate(next);
        byte[] raw = LocalJsonConfig.write(path, saved, next, 262144);
        config = next; saved = raw; available = true;
    }
}
