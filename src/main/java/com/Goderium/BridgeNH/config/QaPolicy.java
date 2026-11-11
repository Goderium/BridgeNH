package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.network.RemoteReadProtocol;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Shared intent classification. Actual server authority and run ownership remain separate checks. */
public final class QaPolicy {
    public static final String CONTROLLED_CONTRACT = "tstqa-controlled-policy-v1";
    enum Action { DISCOVER, OBSERVE, RUN, STOP, CLEAR, FORGET, MANAGE, TELEPORT }

    public static String level(String value, boolean auto) {
        String key = value.trim().toLowerCase(Locale.ROOT);
        if (!(auto && "auto".equals(key)) && !Arrays.asList("off", "observe", "test", "manage").contains(key))
            throw new BridgeException("invalid_args", "QA policy must be auto, off, observe, test or manage.");
        return key;
    }
    public static int rank(String value) { return Arrays.asList("off", "observe", "test", "manage").indexOf(level(value, false)); }
    public static boolean discovery(String operation, JsonObject args) {
        return "qa.catalog".equals(operation) && "capabilities".equals(OperationPolicyRules.text(args, "section"));
    }
    static String explicitRun(String operation, JsonObject args) {
        String id = OperationPolicyRules.text(args, "runId");
        if ("qa.command".equals(operation) && args.has("args") && args.get("args").isJsonArray()
                && args.getAsJsonArray("args").size() == 1) id = args.getAsJsonArray("args").get(0).getAsString();
        try { if (!UUID.fromString(id).toString().equals(id)) return ""; }
        catch (IllegalArgumentException failure) { return ""; }
        return id;
    }
    static Action action(String operation, JsonObject args) {
        if (discovery(operation, args)) return Action.DISCOVER;
        if ("qa.catalog".equals(operation) || "qa.status".equals(operation) || "qa.results".equals(operation)) return Action.OBSERVE;
        if ("qa.run".equals(operation)) return Action.RUN;
        if ("qa.cancel".equals(operation)) return Action.STOP;
        if ("qa.command".equals(operation)) {
            String command = OperationPolicyRules.text(args, "command");
            if ("run".equals(command)) return Action.RUN;
            if ("clear".equals(command) && !explicitRun(operation, args).isEmpty()) return Action.CLEAR;
            if ("forget".equals(command)) return Action.FORGET;
            if ("tp".equals(command)) return Action.TELEPORT;
            return Action.MANAGE;
        }
        throw new BridgeException("policy_unclassified", "Unknown QA operation.");
    }
    static boolean ownRead(String operation, JsonObject args) {
        return ("qa.status".equals(operation) || "qa.results".equals(operation)) && !explicitRun(operation, args).isEmpty();
    }
    public static void check(String operation, JsonObject args, String level, boolean readOnly, String movement,
            boolean authority, boolean canMutate, boolean canTp) {
        Action action = action(operation, args);
        if (action == Action.DISCOVER) return;
        OperationPolicyRules.require(authority, "QA requires actual integrated/server authority.");
        int rank = rank(level);
        if (action == Action.OBSERVE) {
            OperationPolicyRules.require(rank >= 1 || ownRead(operation, args), "QA observation is disabled by the current policy.");
            return; // Reduced-policy own reads are validated again by the owning private runner.
        }
        if (action == Action.STOP || action == Action.FORGET) {
            OperationPolicyRules.require(!explicitRun(operation, args).isEmpty(), "QA stop/forget requires one explicit owned run UUID.");
            return; // Normal stop is retained after downgrade/read-only; never adds new input or clears blocks.
        }
        OperationPolicyRules.require(!readOnly && canMutate, "QA mutation requires mutable policy and actual game authority.");
        OperationPolicyRules.require(rank >= (action == Action.RUN || action == Action.CLEAR ? 2 : 3),
                "QA operation exceeds the selected QA policy.");
        if (action == Action.TELEPORT) OperationPolicyRules.require("tp".equals(movement) && canTp,
                "QA teleport also requires tp movement and actual command permission.");
    }
    public static JsonObject context(OperationPolicies policies) {
        JsonObject value = new JsonObject(), selected = policies.selected();
        value.addProperty("level", policies.qaLevel()); value.add("readOnly", selected.get("readOnly"));
        value.add("movement", selected.get("movement")); return value;
    }
    public static void validateContext(JsonObject value) {
        RemoteReadProtocol.keys(value, "level", "readOnly", "movement");
        level(JsonData.string(value, "level"), false); OperationPolicies.bool(value, "readOnly");
        OperationPolicies.movement(JsonData.string(value, "movement"));
    }
    public static void requireNativeScope(String operation, JsonObject args, JsonObject context) {
        Action action = action(operation, args);
        if (action == Action.RUN && rank(JsonData.string(context, "level")) < 3
                || action == Action.CLEAR || action == Action.STOP || action == Action.FORGET
                || action == Action.OBSERVE && rank(JsonData.string(context, "level")) < 1)
            throw new BridgeException("qa_policy_unavailable", "This runner cannot prove controlled execution/owned cleanup; use the advertised private runner or manage policy where supported.");
    }
}
