package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.common.dialogue.AgentDialogueMailbox;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** Local selection is persistent intent, never proof of current game/server authority. */
public final class OperationPolicies {
    static final String OPERATION = "bridge.policy";
    static final int MAX_BYTES = 262144;
    private Path path;
    private byte[] saved;
    private JsonObject config = defaults();
    private boolean available = true;
    private String scope = "", label = "menu";
    private volatile long revision;

    static JsonObject selection() {
        JsonObject value = new JsonObject(); value.addProperty("mode", "survival"); value.addProperty("movement", "path");
        value.addProperty("readOnly", false); value.addProperty("terrainChanges", false); value.addProperty("qa", "auto"); return value;
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject defaults() {
        JsonObject value = new JsonObject(), groups = new JsonObject(); value.addProperty("version", 1);
        String[] ids = {"survival", "survival_efficient", "creative", "full"}, labels = {"生存", "生存高效", "创造", "完全支配"};
        for (int i = 0; i < ids.length; i++) {
            JsonObject group = new JsonObject(); group.addProperty("label", labels[i]);
            group.addProperty("machineRead", i >= 1); group.addProperty("supply", i >= 2); group.addProperty("direct", i >= 3);
            group.addProperty("qa", new String[]{"off", "observe", "test", "manage"}[i]);
            groups.add(ids[i], group);
        }
        value.add("groups", groups); value.add("defaults", selection()); value.add("scopes", new JsonObject()); return value;
    }
    public void initialize(Path file) { path = file; reload(); }
    public void reload() {
        available = false; revision++;
        try {
            if (path == null) throw new IllegalStateException();
            if (!Files.exists(path)) { JsonObject next = defaults(); saved = LocalJsonConfig.write(path, null, next, MAX_BYTES); config = next; }
            else {
                byte[] bytes = LocalJsonConfig.read(path, MAX_BYTES);
                JsonObject next = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                addQaDefaults(next); validate(next); config = next; saved = bytes;
            }
            available = true;
        } catch (Exception failure) { throw new BridgeException("policy_config_unavailable", "Policy config is invalid or unavailable. Correct policies.json and use /mcb policy reload."); }
    }
    public static String mode(String value) {
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (!Arrays.asList("survival", "survival_efficient", "creative", "full").contains(key)) throw new BridgeException("invalid_args", "Unknown operation policy.");
        return key;
    }
    public static String movement(String value) {
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (!"path".equals(key) && !"tp".equals(key)) throw new BridgeException("invalid_args", "Movement policy must be path or tp.");
        return key;
    }
    public static String endpoint(String address) {
        String value = address.trim(), host; int port = 25565;
        try {
            if (value.startsWith("[")) {
                int end = value.indexOf(']'); if (end < 2) throw new IllegalArgumentException();
                host = value.substring(0, end + 1).toLowerCase(Locale.ROOT);
                if (end + 1 < value.length()) { if (value.charAt(end + 1) != ':') throw new IllegalArgumentException(); port = Integer.parseInt(value.substring(end + 2)); }
                if (!host.matches("\\[[0-9a-f:.]+\\]")) throw new IllegalArgumentException();
            } else {
                int colon = value.lastIndexOf(':');
                if (colon >= 0) {
                    if (value.indexOf(':') != colon) throw new IllegalArgumentException();
                    port = Integer.parseInt(value.substring(colon + 1)); value = value.substring(0, colon);
                }
                host = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                if (host.isEmpty() || host.length() > 253) throw new IllegalArgumentException();
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException();
            return host + ":" + port;
        } catch (RuntimeException failure) { throw new BridgeException("invalid_args", "Invalid server address for policy scope."); }
    }
    public void bind(String key, String display) {
        if (!scope.equals(key)) { scope = key; revision++; }
        label = display;
    }
    public long revision() { return revision; }
    String scope() { return scope; }
    public JsonObject selected() {
        if (!available) return selection();
        JsonObject scopes = config.getAsJsonObject("scopes");
        return (scopes.has(scope) ? scopes.getAsJsonObject(scope) : config.getAsJsonObject("defaults")).deepCopy();
    }
    public boolean permits(String capability) {
        return available && config.getAsJsonObject("groups").getAsJsonObject(selected().get("mode").getAsString()).get(capability).getAsBoolean();
    }
    public String qaLevel() {
        if (!available) return "off";
        JsonObject choice = selected();
        String maximum = config.getAsJsonObject("groups").getAsJsonObject(choice.get("mode").getAsString()).get("qa").getAsString();
        String requested = choice.get("qa").getAsString();
        return "auto".equals(requested) || QaPolicy.rank(requested) > QaPolicy.rank(maximum) ? maximum : requested;
    }
    private static void addQaDefaults(JsonObject value) {
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("groups").entrySet()) {
            JsonObject group = entry.getValue().getAsJsonObject();
            if (!group.has("qa")) group.addProperty("qa", bool(group, "direct") ? "manage" : bool(group, "supply") ? "test" : bool(group, "machineRead") ? "observe" : "off");
        }
        if (!value.getAsJsonObject("defaults").has("qa")) value.getAsJsonObject("defaults").addProperty("qa", "auto");
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject("scopes").entrySet())
            if (!entry.getValue().getAsJsonObject().has("qa")) entry.getValue().getAsJsonObject().addProperty("qa", "auto");
    }
    public JsonObject inspect() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("available", available);
        value.addProperty("revision", revision); value.addProperty("scopeKind", scope.startsWith("sp:") ? "singleplayer" : scope.startsWith("mp:") ? "server" : "menu");
        value.addProperty("scopeLabel", label); value.addProperty("scopeConfigured", config.getAsJsonObject("scopes").has(scope));
        value.add("selected", selected()); value.add("groups", config.get("groups").deepCopy()); return value;
    }
    public void update(JsonObject args) {
        if (!available) throw new BridgeException("policy_config_unavailable", "Fix and reload policies.json before editing.");
        String target = args.has("scope") ? args.get("scope").getAsString() : "current";
        if (!"current".equals(target) && !"defaults".equals(target)) throw new BridgeException("invalid_args", "scope must be current or defaults.");
        boolean global = "defaults".equals(target);
        if (!global && scope.isEmpty()) throw new BridgeException("world_unavailable", "Join a world/server or explicitly edit defaults.");
        JsonObject next = config.deepCopy(), choice = global ? next.getAsJsonObject("defaults").deepCopy() : selected();
        boolean changed = false;
        for (String key : new String[]{"mode", "movement", "readOnly", "terrainChanges", "qa"}) if (args.has(key)) {
            if ("mode".equals(key)) choice.addProperty(key, mode(args.get(key).getAsString()));
            else if ("qa".equals(key)) choice.addProperty(key, QaPolicy.level(args.get(key).getAsString(), true));
            else if ("movement".equals(key)) choice.addProperty(key, movement(args.get(key).getAsString()));
            else { bool(args, key); choice.add(key, args.get(key)); }
            changed = true;
        }
        if (!changed) throw new BridgeException("invalid_args", "Set at least one policy option.");
        String maximum = next.getAsJsonObject("groups").getAsJsonObject(choice.get("mode").getAsString()).get("qa").getAsString();
        if (args.has("qa") && !"auto".equals(choice.get("qa").getAsString()) && QaPolicy.rank(choice.get("qa").getAsString()) > QaPolicy.rank(maximum))
            throw new BridgeException("policy_denied", "QA selection exceeds the current mode/group ceiling.");
        if (global) next.add("defaults", choice); else next.getAsJsonObject("scopes").add(scope, choice);
        validate(next); byte[] bytes = LocalJsonConfig.write(path, saved, next, MAX_BYTES);
        config = next; saved = bytes; revision++;
    }
    static boolean bool(JsonObject value, String key) {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new BridgeException("invalid_args", key + " must be boolean.");
        return item.getAsBoolean();
    }
    private static void validateSelection(JsonObject value) {
        if (!mode(value.get("mode").getAsString()).equals(value.get("mode").getAsString())
                || !movement(value.get("movement").getAsString()).equals(value.get("movement").getAsString())) throw new IllegalArgumentException();
        bool(value, "readOnly"); bool(value, "terrainChanges");
        if (!QaPolicy.level(value.get("qa").getAsString(), true).equals(value.get("qa").getAsString())) throw new IllegalArgumentException();
    }
    private static void validate(JsonObject value) {
        if (value.get("version").getAsInt() != 1) throw new IllegalArgumentException();
        JsonObject limits = defaults().getAsJsonObject("groups"), groups = value.getAsJsonObject("groups");
        for (Map.Entry<String, JsonElement> entry : limits.entrySet()) {
            JsonObject group = groups.getAsJsonObject(entry.getKey()); AgentDialogueMailbox.text(group.get("label").getAsString(), 24);
            for (String key : new String[]{"machineRead", "supply", "direct"})
                if (bool(group, key) && !entry.getValue().getAsJsonObject().get(key).getAsBoolean()) throw new IllegalArgumentException("Policy exceeds group ceiling");
            if (QaPolicy.rank(group.get("qa").getAsString()) > QaPolicy.rank(entry.getValue().getAsJsonObject().get("qa").getAsString()))
                throw new IllegalArgumentException("QA policy exceeds group ceiling");
        }
        validateSelection(value.getAsJsonObject("defaults"));
        JsonObject scopes = value.getAsJsonObject("scopes");
        if (scopes.entrySet().size() > 1024) throw new IllegalArgumentException();
        for (Map.Entry<String, JsonElement> entry : scopes.entrySet()) {
            if (entry.getKey().length() > 512 || !(entry.getKey().startsWith("sp:") || entry.getKey().startsWith("mp:"))) throw new IllegalArgumentException();
            validateSelection(entry.getValue().getAsJsonObject());
        }
    }
}
