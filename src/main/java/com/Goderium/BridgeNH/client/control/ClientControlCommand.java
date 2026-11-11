package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.dialogue.AgentDialogueCommand;
import com.Goderium.BridgeNH.config.OperationPolicies;
import com.Goderium.BridgeNH.config.QaPolicy;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;

/** Local controls with explicit dialogue forwarding through native player chat. */
public class ClientControlCommand extends CommandBase {
    static final String HELP = "/mcb agent on|off (or /agent on|off when available), mouse on|off, unfocused on|off, background on|off, takeover, release, cancel, record on|off|status, scan 1..8 [verticalChunks], cache 1..32 [verticalChunks], cache memory 64..16384 (MiB), scan|cache status, status, help. scan/cache values are independent radii (16 blocks per chunk), stored for this session only. agent on enables mouse protection and unfocused running; agent off restores both original settings. mouse on restores manual mouse; unfocused on keeps running without focus.";
    private final BiFunction<String, Boolean, JsonObject> control;
    private final Supplier<JsonObject> status;
    private final Function<JsonObject, JsonObject> scan;
    private final Function<JsonObject, JsonObject> recording;
    private final Consumer<String> dialogue;
    private Function<JsonObject, JsonObject> policy = args -> { throw new BridgeException("unsupported", "Policy service unavailable."); };
    private Function<JsonObject, JsonObject> follow = args -> { throw new BridgeException("unsupported", "Follow service unavailable."); };

    ClientControlCommand(BiFunction<String, Boolean, JsonObject> control, Supplier<JsonObject> status) {
        this(control, status, new ScanSettings()::apply);
    }

    ClientControlCommand(BiFunction<String, Boolean, JsonObject> control, Supplier<JsonObject> status, Function<JsonObject, JsonObject> scan) {
        this(control, status, scan, args -> { throw new BridgeException("unsupported", "Recorder is unavailable."); });
    }

    ClientControlCommand(BiFunction<String, Boolean, JsonObject> control, Supplier<JsonObject> status, Function<JsonObject, JsonObject> scan, Function<JsonObject, JsonObject> recording) {
        this(control, status, scan, recording, text -> { throw new BridgeException("unavailable", "Player connection is unavailable."); });
    }

    @com.google.common.annotations.VisibleForTesting
    public ClientControlCommand(BiFunction<String, Boolean, JsonObject> control, Supplier<JsonObject> status, Function<JsonObject, JsonObject> scan, Function<JsonObject, JsonObject> recording, Consumer<String> dialogue) {
        this.control = control;
        this.status = status;
        this.scan = scan;
        this.recording = recording;
        this.dialogue = dialogue;
    }

    public static void register(GameBridge bridge) {
        ClientControlCommand command = new ClientControlCommand(bridge::localControl, bridge::controlStatus, bridge::scanConfig, bridge::localRecording,
                text -> {
                    net.minecraft.client.entity.EntityClientPlayerMP player = net.minecraft.client.Minecraft.getMinecraft().thePlayer;
                    if (player == null) throw new BridgeException("unavailable", "Player connection is unavailable.");
                    player.sendChatMessage(text);
                });
        command.policy = bridge::policy;
        command.follow = bridge::follow;
        register(command);
    }

    @com.google.common.annotations.VisibleForTesting
    public static void register(ClientControlCommand command) { ClientCommandHandler.instance.registerCommand(command); }

    @Override public String getCommandName() { return "mcb"; }
    @Override public String getCommandUsage(ICommandSender sender) { return AgentDialogueCommand.HELP + "; /mcb follow start <player>|status|stop; /mcb policy status|mode|qa|move|readonly|terrain|reload; " + HELP; }
    @Override public List<String> getCommandAliases() {
        Object agent = ClientCommandHandler.instance.getCommands().get("agent");
        return agent == null || agent instanceof ClientControlCommand ? Arrays.asList("codexbridge", "agent") : Arrays.asList("codexbridge");
    }
    @Override public int getRequiredPermissionLevel() { return 0; }
    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

    @Override public void processCommand(ICommandSender sender, String[] args) {
        try {
            if(args.length>0 && "follow".equalsIgnoreCase(args[0])) {
                JsonObject result=follow.apply(followArgs(args));
                sender.addChatMessage(new ChatComponentText("MCB follow: "+result.get("phase").getAsString()
                        +(result.has("playerName") ? "; "+result.get("playerName").getAsString() : "")
                        +(result.has("reason") && !result.get("reason").getAsString().isEmpty() ? "; "+result.get("reason").getAsString() : "")));
                return;
            }
            if (args.length > 0 && "policy".equalsIgnoreCase(args[0])) {
                JsonObject result = policy.apply(policyArgs(args));
                JsonObject selected = result.getAsJsonObject("selected"), effective = result.getAsJsonObject("effective");
                String mode = selected.get("mode").getAsString();
                sender.addChatMessage(new ChatComponentText("MCB 策略: " + result.getAsJsonObject("groups").getAsJsonObject(mode).get("label").getAsString()
                        + "; 移动=" + selected.get("movement").getAsString() + " → " + effective.get("movementImplementation").getAsString()
                        + "; 只读=" + selected.get("readOnly").getAsBoolean() + "; 路线地形修改=" + selected.get("terrainChanges").getAsBoolean()
                        + "; 范围=" + result.get("scopeKind").getAsString()));
                return;
            }
            if (args.length > 0 && Arrays.asList("say", "steer", "inbox", "whoami", "group", "debug", "permissions").contains(args[0].toLowerCase(Locale.ROOT))) {
                if (Arrays.asList("whoami", "group", "debug", "permissions").contains(args[0].toLowerCase(Locale.ROOT))
                        || "inbox".equalsIgnoreCase(args[0]) && args.length <= 2
                        || !"inbox".equalsIgnoreCase(args[0]) && args.length >= 2)
                    dialogue.accept("/mcb " + String.join(" ", args));
                else sender.addChatMessage(new ChatComponentText(AgentDialogueCommand.HELP));
                return;
            }
            Request request = parse(args);
            if ("help".equals(request.control)) sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            else {
                JsonObject result = "record".equals(request.control) ? recording.apply(request.scanArgs)
                        : "scan".equals(request.control) || "cache".equals(request.control) ? scan.apply(request.scanArgs)
                        : "status".equals(request.control) ? status.get() : control.apply(request.control, request.enabled);
                sender.addChatMessage(new ChatComponentText(format(result)));
            }
        } catch (BridgeException failure) {
            sender.addChatMessage(new ChatComponentText("Codex Bridge " + failure.code + ": " + JsonData.bounded(failure.getMessage(), 512)));
        }
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject followArgs(String[] args) {
        if(args.length!=2 && args.length!=3) throw new BridgeException("invalid_args","/mcb follow start <player>|status|stop");
        String action=args[1].toLowerCase(Locale.ROOT);
        if(!Arrays.asList("start","status","stop").contains(action) || "start".equals(action)!=(args.length==3))
            throw new BridgeException("invalid_args","/mcb follow start <player>|status|stop");
        JsonObject value=new JsonObject(); value.addProperty("action",action);
        if(args.length==3) value.addProperty("player",args[2]); return value;
    }

    @com.google.common.annotations.VisibleForTesting
    public static Request parse(String[] supplied) {
        List<String> args = new ArrayList<String>();
        for (String arg : supplied) if (!arg.trim().isEmpty()) args.add(arg.trim().toLowerCase(Locale.ROOT));
        if (args.isEmpty() || args.size() == 1 && "help".equals(args.get(0))) return new Request("help", false);
        if (args.size() == 1) {
            if ("on".equals(args.get(0)) || "off".equals(args.get(0))) return new Request("background", "on".equals(args.get(0)));
            if ("status".equals(args.get(0))) return new Request("status", false);
            if ("takeover".equals(args.get(0))) return new Request("background", true);
            if ("cancel".equals(args.get(0))) return new Request("cancel", false);
            if ("release".equals(args.get(0))) return new Request("background", false);
        }
        if (args.size() == 2 && Arrays.asList("mouse", "unfocused", "background", "agent").contains(args.get(0))) {
            if ("on".equals(args.get(1)) || "off".equals(args.get(1))) return new Request("agent".equals(args.get(0)) ? "background" : args.get(0), "on".equals(args.get(1)));
        }
        if (args.size() == 2 && "record".equals(args.get(0)) && Arrays.asList("on", "off", "status").contains(args.get(1))) {
            JsonObject values = new JsonObject(); values.addProperty("action", args.get(1)); return new Request("record", values);
        }
        if ((args.size() == 2 || args.size() == 3) && ("scan".equals(args.get(0)) || "cache".equals(args.get(0)))) {
            JsonObject values = new JsonObject();
            if (args.size() == 2 && "status".equals(args.get(1))) return new Request(values);
            try {
                if ("cache".equals(args.get(0)) && args.size() == 3 && "memory".equals(args.get(1))) {
                    int memory = Integer.parseInt(args.get(2));
                    if (memory < 64 || memory > ScanSettings.MAX_CACHE_MIB) throw new NumberFormatException();
                    values.addProperty("cacheMaxMiB", memory); return new Request("cache", values);
                }
                int horizontal = Integer.parseInt(args.get(1));
                int vertical = args.size() == 3 ? Integer.parseInt(args.get(2)) : horizontal;
                int maximum = "cache".equals(args.get(0)) ? ScanSettings.MAX_CACHE_CHUNKS : ScanSettings.MAX_CHUNKS;
                if (horizontal < 1 || horizontal > maximum || vertical < 1 || vertical > maximum) throw new NumberFormatException();
                if ("cache".equals(args.get(0))) {
                    values.addProperty("cacheRadiusChunks", horizontal);
                    values.addProperty("cacheVerticalRadiusChunks", vertical);
                    return new Request("cache", values);
                }
                values.addProperty("radiusChunks", horizontal);
                values.addProperty("verticalRadiusChunks", vertical);
                return new Request("scan", values);
            } catch (NumberFormatException failure) { throw new BridgeException("invalid_args", HELP); }
        }
        throw new BridgeException("invalid_args", HELP);
    }

    @com.google.common.annotations.VisibleForTesting
    public static JsonObject policyArgs(String[] args) {
        JsonObject value = new JsonObject();
        String help = "/mcb policy status|reload; mode survival|survival_efficient|creative|full; qa auto|off|observe|test|manage; move path|tp; readonly on|off; terrain on|off";
        if (args.length == 1 || args.length == 2 && "status".equalsIgnoreCase(args[1])) { value.addProperty("action", "status"); return value; }
        if (args.length == 2 && "reload".equalsIgnoreCase(args[1])) { value.addProperty("action", "reload"); return value; }
        if (args.length != 3) throw new BridgeException("invalid_args", help);
        value.addProperty("action", "set");
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "mode": value.addProperty("mode", OperationPolicies.mode(args[2])); break;
            case "qa": value.addProperty("qa", QaPolicy.level(args[2], true)); break;
            case "move": value.addProperty("movement", OperationPolicies.movement(args[2])); break;
            case "readonly": case "terrain":
                if (!"on".equalsIgnoreCase(args[2]) && !"off".equalsIgnoreCase(args[2])) throw new BridgeException("invalid_args", help);
                value.addProperty("readonly".equalsIgnoreCase(args[1]) ? "readOnly" : "terrainChanges", "on".equalsIgnoreCase(args[2])); break;
            default: throw new BridgeException("invalid_args", help);
        }
        return value;
    }

    @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if(args.length==2 && "follow".equalsIgnoreCase(args[0])) return getListOfStringsMatchingLastWord(args,"start","status","stop");
        if (args.length == 2 && "policy".equalsIgnoreCase(args[0])) return getListOfStringsMatchingLastWord(args, "status", "mode", "qa", "move", "readonly", "terrain", "reload");
        if (args.length == 3 && "policy".equalsIgnoreCase(args[0])) {
            if ("mode".equalsIgnoreCase(args[1])) return getListOfStringsMatchingLastWord(args, "survival", "survival_efficient", "creative", "full");
            if ("qa".equalsIgnoreCase(args[1])) return getListOfStringsMatchingLastWord(args, "auto", "off", "observe", "test", "manage");
            if ("move".equalsIgnoreCase(args[1])) return getListOfStringsMatchingLastWord(args, "path", "tp");
            return getListOfStringsMatchingLastWord(args, "on", "off");
        }
        if (args.length == 2 && "record".equalsIgnoreCase(args[0])) return getListOfStringsMatchingLastWord(args, "on", "off", "status");
        if (args.length == 1) return getListOfStringsMatchingLastWord(args, "policy", "say", "steer", "inbox", "whoami", "group", "debug", "permissions", "agent", "on", "off", "mouse", "unfocused", "background", "takeover", "release", "cancel", "record", "scan", "cache", "status", "help");
        if (args.length == 2 && Arrays.asList("mouse", "unfocused", "background", "agent").contains(args[0].toLowerCase(Locale.ROOT)))
            return getListOfStringsMatchingLastWord(args, "on", "off");
        if (args.length == 2 && "scan".equals(args[0].toLowerCase(Locale.ROOT)))
            return getListOfStringsMatchingLastWord(args, "status", "1", "2", "3", "4", "5", "6", "7", "8");
        if (args.length == 2 && "cache".equals(args[0].toLowerCase(Locale.ROOT)))
            return getListOfStringsMatchingLastWord(args, "status", "memory", "1", "2", "4", "8", "16", "32");
        if (args.length == 3 && "cache".equals(args[0].toLowerCase(Locale.ROOT)) && !"status".equals(args[1].toLowerCase(Locale.ROOT)))
            return "memory".equals(args[1].toLowerCase(Locale.ROOT))
                    ? getListOfStringsMatchingLastWord(args, "2048", "4096", "8192", "16384")
                    : getListOfStringsMatchingLastWord(args, "1", "2", "4", "8", "16", "32");
        if (args.length == 3 && "scan".equals(args[0].toLowerCase(Locale.ROOT)) && !"status".equals(args[1].toLowerCase(Locale.ROOT)))
            return getListOfStringsMatchingLastWord(args, "1", "2", "3", "4", "5", "6", "7", "8");
        return new ArrayList<String>();
    }

    public static boolean ownsMessage(String message) {
        String text = message.trim();
        if (!text.startsWith("/")) return false;
        String root = text.substring(1).split("\\s+", 2)[0];
        return "mcb".equals(root) || "codexbridge".equals(root) || "agent".equals(root);
    }

    public static void executeLocal(ICommandSender sender, String message) {
        if (!ownsMessage(message)) throw new BridgeException("invalid_args", "Only the fixed local Codex Bridge command roots may use this path.");
        String normalized = String.join(" ", message.trim().split("\\s+"));
        String root = normalized.substring(1).split(" ", 2)[0];
        if (!(ClientCommandHandler.instance.getCommands().get(root) instanceof ClientControlCommand))
            throw new BridgeException("unsupported", "Codex Bridge does not own this local command root. Use /mcb agent on|off; the command was not sent to the server.");
        // Even a cancelled local command must never fall through to server chat.
        ClientCommandHandler.instance.executeCommand(sender, normalized);
    }

    private static String format(JsonObject result) {
        if (result.has("recordingVersion")) return "MCB recording " + (result.get("enabled").getAsBoolean() ? "ON" : "OFF")
                + "; events=" + result.get("latestSeq").getAsLong() + "; stopReason=" + result.get("stopReason").getAsString()
                + "; flushPending=" + result.get("flushPending").getAsBoolean() + "; " + result.get("path").getAsString();
        if (result.has("radiusChunks")) return "Codex Bridge scan: horizontal " + result.get("radiusChunks").getAsInt()
                + " chunks (" + result.get("radiusBlocks").getAsInt() + " blocks), vertical " + result.get("verticalRadiusChunks").getAsInt()
                + " chunks (" + result.get("verticalRadiusBlocks").getAsInt() + " blocks); cache horizontal "
                + result.get("cacheRadiusChunks").getAsInt() + " chunks, vertical " + result.get("cacheVerticalRadiusChunks").getAsInt()
                + " chunks, memory budget " + result.get("cacheMaxMiB").getAsInt() + " MiB; session only.";
        String text = "Codex Bridge: mouse " + (result.get("mouseEnabled").getAsBoolean() ? "on" : "off")
                + ", unfocused " + (result.get("unfocusedEnabled").getAsBoolean() ? "on" : "off")
                + ", agent/background " + (result.get("enabled").getAsBoolean() ? "on" : "off")
                + ", pauseOnLostFocus=" + result.get("pauseOnLostFocus").getAsBoolean();
        if (result.get("helperConflict").getAsBoolean()) text += "; another mod replaced MouseHelper";
        if (result.has("lifecycle")) text += ", lifecycle=" + result.getAsJsonObject("lifecycle").get("status").getAsString();
        return text;
    }

    @com.google.common.annotations.VisibleForTesting
    public static class Request {
        @com.google.common.annotations.VisibleForTesting
        public final String control;
        final boolean enabled;
        @com.google.common.annotations.VisibleForTesting
        public final JsonObject scanArgs;
        Request(String control, boolean enabled) { this.control = control; this.enabled = enabled; this.scanArgs = null; }
        Request(JsonObject scanArgs) { this("scan", scanArgs); }
        Request(String control, JsonObject scanArgs) { this.control = control; this.enabled = false; this.scanArgs = scanArgs; }
    }
}
