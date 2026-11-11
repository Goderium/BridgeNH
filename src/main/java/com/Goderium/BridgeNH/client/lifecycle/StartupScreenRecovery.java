package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.StartupQuery;
import java.util.concurrent.CountDownLatch;

/** Observes real Forge startup queries; never answers, resets or finishes one. */
public final class StartupScreenRecovery {
    static final String NOTIFICATION = "cpw.mods.fml.client.GuiNotification";
    private StartupQuery pending;
    private boolean restartRequired;
    private String observationError;

    public static boolean startupScreen(Object screen) { return ExpandedGuiApi.is(screen, NOTIFICATION); }
    static boolean confirmation(Object screen) { return ExpandedGuiApi.is(screen, "cpw.mods.fml.client.GuiConfirmation"); }

    static StartupQuery query(Object screen) {
        Object actual = ExpandedGuiApi.field(screen, NOTIFICATION, "query");
        if (!(actual instanceof StartupQuery)) throw new BridgeException("unsupported", "Actual Forge startup query is unavailable.");
        return (StartupQuery) actual;
    }

    private static boolean finished(StartupQuery query) {
        // Forge exposes no completion getter. Read its fixed native latch only;
        // false is the initial answer as well, so the answer alone proves nothing.
        Object signal = ExpandedGuiApi.field(query, "cpw.mods.fml.common.StartupQuery", "signal");
        if (!(signal instanceof CountDownLatch)) throw new BridgeException("unsupported", "Native startup query completion is unavailable.");
        return ((CountDownLatch) signal).getCount() == 0;
    }

    /** True once when an actual completed negative native query is observed. */
    public boolean observe(Object screen) {
        try {
            if (startupScreen(screen)) pending = query(screen);
            if (pending == null || !finished(pending)) return false;
            boolean canceled = Boolean.FALSE.equals(pending.getResult());
            pending = null;
            if (canceled && !restartRequired) { restartRequired = true; return true; }
        } catch (RuntimeException | LinkageError unavailable) {
            observationError = unavailable.getClass().getSimpleName();
        }
        return false;
    }

    public boolean restartRequired() { return restartRequired; }

    public static void requireNativeChoice(Object screen) {
        if (startupScreen(screen)) throw new BridgeException("startup_query_requires_choice",
            "This Forge startup query must be answered through an observed native button. Closing it or sending Escape bypasses its completion signal and can strand loading.");
    }

    public void requireFreshClient() {
        if (restartRequired) throw new BridgeException("client_restart_required",
            "A native startup confirmation was canceled. Restart this Minecraft client before loading or connecting again; do not retry another world or restart Codex/MCP for this game-state condition.");
    }

    public JsonObject inspect() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1);
        value.addProperty("restartRequired", restartRequired);
        value.addProperty("reason", restartRequired ? "native_startup_confirmation_canceled" : "none_observed");
        value.addProperty("source", "Actual StartupQuery identity, public answer and completed native latch; no mod-specific text matching or registry mutation.");
        value.addProperty("policy", "After cancellation restart the client before another world/connection; no automatic accept, retry or registry reset.");
        if (observationError != null) value.addProperty("observationError", observationError);
        return value;
    }

    public static JsonObject describe(Object screen) {
        JsonObject value = new JsonObject();
        if (!startupScreen(screen)) { value.addProperty("available", false); return value; }
        try {
            StartupQuery query = query(screen); String text = query.getText();
            boolean done = finished(query);
            value.addProperty("available", true); value.addProperty("confirmation", confirmation(screen));
            value.addProperty("text", JsonData.bounded(text == null ? "" : text, 2048));
            value.addProperty("textLength", text == null ? 0 : text.length());
            value.addProperty("textTruncated", text != null && text.length() > 2048);
            value.addProperty("finished", done); value.addProperty("synchronous", query.isSynchronous());
            if (done && query.getResult() != null) value.addProperty("nativeResult", query.getResult());
            else value.add("nativeResult", JsonNull.INSTANCE);
            if (confirmation(screen)) { value.addProperty("cancelButtonId", 1); value.addProperty("continueButtonId", 0); }
            else value.addProperty("doneButtonId", 0);
            value.addProperty("source", "Current native Forge query text and callback-defined button IDs; use current observed widget identity for actions.");
        } catch (RuntimeException | LinkageError unavailable) {
            value.addProperty("available", false); value.addProperty("reason", unavailable.getClass().getSimpleName());
        }
        return value;
    }

    public void readiness(JsonObject value, boolean pendingLifecycle, boolean queryOpen) {
        value.add("clientRecovery", inspect());
        if (queryOpen && !restartRequired) {
            value.addProperty("menuReady", false); value.addProperty("worldReady", false); value.addProperty("developerReady", false);
            value.addProperty("stage", "startup_query"); value.addProperty("nextTool", "mcb_gui");
            value.addProperty("nextToolCondition", "Inspect the actual Forge startup query text and current native button identity. A pending lifecycle is waiting for this choice; do not repeat load/connect or close the query with Escape.");
        }
        if (!restartRequired) return;
        value.addProperty("menuReady", false); value.addProperty("worldReady", false); value.addProperty("developerReady", false);
        value.addProperty("stage", pendingLifecycle ? "startup_cancel_unwinding" : "client_restart_required");
        value.addProperty("nextTool", pendingLifecycle ? "mcb_health" : "mcb_exit");
        value.addProperty("nextToolCondition", pendingLifecycle
            ? "Wait for the existing native loading call to unwind; do not load another world or schedule another lifecycle action."
            : "Exit this client normally, restore the intended mod set, launch it again and observe its new session before loading the original save.");
    }
}
