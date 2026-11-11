package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.client.gui.ScreenSemantics;
import com.Goderium.BridgeNH.common.bridge.ClientReadiness;

import com.google.gson.JsonObject;

/** Native ownership, lifecycle connection fencing and permission-independent client readiness. */
public class ClientEnvironmentSelfTest {
    private static int checks;

    public static int run() {
        checks = 0;
        Object server = new Object();
        check(WorldLifecycle.integratedOwner(true, server, server), "owned integrated host is local independently of LAN visibility");
        check(!WorldLifecycle.integratedOwner(false, server, server), "a remote client cannot claim local ownership");
        check(!WorldLifecycle.integratedOwner(true, server, new Object()), "replaced global server invalidates local ownership");
        check(!WorldLifecycle.integratedOwner(true, null, null), "missing integrated server is not ownership");
        JsonObject localPlayer = ClientReadiness.healthReadiness("player", true, true, true, true, true, true, true, false, false, false, false, true);
        check("requested_task".equals(localPlayer.get("nextTool").getAsString()), "ordinary local tasks do not instruct an unnecessary developer switch");

        WorldLifecycle.ConnectionTarget targets = new WorldLifecycle.ConnectionTarget();
        Object handler = new Object(), world = new Object(), player = new Object();
        String first = targets.get(handler, world, player);
        check(first != null && first.startsWith("connection:") && first.length() <= 128, "native target fits existing exact expectedWorld guard");
        check(first.equals(targets.get(handler, world, player)), "same native connection has stable target without a server bridge");
        String second = targets.get(new Object(), world, player);
        check(!first.equals(second), "reconnecting to even the same address invalidates old target");
        String third = targets.get(handler, new Object(), player);
        check(!second.equals(third), "changed client world invalidates target");
        check(!third.equals(targets.get(handler, world, new Object())), "changed player invalidates target");
        check(targets.get(null, world, player) == null, "missing native connection clears lifecycle target");
        check(!first.equals(targets.get(handler, world, player)), "disconnect cannot resurrect old token");
        check(targets.get(handler, null, player) == null && targets.get(handler, world, null) == null, "partial joins have no disconnect target");

        for (boolean operator : new boolean[] {false, true}) {
            JsonObject ready = remote();
            ClientReadiness.remoteReadiness(ready, true, operator, true);
            check(ready.get("worldReady").getAsBoolean(), "normal server client readiness is independent of OP/MCB");
            check(ready.get("developerReady").getAsBoolean() == operator, "developer authority still requires authenticated operator");
            check(ready.get("remoteServerReady").getAsBoolean() == operator, "normal client is not advertised as server bridge");
            for (String field : new String[] {"worldLoaded", "playerPresent", "playerHealthy", "playerWorldMatches", "paused", "lifecyclePending"}) {
                JsonObject bad = remote();
                bad.addProperty(field, field.equals("paused") || field.equals("lifecyclePending"));
                ClientReadiness.remoteReadiness(bad, true, operator, true);
                check(!bad.get("worldReady").getAsBoolean() && !bad.get("developerReady").getAsBoolean(), "remote authority cannot override invalid native state " + field);
            }
        }
        JsonObject missing = remote();
        ClientReadiness.remoteReadiness(missing, false, true, true);
        check(!missing.get("worldReady").getAsBoolean(), "stale authenticated handshake cannot replace native connection");
        JsonObject menu = ClientReadiness.healthReadiness("player", false, false, false, false, false, false, false, false, false, false, false, true);
        menu.addProperty("screenKind", "settings"); ClientReadiness.menuReadiness(menu, true);
        check("menu_open".equals(menu.get("stage").getAsString()) && "mcb_gui".equals(menu.get("nextTool").getAsString()), "Unloaded settings have a native menu recovery path rather than repeated health polling");
        menu.addProperty("screenKind", "disconnect"); ClientReadiness.menuReadiness(menu, true);
        check("error_menu".equals(menu.get("stage").getAsString()), "Native disconnect retains an explicit error-menu recovery path");
        JsonObject pending = ClientReadiness.healthReadiness("player", false, false, false, false, false, false, false, false, false, true, false, true);
        pending.addProperty("screenKind", "settings"); ClientReadiness.menuReadiness(pending, true);
        check("lifecycle_pending".equals(pending.get("stage").getAsString()), "An actual pending lifecycle ticket is never bypassed by menu advice");
        check("settings".equals(ScreenSemantics.classKind(net.minecraft.client.gui.GuiOptions.class))
            && "settings".equals(ScreenSemantics.classKind(net.minecraft.client.gui.GuiScreenOptionsSounds.class))
            && "world_select".equals(ScreenSemantics.classKind(net.minecraft.client.gui.GuiSelectWorld.class))
            && "disconnect".equals(ScreenSemantics.classKind(net.minecraft.client.gui.GuiDisconnected.class)), "Native lifecycle/settings screens retain distinct public families");
        return checks;
    }

    private static JsonObject remote() {
        return ClientReadiness.healthReadiness("player", true, true, true, true, false, false, false, false, false, false, false, true);
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
