package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** One policy boundary for authenticated RPC, independent of transport and GUI adapters. */
public final class OperationPolicyRules {
    private static Set<String> set(String text) { return new HashSet<String>(Arrays.asList(text.split(" "))); }
    private static final Set<String> CONTROL = set("bridge.health bridge.policy client.snapshot client.worlds client.load_world client.connect client.leave_world client.exit client.background client.mouse client.unfocused client.diagnostics client.mods client.messages client.recording input.bindings world.scan_config agent.dialogue player.task");
    private static final Set<String> READ = set("registry.items registry.item registry.variants gui.inspect gui.tooltip gui.widgets inventory.inspect baubles.inspect nei.inspect nei.view nei.bookmarks research.read waila.inspect ae.inspect state.diff client.screenshot world.inspect world.inspect_many world.scan world.entities world.spatial player.plan_approach player.flight_check");
    private static final Set<String> MACHINE = set("machine.inspect machine.capture world.structure world.tiles structure.goal ae.material_source");
    private static final Set<String> MOVE = set("player.look player.hotbar player.navigate player.flight dev.teleport gui.open_inventory gui.open_pause_menu gui.open_baubles gui.close gui.pointer nei.open nei.navigate nei.preview_control");
    private static final Set<String> MUTATE = set("player.interact player.place_block player.replace_owned_block structure.survival_build gui.slot_click gui.transfer_whole_stack gui.click gui.key gui.widget_action ae.action nei.item_action nei.bookmark_action");
    public static boolean control(String operation) { return CONTROL.contains(operation) || "client.window".equals(operation) || "client.window_action".equals(operation); }
    public static String execution(String movement, boolean flying, boolean canTp) {
        return "tp".equals(movement) && canTp ? "developer_teleport" : flying ? "ability_flight" : "walking";
    }
    public static void require(boolean allowed, String reason) {
        if (!allowed) throw new BridgeException("policy_denied", reason);
    }
    static boolean flag(JsonObject value, String key) { return value.has(key) && value.get(key).getAsBoolean(); }
    static String text(JsonObject value, String key) { return value.has(key) ? value.get(key).getAsString() : ""; }
    public static void check(String operation, JsonObject args, OperationPolicies policies, boolean authority, boolean creative, boolean canTp) {
        if("player.follow".equals(operation)) {
            if("start".equals(text(args,"action"))) check("player.navigate",args,policies,authority,creative,canTp);
            return;
        }
        if (control(operation)) return;
        if (QaPolicy.discovery(operation, args)) return;
        require(policies.inspect().get("available").getAsBoolean(), "Fix and reload policies.json before world operations.");
        JsonObject selected = policies.selected();
        boolean readOnly = flag(selected, "readOnly");
        boolean supply = policies.permits("supply") && (creative || canTp);
        boolean direct = policies.permits("direct") && authority && (creative || canTp);
        boolean mutation = false;
        if (operation.startsWith("qa.")) {
            QaPolicy.check(operation, args, policies.qaLevel(), readOnly, text(selected, "movement"), authority,
                    authority && (creative || canTp), canTp);
            return;
        }
        if (args.has("nativeWait")) require(policies.permits("machineRead") && authority, "Authoritative observation waits require enhanced observation.");
        if ("nei.item_action".equals(operation) && "take".equals(text(args, "action"))) require(supply, "NEI item generation requires creative/full policy and game authority.");
        if ("bridge.profile".equals(operation)) throw new BridgeException("unsupported", "Use bridge.policy; execution context is derived from the live connection.");
        if (READ.contains(operation) || MOVE.contains(operation)) {
            if ("dev.teleport".equals(operation)) require("tp".equals(text(selected, "movement")) && canTp, "TP requires selected tp movement and current normal tp command permission.");
        } else if (MACHINE.contains(operation)) require(policies.permits("machineRead") && authority, "This observation requires an authorized efficient-survival or higher policy.");
        else if ("server.observe".equals(operation)) {
            String kind = text(args, "kind");
            if (!kind.isEmpty() && !"status".equals(kind) && !"self".equals(kind))
                require(policies.permits("machineRead") && authority, "Server block/machine reads require the selected observation policy and current server permission.");
        } else if ("recipes.query".equals(operation)) {
            if (args.has("x") || args.has("machine") || args.has("machinePosition") || "backend_match".equals(text(args, "mode")))
                require(policies.permits("machineRead") && authority, "Machine recipe matching requires enhanced observation.");
        } else if ("inventory.supply".equals(operation)) { require(supply, "Supply requires creative/full policy and actual game authority."); mutation = true; }
        else if ("machine.set_working".equals(operation)) {
            require(direct, "Direct backend actions require full policy and actual game authority."); mutation = true;
        } else if ("ae.order".equals(operation)) {
            if ("status".equals(text(args, "action"))) require(policies.permits("machineRead") && authority, "Authoritative order status requires enhanced observation.");
            else mutation = true; // The existing adapter still binds the player's actual open terminal and native callbacks.
        } else if ("player.input".equals(operation)) {
            String keys = args.toString(); mutation = keys.contains("\"attack\"") || keys.contains("\"use\"");
        } else if ("chat.send".equals(operation)) {
            String message = text(args, "message").trim();
            if (message.startsWith("/") && !message.matches("/(?i:mcb|codexbridge|agent)(?:\\s.*)?")) {
                require(direct, "Arbitrary game commands require full policy; use named native operations otherwise."); mutation = true;
            }
        } else if (MUTATE.contains(operation)) mutation = true;
        else throw new BridgeException("policy_unclassified", "Operation has no policy classification: " + operation);
        if (mutation) {
            require(!readOnly, "Read-only policy refuses mutations before submission.");
            require(!creative || policies.permits("supply"), "Actual creative mode cannot perform survival mutations; switch actual game mode or choose creative/full policy.");
        }
    }
}
