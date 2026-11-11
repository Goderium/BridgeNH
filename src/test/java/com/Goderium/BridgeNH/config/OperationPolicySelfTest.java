package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.common.bridge.BridgeCapabilities;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaAdapter;
import com.Goderium.BridgeNH.network.RemoteReadClient;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OperationPolicySelfTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("operation policy check " + checks); }
    private static JsonObject json(String value) { return new JsonParser().parse(value).getAsJsonObject(); }
    private static void denied(Runnable action, String code) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code)); }
    }
    public static int run() throws Exception {
        Path folder = Files.createTempDirectory("mcb-policy-test-"), file = folder.resolve("policies.json");
        try {
            OperationPolicies p = new OperationPolicies(); p.initialize(file); p.bind("sp:actual-folder", "renamable title");
            check("survival".equals(p.selected().get("mode").getAsString()));
            p.update(json("{mode:'survival_efficient',movement:'path'}"));
            OperationPolicyRules.check("machine.inspect", new JsonObject(), p, true, false, false); checks++;
            denied(() -> OperationPolicyRules.check("machine.inspect", new JsonObject(), p, false, false, false), "policy_denied");
            denied(() -> OperationPolicyRules.check("inventory.supply", new JsonObject(), p, true, false, true), "policy_denied");
            p.bind("mp:" + OperationPolicies.endpoint("Example.COM."), "server");
            check("survival".equals(p.selected().get("mode").getAsString()));
            p.update(json("{mode:'full',movement:'tp',readOnly:true}"));
            denied(() -> OperationPolicyRules.check("chat.send", json("{message:'/give @p stone'}"), p, true, true, true), "policy_denied");
            denied(() -> OperationPolicyRules.check("gui.transfer_whole_stack", new JsonObject(), p, true, false, true), "policy_denied");
            OperationPolicyRules.check("player.look", new JsonObject(), p, true, true, true); checks++;
            p.update(json("{readOnly:false}"));
            denied(() -> OperationPolicyRules.check("chat.send", json("{message:'/give @p stone'}"), p, false, true, false), "policy_denied");
            denied(() -> OperationPolicyRules.check("dev.teleport", new JsonObject(), p, true, true, false), "policy_denied");
            check("ability_flight".equals(OperationPolicyRules.execution("tp", true, false)));
            check("walking".equals(OperationPolicyRules.execution("tp", false, false)));
            check("developer_teleport".equals(OperationPolicyRules.execution("tp", false, true)));
            check("walking".equals(OperationPolicyRules.execution("path", false, true)));
            check(RemoteReadClient.operatorSample(json("{operatorReads:true}"), 1000000000L));
            check(!RemoteReadClient.operatorSample(json("{operatorReads:true}"), 1000000001L));
            check(!RemoteReadClient.operatorSample(json("{operatorReads:false}"), 10L));
            check(!RemoteReadClient.operatorSample(new JsonObject(), 10L));
            java.lang.reflect.Field operations = BridgeCapabilities.class.getDeclaredField("OPERATIONS");
            operations.setAccessible(true);
            for (String operation : (String[]) operations.get(null)) {
                if ("qa.cancel".equals(operation)) continue;
                OperationPolicyRules.check(operation, new JsonObject(), p, true, true, true); checks++;
            }
            p.bind("sp:actual-folder", "new display name"); p.reload();
            check("survival_efficient".equals(p.selected().get("mode").getAsString()));
            OperationPolicies restarted = new OperationPolicies(); restarted.initialize(file); restarted.bind("mp:example.com:25565", "server");
            check("full".equals(restarted.selected().get("mode").getAsString()));
            check("[::1]:25565".equals(OperationPolicies.endpoint("[::1]")));
            check(!OperationPolicies.endpoint("example.com:25566").equals(OperationPolicies.endpoint("example.com")));
            denied(() -> OperationPolicies.endpoint("bad:port"), "invalid_args");
            denied(() -> p.update(json("{scope:'typo',mode:'full'}")), "invalid_args");
            denied(() -> OperationPolicyRules.check("unknown.mutation", new JsonObject(), p, true, true, true), "policy_unclassified");
            denied(() -> OperationPolicyRules.check("bridge.profile", json("{profile:'developer'}"), p, true, false, true), "unsupported");
            for (String alias : new String[]{"生存", "生存高效", "创造", "完全支配"}) {
                denied(() -> OperationPolicies.mode(alias), "invalid_args");
                denied(() -> ClientControlCommand.policyArgs(new String[]{"policy", "mode", alias}), "invalid_args");
            }
            denied(() -> OperationPolicies.movement("寻路"), "invalid_args");
            denied(() -> ClientControlCommand.policyArgs(new String[]{"policy", "move", "寻路"}), "invalid_args");
            JsonObject command = ClientControlCommand.policyArgs(new String[]{"policy", "mode", "survival_efficient"});
            check("survival_efficient".equals(command.get("mode").getAsString()));
            qa(p, file);
            Files.write(file, "invalid".getBytes(StandardCharsets.UTF_8));
            denied(() -> p.update(json("{mode:'full'}")), "config_changed");
            denied(p::reload, "policy_config_unavailable");
            denied(() -> OperationPolicyRules.check("player.interact", json("{action:'attack'}"), p, true, false, true), "policy_denied");
            OperationPolicyRules.check("bridge.policy", new JsonObject(), p, true, false, true); checks++;
            GameQueue queue = new GameQueue(2); GameQueue.Control control = new GameQueue.Control();
            control.guardExecution(() -> { throw new BridgeException("policy_changed", "test revision invalidated"); });
            GameQueue.Ticket<Integer> ticket = queue.submit(() -> { throw new AssertionError("Old revision acted"); }, 1000, control);
            queue.drain(1);
            try { queue.await(ticket, 1000); throw new AssertionError("Expected invalidation"); }
            catch (java.util.concurrent.ExecutionException failure) { check(failure.getCause() instanceof BridgeException && "policy_changed".equals(((BridgeException) failure.getCause()).code)); }
            return checks;
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(folder); }
    }

    private static void qa(OperationPolicies p, Path file) throws Exception {
        JsonObject owned = json("{runId:'983d4171-8cca-4bab-b19d-5d2dd88f9775'}");
        p.update(json("{mode:'survival',qa:'auto'}"));
        OperationPolicyRules.check("qa.catalog", json("{section:'capabilities'}"), p, false, false, false); checks++;
        denied(() -> OperationPolicyRules.check("qa.catalog", new JsonObject(), p, true, false, false), "policy_denied");
        denied(() -> p.update(json("{qa:'test'}")), "policy_denied");
        p.update(json("{mode:'creative',qa:'auto'}")); check("test".equals(p.qaLevel()));
        OperationPolicyRules.check("qa.run", json("{selector:'reviewed'}"), p, true, true, true); checks++;
        denied(() -> OperationPolicyRules.check("qa.run", new JsonObject(), p, false, true, false), "policy_denied");
        denied(() -> OperationPolicyRules.check("qa.command", json("{command:'runall'}"), p, true, true, true), "policy_denied");
        denied(() -> p.update(json("{qa:'manage'}")), "policy_denied");
        p.update(json("{qa:'observe'}"));
        denied(() -> OperationPolicyRules.check("qa.run", new JsonObject(), p, true, true, true), "policy_denied");
        p.update(json("{qa:'test',readOnly:true}"));
        denied(() -> OperationPolicyRules.check("qa.run", new JsonObject(), p, true, true, true), "policy_denied");
        p.update(json("{mode:'survival',qa:'off'}"));
        for (String op : new String[]{"qa.cancel", "qa.status", "qa.results"}) {
            OperationPolicyRules.check(op, owned, p, true, false, false); checks++;
        }
        denied(() -> OperationPolicyRules.check("qa.cancel", new JsonObject(), p, true, false, false), "policy_denied");
        JsonObject clear = json("{command:'clear',args:['983d4171-8cca-4bab-b19d-5d2dd88f9775']}");
        denied(() -> OperationPolicyRules.check("qa.command", clear, p, true, false, false), "policy_denied");
        p.update(json("{mode:'creative',qa:'auto',readOnly:false}"));
        OperationPolicyRules.check("qa.command", clear, p, true, true, true); checks++;
        check("clear".equals(PrivateDimensionQaAdapter.route("qa.command", clear)));
        p.reload(); check("test".equals(p.qaLevel()));
        p.update(json("{mode:'full',qa:'auto',movement:'path'}"));
        denied(() -> OperationPolicyRules.check("qa.command", json("{command:'tp'}"), p, true, true, true), "policy_denied");
        denied(() -> QaPolicy.requireNativeScope("qa.run", new JsonObject(), json("{level:'test'}")), "qa_policy_unavailable");
        // Existing configs retain prior group restrictions when the additive QA fields are absent.
        JsonObject legacy = OperationPolicies.defaults();
        legacy.getAsJsonObject("defaults").remove("qa");
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry : legacy.getAsJsonObject("groups").entrySet()) entry.getValue().getAsJsonObject().remove("qa");
        Files.write(file, legacy.toString().getBytes(StandardCharsets.UTF_8)); p.reload();
        check("auto".equals(p.selected().get("qa").getAsString()));
        check("off".equals(p.qaLevel()));
        check("test".equals(ClientControlCommand.policyArgs(new String[]{"policy", "qa", "test"}).get("qa").getAsString()));
        denied(() -> QaPolicy.level("测试", true), "invalid_args");
    }
}
