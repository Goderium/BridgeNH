package com.Goderium.BridgeNH.common.dialogue;

import com.Goderium.BridgeNH.config.AgentPermissions;
import com.Goderium.BridgeNH.util.BridgeException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.rcon.RConConsoleSource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;

/** Ordinary server command: even clients without MCB can send attributed messages. */
public final class AgentDialogueCommand extends CommandBase {
    static final AgentPermissions PERMISSIONS = new AgentPermissions();
    static final AgentDialogueMailbox MAILBOX = new AgentDialogueMailbox(PERMISSIONS);
    static final AgentDialogueMailbox.Receipts RECEIPTS = new AgentDialogueMailbox.Receipts() {
        @Override public void send(String uuid, String text) { receipt(uuid, text); }
        @Override public void debug(String text) { debugReceipt(text); }
    };
    public static final String HELP = "/mcb whoami | say <内容> | steer <引导内容> | inbox [编号] | group get <玩家/UUID> | group set <玩家/UUID> guest|user|admin|agent | debug on|off|status | permissions reload";
    public static void initializePermissions(java.nio.file.Path path) { PERMISSIONS.initialize(path); }
    public static void reloadPermissions() { PERMISSIONS.reload(); }
    public static void closeDialogue(String reason) { MAILBOX.close(RECEIPTS, reason); }
    public static void expireDialogue(long now) { MAILBOX.expire(now, RECEIPTS); }
    public static com.google.gson.JsonObject performDialogue(String playerId, com.google.gson.JsonObject args, long now) {
        return MAILBOX.perform(playerId, args, now, RECEIPTS);
    }
    @Override public String getCommandName() { return "mcb"; }
    @Override public List<String> getCommandAliases() { return Arrays.asList("mcbagent"); }
    @Override public String getCommandUsage(ICommandSender sender) { return HELP; }
    @Override public int getRequiredPermissionLevel() { return 0; }
    static boolean console(ICommandSender sender) {
        return sender != null && (sender == MinecraftServer.getServer() || sender == RConConsoleSource.instance);
    }
    @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return sender instanceof EntityPlayerMP || console(sender); }
    @Override public void processCommand(ICommandSender sender, String[] args) {
        try {
            if (!canCommandSenderUseCommand(sender)) throw new BridgeException("permission_denied", "Only logged-in players or the server console may use MCB commands.");
            boolean console = console(sender);
            String uuid = console ? AgentDialogueMailbox.CONSOLE_ID : ((EntityPlayerMP) sender).getUniqueID().toString();
            MAILBOX.expire(System.currentTimeMillis(), RECEIPTS);
            String mode = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            if ("whoami".equals(mode) && args.length == 1) {
                tell(sender, console ? "[MCB] 服务器后台: 全部权限" : "[MCB] " + PERMISSIONS.label(PERMISSIONS.effective(uuid, MAILBOX.owns(uuid)))
                        + ("agent".equals(PERMISSIONS.assigned(uuid)) && !MAILBOX.owns(uuid) ? " (AGENT 账号尚未连接)" : "")
                        + "; debug=" + (PERMISSIONS.debugEnabled(uuid, MAILBOX.owns(uuid)) ? "on" : "off"));
            } else if ("group".equals(mode)) {
                PERMISSIONS.require(uuid, MAILBOX.owns(uuid), console, "manage");
                if (args.length == 3 && "get".equalsIgnoreCase(args[1])) {
                    String target = target(args[2]);
                    tell(sender, "[MCB] " + args[2] + ": " + PERMISSIONS.assigned(target) + "; 当前=" + PERMISSIONS.effective(target, MAILBOX.owns(target)));
                } else if (args.length == 4 && "set".equalsIgnoreCase(args[1])) {
                    String target = target(args[2]), group = AgentPermissions.groupId(args[3]);
                    PERMISSIONS.setGroup(target, group);
                    MAILBOX.expire(System.currentTimeMillis(), RECEIPTS);
                    org.apache.logging.log4j.LogManager.getLogger("MCB").info("MCB group changed: actor={}, target={}, group={}", console ? "console" : uuid, target, group);
                    tell(sender, "[MCB] " + args[2] + " -> " + group + ", 已保存到 config.");
                } else tell(sender, HELP);
            } else if ("permissions".equals(mode) && args.length == 2 && "reload".equalsIgnoreCase(args[1])) {
                PERMISSIONS.require(uuid, MAILBOX.owns(uuid), console, "manage");
                try { PERMISSIONS.reload(); }
                finally { MAILBOX.expire(System.currentTimeMillis(), RECEIPTS); }
                tell(sender, "[MCB] 权限配置已重载.");
            } else if ("debug".equals(mode)) {
                PERMISSIONS.require(uuid, MAILBOX.owns(uuid), console, "debug");
                if (console) tell(sender, "[MCB] 后台调试记录始终写入日志.");
                else if (args.length == 2 && Arrays.asList("on", "off", "status").contains(args[1].toLowerCase(Locale.ROOT))) {
                    if (!"status".equalsIgnoreCase(args[1])) PERMISSIONS.setDebug(uuid, "on".equalsIgnoreCase(args[1]));
                    tell(sender, "[MCB] 私密调试显示 " + (PERMISSIONS.debugEnabled(uuid, MAILBOX.owns(uuid)) ? "on" : "off") + ", 已保存到 config.");
                } else tell(sender, "/mcb debug on|off|status");
            } else if (("status".equals(mode) || "inbox".equals(mode)) && args.length <= 2) {
                for (String line : MAILBOX.playerStatus(uuid, args.length == 2 ? args[1] : "", System.currentTimeMillis(), RECEIPTS, console))
                    tell(sender, line);
            } else if (("say".equals(mode) || "steer".equals(mode)) && args.length >= 2)
                MAILBOX.submit(uuid, console ? "ServerConsole" : sender.getCommandSenderName(), mode,
                        String.join(" ", Arrays.copyOfRange(args, 1, args.length)), System.currentTimeMillis(), RECEIPTS, console);
            else tell(sender, help(uuid, console));
        } catch (BridgeException failure) { tell(sender, "[MCB] " + failure.code + ": " + failure.getMessage()); }
    }
    private static String help(String uuid, boolean console) {
        String help = "/mcb whoami";
        if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "send")) help += " | say <内容> | steer <内容> | inbox [编号]";
        if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "manage")) help += " | group get <玩家/UUID> | group set <玩家/UUID> guest|user|admin|agent | permissions reload";
        if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "debug")) help += " | debug on|off|status";
        return help;
    }
    private static String target(String value) {
        MinecraftServer server = MinecraftServer.getServer();
        EntityPlayerMP player = server == null || server.getConfigurationManager() == null ? null : server.getConfigurationManager().getPlayerByUsername(value);
        return player == null ? AgentPermissions.playerId(value) : player.getUniqueID().toString();
    }
    private static void debugReceipt(String text) {
        org.apache.logging.log4j.LogManager.getLogger("MCB").info(text);
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            String uuid = player.getUniqueID().toString();
            if (PERMISSIONS.debugEnabled(uuid, MAILBOX.owns(uuid))) tell(player, text);
        }
    }
    static void receipt(String uuid, String text) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server != null && AgentDialogueMailbox.CONSOLE_ID.equals(uuid)) { tell(server, text); return; }
        if (server == null || server.getConfigurationManager() == null) return;
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            if (uuid.equals(player.getUniqueID().toString())) { tell(player, text); return; }
        }
    }
    private static void tell(ICommandSender player, String text) {
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 512, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            player.addChatMessage(new ChatComponentText(text.substring(start, end))); start = end;
        }
    }
    @Override public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) return java.util.Collections.<String>emptyList();
        boolean console = console(sender);
        String uuid = console ? "" : ((EntityPlayerMP) sender).getUniqueID().toString();
        if (args.length == 1) {
            java.util.List<String> choices = new java.util.ArrayList<String>(Arrays.asList("whoami", "help"));
            if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "send")) choices.addAll(Arrays.asList("say", "steer", "inbox"));
            if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "manage")) choices.addAll(Arrays.asList("group", "permissions"));
            if (console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "debug")) choices.add("debug");
            return getListOfStringsMatchingLastWord(args, choices.toArray(new String[0]));
        }
        if ((console || PERMISSIONS.allows(uuid, MAILBOX.owns(uuid), "manage")) && "group".equalsIgnoreCase(args[0])) {
            if (args.length == 2) return getListOfStringsMatchingLastWord(args, "get", "set");
            if (args.length == 4 && "set".equalsIgnoreCase(args[1])) return getListOfStringsMatchingLastWord(args, "guest", "user", "admin", "agent");
        }
        return java.util.Collections.<String>emptyList();
    }
}
