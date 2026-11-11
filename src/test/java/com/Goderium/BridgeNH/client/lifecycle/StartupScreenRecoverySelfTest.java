package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.client.gui.ScreenSemantics;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import cpw.mods.fml.client.GuiConfirmation;
import cpw.mods.fml.client.GuiNotification;
import cpw.mods.fml.common.StartupQuery;
import java.lang.reflect.Constructor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real Forge query objects; no execute(), registry changes or graphics context. */
public class StartupScreenRecoverySelfTest {
    private static int checks;

    public static int run() throws Exception {
        checks = 0;
        StartupQuery query = query("Missing mod registry entries", new AtomicBoolean(false));
        GuiConfirmation screen = new GuiConfirmation(query);
        StartupScreenRecovery recovery = new StartupScreenRecovery();
        check("confirmation".equals(ScreenSemantics.kind(screen)), "Forge query retains the known confirmation family");
        check(!recovery.observe(screen) && !recovery.restartRequired(), "Initial false is not a completed cancellation");
        check(StartupScreenRecovery.describe(screen).get("nativeResult").isJsonNull(), "Unanswered native false is never presented as a response");
        JsonObject ready = new JsonObject();
        recovery.readiness(ready, true, true);
        check("mcb_gui".equals(ready.get("nextTool").getAsString()) && !ready.get("worldReady").getAsBoolean(), "Native query breaks health-only polling even while lifecycle is pending");
        expect("startup_query_requires_choice", () -> StartupScreenRecovery.requireNativeChoice(screen));
        StartupScreenRecovery.requireNativeChoice(null);
        recovery.requireFreshClient();
        // Fixture answers through Forge's public API; the product only observes it.
        query.finish();
        check(recovery.observe(null) && recovery.restartRequired(), "Retained actual query observes completion after its screen closes");
        check(!recovery.observe(null), "Cancellation is signaled once");
        expect("client_restart_required", recovery::requireFreshClient);
        recovery.readiness(ready, true, false);
        check("startup_cancel_unwinding".equals(ready.get("stage").getAsString()) && "mcb_health".equals(ready.get("nextTool").getAsString()), "Nested load must unwind before another lifecycle action");
        recovery.readiness(ready, false, false);
        check("mcb_exit".equals(ready.get("nextTool").getAsString()) && !ready.get("menuReady").getAsBoolean(), "Completed cancellation requires normal client exit");
        check(!recovery.observe(screen) && recovery.restartRequired(), "A re-observed completed query cannot clear the process latch");

        StartupScreenRecovery fresh = new StartupScreenRecovery();
        StartupQuery positive = query("Accepted", new AtomicBoolean(true));
        check(!fresh.observe(new GuiConfirmation(positive)), "Unfinished affirmative query does not change recovery policy");
        positive.finish();
        check(!fresh.observe(null) && !fresh.restartRequired(), "An actual affirmative response does not require restart");
        StartupQuery notification = query("Notice", null);
        GuiNotification notice = new GuiNotification(notification);
        fresh.observe(notice); notification.finish();
        check(!fresh.observe(null) && !fresh.restartRequired() && "error_menu".equals(ScreenSemantics.kind(notice)), "Notification completion is distinct from negative confirmation");
        StringBuilder text = new StringBuilder(); for (int i = 0; i < 3000; i++) text.append('x');
        JsonObject described = StartupScreenRecovery.describe(new GuiConfirmation(query(text.toString(), new AtomicBoolean())));
        check(described.get("text").getAsString().length() <= 2048 && described.get("textLength").getAsInt() == 3000
                && described.get("textTruncated").getAsBoolean(), "Actual query text remains bounded with explicit coverage");
        check(!StartupScreenRecovery.describe(new Object()).get("available").getAsBoolean(), "Arbitrary screens cannot claim startup recovery");
        return checks;
    }

    private static StartupQuery query(String text, AtomicBoolean answer) throws Exception {
        Constructor<StartupQuery> constructor = StartupQuery.class.getDeclaredConstructor(String.class, AtomicBoolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(text, answer);
    }

    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), "Expected " + code); }
    }
}
