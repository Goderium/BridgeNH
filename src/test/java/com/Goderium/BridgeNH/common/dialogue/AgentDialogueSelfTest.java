package com.Goderium.BridgeNH.common.dialogue;

import com.Goderium.BridgeNH.config.AgentPermissions;
import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AgentDialogueSelfTest {
    private static int checks;
    private static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("dialogue " + checks); }
    private static void refused(Runnable action, String code) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code)); }
    }
    public static int run() throws Exception {
        AgentDialogueCommand command = new AgentDialogueCommand();
        check("mcb".equals(command.getCommandName()) && command.getCommandAliases().contains("mcbagent"));
        java.nio.file.Path directory = java.nio.file.Paths.get("build/tmp/agents-dialogue-permissions-tests");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Path file = java.nio.file.Files.createTempDirectory(directory, "config-").resolve("permissions.json");
        AgentPermissions permissions = new AgentPermissions(); permissions.initialize(file);
        AgentDialogueMailbox box = new AgentDialogueMailbox(permissions);
        String owner = UUID.randomUUID().toString(), alice = UUID.randomUUID().toString(), bob = UUID.randomUUID().toString();
        check(!permissions.canBind(owner) && !permissions.allows(owner, true, "manage"));
        // Neither actual binding nor Minecraft OP (not a policy input) grants an unassigned player privileges.
        refused(() -> permissions.require(owner, true, false, "manage"), "permission_denied");
        permissions.require("", false, true, "manage"); check(true);
        permissions.setGroup(owner, "agent"); permissions.setGroup(alice, "user"); permissions.setGroup(bob, "user");
        check(!permissions.allows(owner, false, "manage") && permissions.allows(owner, true, "manage"));
        check(!permissions.debugEnabled(owner, true));
        permissions.setDebug(owner, true); check(permissions.debugEnabled(owner, true) && !permissions.debugEnabled(owner, false));
        permissions.setDebug(owner, false);
        String lease = UUID.randomUUID().toString();
        List<String> receipts = new ArrayList<String>();
        AgentDialogueMailbox.Receipts sink = (id, text) -> receipts.add(id + ":" + text);
        refused(() -> box.submit(alice, "Alice", "say", "hello", 1000, sink), "unavailable");
        JsonObject bind = args("bind", lease); bind.addProperty("threadId", UUID.randomUUID().toString());
        box.perform(owner, bind, 1000, sink);
        refused(() -> box.perform(bob, bind, 1000, sink), "permission_denied");
        String otherAgent = UUID.randomUUID().toString(); permissions.setGroup(otherAgent, "agent");
        refused(() -> box.perform(otherAgent, bind, 1000, sink), "busy");
        JsonObject message = box.submit(alice, "Alice", "steer", "保留 Case 与空格", 1000, sink);
        check("user".equals(message.get("permissionGroup").getAsString()));
        check(receipts.size() == 1 && receipts.get(0).startsWith(alice) && receipts.get(0).contains("已接收, 等待 Agent 处理"));
        refused(() -> box.submit(alice, "Alice", "say", "too fast", 1001, sink), "rate_limit");
        refused(() -> box.submit(bob, "Bob", "say", "bad\nline", 1001, sink), "invalid_args");
        refused(() -> box.perform(bob, args("poll", lease), 1001, sink), "stale_session");
        check(box.perform(owner, args("poll", lease), 1001, sink).getAsJsonArray("messages").size() == 1);
        check(!box.playerStatus(bob, "", 1001, sink).get(0).contains("保留"));
        JsonObject receipt = args("receipt", lease); receipt.addProperty("messageId", message.get("id").getAsString());
        receipt.addProperty("state", "dispatching"); receipt.addProperty("text", "sending");
        box.perform(owner, receipt, 1002, sink);
        check(box.perform(owner, args("poll", lease), 1003, sink).getAsJsonArray("messages").size() == 0);
        receipt.addProperty("state", "delivered"); receipt.addProperty("text", "已送达");
        int count = receipts.size(); box.perform(owner, receipt, 1004, sink);
        check(count == receipts.size());
        check(box.playerStatus(alice, message.get("id").getAsString(), 1004, sink).get(0).contains("delivered"));
        check(!box.playerStatus(alice, message.get("id").getAsString(), 1004, sink).get(0).contains("已送达"));
        box.perform(owner, receipt, 1004, sink); check(count == receipts.size());
        receipt.addProperty("state", "queued"); refused(() -> box.perform(owner, receipt, 1005, sink), "invalid_state");
        receipt.addProperty("state", "replied"); receipt.addProperty("text", "收到你的引导");
        box.perform(owner, receipt, 1006, sink); check(receipts.get(receipts.size() - 1).startsWith(alice)
                && receipts.get(receipts.size() - 1).contains("replied: 收到你的引导"));
        JsonObject pending = box.submit(bob, "Bob", "say", "queued", 2000, sink);
        box.expire(1006 + AgentDialogueMailbox.LEASE_MILLIS, sink);
        check(receipts.get(receipts.size() - 1).startsWith(bob) && receipts.get(receipts.size() - 1).contains("failed"));
        check(!box.summary().get("enabled").getAsBoolean());
        check(box.playerStatus(bob, pending.get("id").getAsString(), 62000, sink).get(0).contains("failed"));
        refused(() -> RemoteReadProtocol.permit(AgentDialogueMailbox.OPERATION, false), "permission_denied");
        AgentDialogueMailbox full = new AgentDialogueMailbox(permissions); full.perform(owner, bind, 1, sink);
        for (int i = 0; i < 32; i++) {
            String player = UUID.randomUUID().toString(); permissions.setGroup(player, "user"); full.submit(player, "P", "say", "hello", 2, sink);
        }
        refused(() -> full.submit(alice, "A", "say", "overflow", 3, sink), "queue_full");
        AgentDialogueMailbox bounded = new AgentDialogueMailbox(permissions); bounded.perform(owner, bind, 1, sink);
        String wide = String.join("", java.util.Collections.nCopies(1024, "测"));
        for (int i = 0; i < 16; i++) {
            String player = UUID.randomUUID().toString(); permissions.setGroup(player, "user"); bounded.submit(player, "SixteenCharsName", i < 8 ? "say" : "steer", wide, 2, sink);
        }
        JsonObject page = bounded.perform(owner, args("poll", lease), 3, sink);
        check(page.getAsJsonArray("messages").size() == 8);
        check(RemoteReadProtocol.encode(page, RemoteReadProtocol.RESPONSE_BYTES).length < RemoteReadProtocol.RESPONSE_BYTES);
        JsonObject late = page.getAsJsonArray("messages").get(0).getAsJsonObject();
        receipt.addProperty("messageId", late.get("id").getAsString());
        receipt.addProperty("state", "dispatching"); receipt.addProperty("text", "sending"); bounded.perform(owner, receipt, 4, sink);
        receipt.addProperty("state", "unknown"); receipt.addProperty("text", "uncertain"); bounded.perform(owner, receipt, 5, sink);
        receipt.addProperty("state", "replied"); receipt.addProperty("text", "late actual reply"); bounded.perform(owner, receipt, 6, sink);
        check(bounded.playerStatus(late.get("playerUuid").getAsString(), late.get("id").getAsString(), 7, sink).get(0).contains("replied"));
        JsonObject publicMessage = bounded.submit(alice, "Alice", "steer", "public reply", 2000, sink);
        JsonObject claim = args("prepare_chat_reply", lease); claim.addProperty("messageId", publicMessage.get("id").getAsString()); claim.addProperty("text", "正常玩家发言");
        refused(() -> bounded.perform(owner, claim, 2001, sink), "invalid_state");
        receipt.addProperty("messageId", publicMessage.get("id").getAsString()); receipt.addProperty("state", "dispatching"); receipt.addProperty("text", "sending");
        bounded.perform(owner, receipt, 2002, sink);
        claim.addProperty("text", " /stop"); refused(() -> bounded.perform(owner, claim, 2003, sink), "invalid_args");
        claim.addProperty("text", String.join("", java.util.Collections.nCopies(101, "a"))); refused(() -> bounded.perform(owner, claim, 2003, sink), "invalid_args");
        claim.addProperty("text", "正常玩家发言");
        refused(() -> bounded.perform(bob, claim, 2004, sink), "stale_session");
        check(bounded.perform(owner, claim, 2004, sink).get("claimed").getAsBoolean());
        check(!bounded.perform(owner, claim, 2005, sink).get("claimed").getAsBoolean());
        receipt.addProperty("state", "delivered"); receipt.addProperty("text", "late acknowledgement"); bounded.perform(owner, receipt, 2006, sink);
        check(bounded.playerStatus(alice, publicMessage.get("id").getAsString(), 2007, sink).get(0).contains("replying"));
        receipt.addProperty("state", "replied"); receipt.addProperty("text", "different"); refused(() -> bounded.perform(owner, receipt, 2008, sink), "invalid_state");
        count = receipts.size();
        receipt.addProperty("text", "正常玩家发言"); bounded.perform(owner, receipt, 2009, sink);
        check(count == receipts.size());
        bounded.perform(owner, receipt, 2010, sink); check(count == receipts.size());
        check(bounded.playerStatus(alice, publicMessage.get("id").getAsString(), 2010, sink).get(0).contains("replied"));
        check(!bounded.perform(owner, claim, 2011, sink).get("claimed").getAsBoolean());
        claim.addProperty("text", "different"); refused(() -> bounded.perform(owner, claim, 2012, sink), "invalid_state");
        AgentDialogueMailbox revocation = new AgentDialogueMailbox(permissions); revocation.perform(owner, bind, 3000, sink);
        JsonObject revoked = revocation.submit(alice, "Alice", "say", "not delivered", 3001, sink);
        permissions.setGroup(alice, "guest");
        check(revocation.perform(owner, args("poll", lease), 3002, sink).getAsJsonArray("messages").size() == 0);
        receipt.addProperty("messageId", revoked.get("id").getAsString()); receipt.addProperty("state", "dispatching"); receipt.addProperty("text", "sending");
        refused(() -> revocation.perform(owner, receipt, 3003, sink), "invalid_state");
        refused(() -> revocation.submit(alice, "Alice", "say", "denied", 4000, sink), "permission_denied");
        refused(() -> revocation.playerStatus(alice, "", 4000, sink), "permission_denied");
        permissions.setGroup(bob, "admin"); permissions.setDebug(bob, true);
        AgentPermissions reopened = new AgentPermissions(); reopened.initialize(file);
        check(reopened.debugEnabled(bob, false) && reopened.allows(bob, false, "manage"));
        check("guest".equals(reopened.assigned(alice)) && reopened.canBind(owner));
        permissions.setGroup(bob, "user"); check(!permissions.debugEnabled(bob, false));
        permissions.setGroup(owner, "guest"); revocation.expire(4001, sink);
        check(!revocation.summary().get("enabled").getAsBoolean() && !revocation.owns(owner));
        refused(() -> revocation.perform(owner, bind, 4002, sink), "permission_denied");
        byte[] valid = java.nio.file.Files.readAllBytes(file);
        java.nio.file.Files.write(file, "invalid json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        refused(() -> permissions.setGroup(owner, "admin"), "config_changed");
        refused(permissions::reload, "config_unavailable");
        check(!permissions.allows(bob, true, "send") && !permissions.canBind(otherAgent));
        java.nio.file.Files.write(file, valid); permissions.reload();
        check("guest".equals(permissions.assigned(owner)) && permissions.allows(bob, false, "send"));
        JsonObject invalid = AgentPermissions.defaults(); invalid.addProperty("opInheritsAdmin", true);
        java.nio.file.Files.write(file, invalid.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        refused(permissions::reload, "config_unavailable");
        java.nio.file.Files.write(file, valid); permissions.reload();
        check(!command.canCommandSenderUseCommand(new Sender()) && !AgentDialogueCommand.console(new Sender()));
        command.processCommand(new Sender(), new String[]{"group", "set", owner, "admin"});
        AgentDialogueCommand.PERMISSIONS.initialize(file.resolveSibling("console.json"));
        check(command.canCommandSenderUseCommand(net.minecraft.network.rcon.RConConsoleSource.instance));
        command.processCommand(net.minecraft.network.rcon.RConConsoleSource.instance, new String[]{"group", "set", owner, "admin"});
        check("admin".equals(AgentDialogueCommand.PERMISSIONS.assigned(owner)));
        command.processCommand(net.minecraft.network.rcon.RConConsoleSource.instance, new String[]{"group", "set", owner, "agent"});
        long now = System.currentTimeMillis();
        AgentDialogueCommand.MAILBOX.perform(owner, bind, now, sink);
        command.processCommand(net.minecraft.network.rcon.RConConsoleSource.instance, new String[]{"say", "console input"});
        JsonObject consoleMessage = AgentDialogueCommand.MAILBOX.perform(owner, args("poll", lease), now + 10, sink).getAsJsonArray("messages").get(0).getAsJsonObject();
        check("console".equals(consoleMessage.get("permissionGroup").getAsString()) && "console".equals(consoleMessage.get("senderKind").getAsString()));
        check(AgentDialogueMailbox.CONSOLE_ID.equals(consoleMessage.get("playerUuid").getAsString()));
        refused(() -> AgentDialogueCommand.MAILBOX.submit(AgentDialogueMailbox.CONSOLE_ID, "Server", "say", "spoof", now + 2000, sink), "permission_denied");
        refused(() -> AgentDialogueCommand.MAILBOX.submit(alice, "Server", "say", "spoof", now + 2000, sink, true), "permission_denied");
        refused(() -> AgentDialogueCommand.PERMISSIONS.setGroup(AgentDialogueMailbox.CONSOLE_ID, "admin"), "invalid_args");
        return checks;
    }
    private static JsonObject args(String action, String lease) {
        JsonObject args = new JsonObject(); args.addProperty("action", action); args.addProperty("leaseId", lease); return args;
    }
    private static final class Sender implements net.minecraft.command.ICommandSender {
        @Override public String getCommandSenderName() { return "Server"; }
        @Override public net.minecraft.util.IChatComponent getFormattedCommandSenderName() { return new net.minecraft.util.ChatComponentText("Server"); }
        @Override public void addChatMessage(net.minecraft.util.IChatComponent message) { }
        @Override public boolean canCommandSenderUseCommand(int level, String name) { return true; }
        @Override public net.minecraft.util.ChunkCoordinates getCommandSenderPosition() { return new net.minecraft.util.ChunkCoordinates(); }
        @Override public net.minecraft.world.World getEntityWorld() { return null; }
    }
}
