package com.Goderium.BridgeNH.common.bridge;

import com.google.gson.JsonObject;

/** Pure readiness reduction. Inputs are observed by GameBridge on its owning thread. */
@com.google.common.annotations.VisibleForTesting
public final class ClientReadiness {
    @com.google.common.annotations.VisibleForTesting
    public static void remoteReadiness(JsonObject result, boolean connected, boolean operatorReady, boolean developer) {
        boolean ready = connected && result.get("worldLoaded").getAsBoolean() && result.get("playerPresent").getAsBoolean()
                && result.get("playerHealthy").getAsBoolean() && result.get("playerWorldMatches").getAsBoolean()
                && !result.get("paused").getAsBoolean() && !result.get("lifecyclePending").getAsBoolean();
        result.addProperty("remoteClientReady", ready);
        result.addProperty("remoteServerReady", ready && operatorReady);
        result.addProperty("worldReady", ready);
        result.addProperty("developerReady", ready && operatorReady && developer);
        if (ready) {
            result.addProperty("stage", operatorReady ? "remote_world_ready" : "remote_client_ready");
            result.addProperty("nextTool", "requested_task");
        }
    }

    @com.google.common.annotations.VisibleForTesting
    public static void menuReadiness(JsonObject result, boolean screenOpen) {
        if (result.get("lifecyclePending").getAsBoolean() || result.get("worldLoaded").getAsBoolean()
                || result.get("integratedServerPresent").getAsBoolean() || result.get("menuReady").getAsBoolean() || !screenOpen) return;
        String kind = result.get("screenKind").getAsString();
        if ("connection_progress".equals(kind)) return;
        result.addProperty("stage", "disconnect".equals(kind) || "error_menu".equals(kind) ? "error_menu" : "menu_open");
        result.addProperty("nextTool", "mcb_gui");
        result.addProperty("nextToolCondition", "Inspect the actual screenId and native widgets, then use its observed normal Back/Done/Cancel control. Do not repeat load/connect or use unobserved buttons.");
    }

    /** Pure reduction of the same bounded observation, shared with offline readiness-boundary tests. */
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject healthReadiness(String profile, boolean worldLoaded, boolean playerPresent, boolean healthy,
            boolean consistent, boolean serverPresent, boolean local, boolean matches, boolean developerContext, boolean paused,
            boolean pending, boolean mainMenu, boolean backgroundActive) {
        boolean menuReady = !pending && !worldLoaded && !playerPresent && !serverPresent && mainMenu;
        boolean worldReady = !pending && !paused && worldLoaded && playerPresent && healthy && consistent && serverPresent && local && matches;
        boolean developerReady = worldReady && "developer".equals(profile) && developerContext;
        String stage = pending ? "lifecycle_pending" : menuReady ? "main_menu"
                : worldLoaded && paused ? "world_paused" : worldReady ? "world_ready"
                : serverPresent && (!worldLoaded || !playerPresent) ? "world_loading" : "world_unavailable";
        String next = menuReady ? "mcb_load_world" : !worldReady ? "mcb_health"
                : !backgroundActive ? "mcb_background" : "requested_task";
        JsonObject result = new JsonObject();
        result.addProperty("version", 1); result.addProperty("stage", stage);
        result.addProperty("bridgeReady", true); result.addProperty("menuReady", menuReady);
        result.addProperty("worldReady", worldReady); result.addProperty("developerReady", developerReady);
        result.addProperty("worldLoaded", worldLoaded); result.addProperty("playerPresent", playerPresent);
        result.addProperty("playerHealthy", healthy); result.addProperty("playerWorldMatches", consistent);
        result.addProperty("integratedServerPresent", serverPresent);
        result.addProperty("localIntegratedServer", local); result.addProperty("contextMatches", matches);
        result.addProperty("paused", paused); result.addProperty("lifecyclePending", pending);
        result.addProperty("backgroundActive", backgroundActive); result.addProperty("nextTool", next);
        result.addProperty("nextToolCondition", menuReady
                ? "Load only an authorized existing exact folder; if already known, skip worlds, otherwise use mcb_worlds."
                : "Observe pending/loading/paused state; profile/background changes and tasks require their existing authorization and guards.");
        result.addProperty("retryAction", false); result.addProperty("machineFormationProved", false);
        result.addProperty("scope", "Current readiness only; scheduled is not completed, and unknown remains unknown.");
        return result;
    }
}
