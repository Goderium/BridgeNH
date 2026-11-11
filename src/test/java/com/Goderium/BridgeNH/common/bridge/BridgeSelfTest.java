package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.client.control.BackgroundControl;
import com.Goderium.BridgeNH.client.control.ClientControlCommand;
import com.Goderium.BridgeNH.client.control.InputDiagnosticsSelfTest;
import com.Goderium.BridgeNH.client.control.OwnedKeyStateSelfTest;
import com.Goderium.BridgeNH.client.gui.GuiCallbacks;
import com.Goderium.BridgeNH.client.gui.GuiConfirmationSelfTest;
import com.Goderium.BridgeNH.client.gui.GuiControlsSelfTest;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.client.gui.GuiObservationWaitSelfTest;
import com.Goderium.BridgeNH.client.gui.LogicalGuiPointerSelfTest;
import com.Goderium.BridgeNH.client.gui.PersonalSpaceGuiSelfTest;
import com.Goderium.BridgeNH.client.gui.PhantomGuiSelfTest;
import com.Goderium.BridgeNH.client.gui.ScreenSemantics;
import com.Goderium.BridgeNH.client.gui.VanillaSettingsControlsSelfTest;
import com.Goderium.BridgeNH.client.lifecycle.ClientEnvironmentSelfTest;
import com.Goderium.BridgeNH.client.lifecycle.LifecycleTasks;
import com.Goderium.BridgeNH.client.lifecycle.NativeMenuListsSelfTest;
import com.Goderium.BridgeNH.client.lifecycle.StartupScreenRecoverySelfTest;
import com.Goderium.BridgeNH.client.lifecycle.WorldLifecycle;
import com.Goderium.BridgeNH.client.navigation.ApproachPlannerSelfTest;
import com.Goderium.BridgeNH.client.navigation.CrosshairArrivalSelfTest;
import com.Goderium.BridgeNH.client.navigation.FlightPathPlannerSelfTest;
import com.Goderium.BridgeNH.client.navigation.FollowTargetSelfTest;
import com.Goderium.BridgeNH.client.navigation.GroundedStepSelfTest;
import com.Goderium.BridgeNH.client.navigation.MultipartInteractionSelfTest;
import com.Goderium.BridgeNH.client.navigation.PlayerNavigationSelfTest;
import com.Goderium.BridgeNH.client.navigation.WalkingPathPlannerSelfTest;
import com.Goderium.BridgeNH.client.navigation.WalkingSupportSelfTest;
import com.Goderium.BridgeNH.client.navigation.WalkingSweepProofSelfTest;
import com.Goderium.BridgeNH.client.nei.ExpandedGtSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiDataSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiDiagramSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiGeneratedDiagramSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiHoverSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiItemControlSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiItemVisualSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiNativeControlSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiNaturalRenderSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiPreviewDetailSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiPreviewSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiRenderedSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiSemanticsSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiTextureSelfTest;
import com.Goderium.BridgeNH.client.nei.NeiViewSelfTest;
import com.Goderium.BridgeNH.client.nei.TooltipModifiersSelfTest;
import com.Goderium.BridgeNH.client.recording.ClientMessages;
import com.Goderium.BridgeNH.client.recording.PlayerActivityLogSelfTest;
import com.Goderium.BridgeNH.common.ae.AeGoalsSelfTest;
import com.Goderium.BridgeNH.common.ae.AeNativeControlsSelfTest;
import com.Goderium.BridgeNH.common.ae.AeObservationSelfTest;
import com.Goderium.BridgeNH.common.ae.AePhantomSlotsSelfTest;
import com.Goderium.BridgeNH.common.ae.AeSemanticsSelfTest;
import com.Goderium.BridgeNH.common.ae.CellWorkbenchSelfTest;
import com.Goderium.BridgeNH.common.dialogue.AgentDialogueSelfTest;
import com.Goderium.BridgeNH.common.inventory.ClientCatalog;
import com.Goderium.BridgeNH.common.inventory.FluidContainerDataSelfTest;
import com.Goderium.BridgeNH.common.inventory.InspectManyPlayerInventorySelfTest;
import com.Goderium.BridgeNH.common.inventory.NativeHotbarSwapSelfTest;
import com.Goderium.BridgeNH.common.inventory.NativeSupplySelfTest;
import com.Goderium.BridgeNH.common.inventory.PlayerSlotBindingSelfTest;
import com.Goderium.BridgeNH.common.inventory.WholeStackTransferSelfTest;
import com.Goderium.BridgeNH.common.machine.BasicMachineFluidSelfTest;
import com.Goderium.BridgeNH.common.machine.BasicMachineRuntimeProofSelfTest;
import com.Goderium.BridgeNH.common.machine.MachineEnergySelfTest;
import com.Goderium.BridgeNH.common.machine.MachineObservationCaptureSelfTest;
import com.Goderium.BridgeNH.common.machine.MachineSlotBindingSelfTest;
import com.Goderium.BridgeNH.common.machine.MachineSlotPurposeSelfTest;
import com.Goderium.BridgeNH.common.machine.ModularMachineSelfTest;
import com.Goderium.BridgeNH.common.machine.WailaCaptureSelfTest;
import com.Goderium.BridgeNH.common.qa.PrivateDimensionQaSelfTest;
import com.Goderium.BridgeNH.common.qa.QaSelfTest;
import com.Goderium.BridgeNH.common.recipe.OutputResourceQuerySelfTest;
import com.Goderium.BridgeNH.common.recipe.RecipeQuerySelfTest;
import com.Goderium.BridgeNH.common.recipe.ResearchLinkSelfTest;
import com.Goderium.BridgeNH.common.structure.BlueprintObservationSelfTest;
import com.Goderium.BridgeNH.common.structure.NativePlacementSelfTest;
import com.Goderium.BridgeNH.common.structure.OwnedBlockReplacementSelfTest;
import com.Goderium.BridgeNH.common.structure.PlacementAfterObservationSelfTest;
import com.Goderium.BridgeNH.common.structure.StructureAlignmentFramesSelfTest;
import com.Goderium.BridgeNH.common.structure.StructureDiscoverySelfTest;
import com.Goderium.BridgeNH.common.structure.StructuredInfoSelfTest;
import com.Goderium.BridgeNH.common.world.ExpandedObservationSelfTest;
import com.Goderium.BridgeNH.common.world.WorldBlockCacheSelfTest;
import com.Goderium.BridgeNH.common.world.WorldInspectManySelfTest;
import com.Goderium.BridgeNH.common.world.WorldScanTests;
import com.Goderium.BridgeNH.config.FlightPolicy;
import com.Goderium.BridgeNH.config.LoopbackPortSelfTest;
import com.Goderium.BridgeNH.config.OperationPolicies;
import com.Goderium.BridgeNH.config.OperationPolicySelfTest;
import com.Goderium.BridgeNH.config.ScanSettings;
import com.Goderium.BridgeNH.config.TeleportPolicy;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.network.RemoteQaSelfTest;
import com.Goderium.BridgeNH.network.RemoteReadSelfTest;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.CatalogData;
import com.Goderium.BridgeNH.util.TickSamples;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Real loopback HTTP and scheduler tests, with game operations replaced by a fixture. */
public class BridgeSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        checks += OperationPolicySelfTest.run();
        WorldScanTests.coldProbe();
        schedulerTests();
        renderQueueTests();
        pausedDispatchTests();
        lifecycleTests();
        lifecycleInputPolicyTests();
        checks += ClientEnvironmentSelfTest.run();
        checks += StartupScreenRecoverySelfTest.run();
        checks += AeNativeControlsSelfTest.run();
        controlStateTests();
        localCommandTests();
        checks += AgentDialogueSelfTest.run();
        checks += PlayerActivityLogSelfTest.run();
        checks += WorldScanTests.run();
        checks += WorldBlockCacheSelfTest.run();
        checks += StructuredInfoSelfTest.run();
        checks += StructureDiscoverySelfTest.run();
        checks += StructureAlignmentFramesSelfTest.run();
        checks += BlueprintObservationSelfTest.run();
        checks += WorldInspectManySelfTest.run();
        checks += InspectManyPlayerInventorySelfTest.run();
        checks += NativeObservationWaitSelfTest.run();
        checks += GuiObservationWaitSelfTest.run();
        checks += WalkingSweepProofSelfTest.run();
        checks += ModularMachineSelfTest.run();
        checks += MachineEnergySelfTest.run();
        checks += MachineObservationCaptureSelfTest.run();
        checks += NeiDataSelfTest.run();
        checks += NeiViewSelfTest.run();
        checks += NeiItemControlSelfTest.run();
        checks += NativeHotbarSwapSelfTest.run();
        checks += NeiNativeControlSelfTest.run();
        checks += com.Goderium.BridgeNH.client.nei.NeiBookmarkSelfTest.run();
        checks += NeiSemanticsSelfTest.run();
        checks += ResearchLinkSelfTest.run();
        checks += NeiDiagramSelfTest.run();
        checks += NeiGeneratedDiagramSelfTest.run();
        checks += NeiRenderedSelfTest.run();
        checks += TooltipModifiersSelfTest.run();
        checks += NeiHoverSelfTest.run();
        checks += LogicalGuiPointerSelfTest.run();
        checks += NeiNaturalRenderSelfTest.run();
        checks += NeiPreviewSelfTest.run();
        checks += NeiPreviewDetailSelfTest.run();
        checks += NeiTextureSelfTest.run();
        checks += NeiItemVisualSelfTest.run();
        checks += RecipeQuerySelfTest.run();
        checks += ExpandedGtSelfTest.run();
        checks += WailaCaptureSelfTest.run();
        checks += ExpandedObservationSelfTest.run();
        checks += AeSemanticsSelfTest.run();
        checks += AeGoalsSelfTest.run();
        checks += CellWorkbenchSelfTest.run();
        checks += AePhantomSlotsSelfTest.run();
        checks += AeObservationSelfTest.run();
        checks += GuiControlsSelfTest.run();
        checks += PersonalSpaceGuiSelfTest.run();
        checks += VanillaSettingsControlsSelfTest.run();
        checks += NativeMenuListsSelfTest.run();
        checks += PhantomGuiSelfTest.run();
        checks += GuiConfirmationSelfTest.run();
        checks += NativePlacementSelfTest.run();
        checks += PlacementAfterObservationSelfTest.run();
        checks += ApproachPlannerSelfTest.run();
        checks += PlayerNavigationSelfTest.run();
        checks += FollowTargetSelfTest.run();
        checks += WalkingSupportSelfTest.run();
        checks += CrosshairArrivalSelfTest.run();
        checks += MultipartInteractionSelfTest.run();
        checks += RemoteReadSelfTest.run();
        checks += RemoteQaSelfTest.run();
        checks += GroundedStepSelfTest.run();
        checks += InputDiagnosticsSelfTest.run();
        checks += OwnedKeyStateSelfTest.run();
        WalkingPathPlannerSelfTest.run();
        FlightPathPlannerSelfTest.run();
        checks += OwnedBlockReplacementSelfTest.run();
        checks += QaSelfTest.run();
        checks += PrivateDimensionQaSelfTest.run();
        registryTests();
        registryHttpTests();
        httpTests();
        checks += LoopbackPortSelfTest.run();
        checks += GuardedRpcSelfTest.run();
        checks += PlayerSlotBindingSelfTest.run();
        checks += MachineSlotBindingSelfTest.run();
        checks += MachineSlotPurposeSelfTest.run();
        checks += BasicMachineRuntimeProofSelfTest.run();
        checks += OutputResourceQuerySelfTest.run();
        checks += FluidContainerDataSelfTest.run();
        checks += BasicMachineFluidSelfTest.run();
        checks += NativeSupplySelfTest.run();
        checks += WholeStackTransferSelfTest.run();
        muiCoordinatePolicyTest();
        catalogAndMessageTests();
        tickSampleTests();
        teleportPolicyTests();
        flightPolicyTests();
        interactionViewPolicyTests();
        screenSemanticsTests();
        System.out.println("Bridge self-test passed: " + checks + " assertions; Minecraft operations are mocked.");
    }

    private static void lifecycleTests() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicLong clock = new AtomicLong();
        LifecycleTasks tasks = new LifecycleTasks(clock::get);
        check("idle".equals(tasks.inspect().get("status").getAsString()) && !tasks.active(), "lifecycle starts idle");
        GameQueue.Control first = new GameQueue.Control();
        JsonObject scheduled = tasks.schedule("load", "session", first, () -> true, actions::incrementAndGet, complete::get);
        check("scheduled".equals(scheduled.get("status").getAsString()) && scheduled.has("ticketId"), "lifecycle returns a scheduled ticket");
        tasks.tick();
        check(actions.get() == 0 && "awaiting_response".equals(tasks.inspect().get("status").getAsString()), "lifecycle action waits for HTTP acknowledgement");
        expectCode("busy", () -> tasks.schedule("second", "session", new GameQueue.Control(), () -> true, () -> { }, () -> true));
        tasks.responseFinished(new GameQueue.Control(), true);
        tasks.tick();
        check(actions.get() == 0, "unrelated HTTP completion cannot release lifecycle action");
        tasks.responseFinished(first, true);
        tasks.tick();
        check(actions.get() == 1 && "awaiting_world".equals(tasks.inspect().get("status").getAsString()), "acknowledged action runs once and waits for actual world readiness");
        tasks.tick();
        check(actions.get() == 1 && tasks.active(), "joining world does not rerun lifecycle action");
        complete.set(true);
        tasks.tick();
        tasks.tick();
        check(actions.get() == 1 && "completed".equals(tasks.inspect().get("status").getAsString()) && !tasks.active(), "lifecycle records completion and stays terminal");

        GameQueue.Control lost = new GameQueue.Control();
        tasks.schedule("lost", "session", lost, () -> true, actions::incrementAndGet, () -> true);
        tasks.responseFinished(lost, false);
        tasks.responseFinished(lost, true);
        tasks.tick();
        check(actions.get() == 1 && "cancelled".equals(tasks.inspect().get("status").getAsString()), "lost response cancels action permanently");
        GameQueue.Control changed = new GameQueue.Control();
        tasks.schedule("changed", "session", changed, () -> false, actions::incrementAndGet, () -> true);
        tasks.responseFinished(changed, true);
        tasks.tick();
        check(actions.get() == 1 && "cancelled".equals(tasks.inspect().get("status").getAsString()) && "stale_world".equals(errorCode(tasks.inspect())), "changed-world guard cancels before action");
        tasks.schedule("expired", "session", new GameQueue.Control(), () -> true, actions::incrementAndGet, () -> true);
        clock.addAndGet(15000000001L);
        tasks.tick();
        check(actions.get() == 1 && "cancelled".equals(tasks.inspect().get("status").getAsString()), "missing response acknowledgement expires without game action");

        GameQueue.Control waiting = new GameQueue.Control();
        tasks.schedule("waiting", "session", waiting, () -> true, actions::incrementAndGet, () -> false);
        tasks.responseFinished(waiting, true);
        tasks.tick();
        clock.addAndGet(60000000001L);
        tasks.tick();
        check(actions.get() == 2 && "failed".equals(tasks.inspect().get("status").getAsString()) && "timeout".equals(errorCode(tasks.inspect())), "world-readiness timeout reports unknown started outcome without retry");
        GameQueue.Control failed = new GameQueue.Control();
        tasks.schedule("failed", "session", failed, () -> true, () -> { throw new BridgeException("invalid_state", "fixture"); }, () -> true);
        tasks.responseFinished(failed, true);
        tasks.tick();
        check("failed".equals(tasks.inspect().get("status").getAsString()) && "invalid_state".equals(errorCode(tasks.inspect())), "action failure is retained on lifecycle ticket");

        for (boolean throwsAfterCancel : new boolean[] {false, true}) {
            GameQueue.Control cancel = new GameQueue.Control();
            tasks.schedule("client.load_world", "session", cancel, () -> true, () -> {
                tasks.startupCanceled();
                check(tasks.active() && "running".equals(tasks.inspect().get("status").getAsString()), "Cancellation retains the running lifecycle until the native stack unwinds");
                try {
                    tasks.schedule("client.exit", "session", new GameQueue.Control(), () -> true, () -> { }, () -> true);
                    throw new AssertionError("A nested cancellation cannot schedule exit before the native load returns");
                } catch (BridgeException busy) { check("busy".equals(busy.code), "Native unwinding retains lifecycle exclusivity"); }
                if (throwsAfterCancel) throw new BridgeException("lifecycle_failed", "native startup returned without a server");
            }, () -> { throw new AssertionError("Canceled startup must not check world completion"); });
            tasks.responseFinished(cancel, true); tasks.tick();
            check(!tasks.active() && "cancelled".equals(tasks.inspect().get("status").getAsString()) && "startup_cancelled".equals(errorCode(tasks.inspect())), "Native cancellation is terminal after return or exception, without a spurious startup failure");
        }
        GameQueue.Control awaiting = new GameQueue.Control();
        tasks.schedule("client.load_world", "session", awaiting, () -> true, () -> { }, () -> false);
        tasks.responseFinished(awaiting, true); tasks.tick(); tasks.startupCanceled();
        check(!tasks.active() && "startup_cancelled".equals(errorCode(tasks.inspect())), "A query completed after action return cancels awaiting-world state immediately");
    }

    private static void lifecycleInputPolicyTests() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("folder", "Codex Bridge Test");
        check("Codex Bridge Test".equals(WorldLifecycle.folder(args)), "existing-world selector preserves exact folder names");
        for (String folder : new String[] {"", ".", "..", "../world", "world/save", "world\\save", "D:world", "bad\u0000name"}) {
            args.addProperty("folder", folder);
            expectCode("invalid_args", () -> WorldLifecycle.folder(args));
        }
        args.remove("folder");
        expectCode("invalid_args", () -> WorldLifecycle.folder(args));
        BackgroundControl.requireUngrabbed(false);
        check(true, "background activation accepts an ungrabbed mouse without native setters");
        expectCode("mouse_grabbed", () -> BackgroundControl.requireUngrabbed(true));
        lumien.custommainmenu.configuration.GuiConfig config = new lumien.custommainmenu.configuration.GuiConfig();
        config.name = "mainmenu";
        check(WorldLifecycle.mainMenu(new lumien.custommainmenu.gui.GuiCustom(config)), "known CustomMainMenu mainmenu identity is accepted");
        config.name = "settings";
        check(!WorldLifecycle.mainMenu(new lumien.custommainmenu.gui.GuiCustom(config)), "other CustomMainMenu pages are rejected");
        config.name = "mainmenu_normal";
        check(!WorldLifecycle.mainMenu(new lumien.custommainmenu.gui.GuiCustom(config)), "only the normalized public mainmenu identity is accepted");
        config.name = "mainmenu";
        check(!WorldLifecycle.mainMenu(new UnknownCustomMenu(config)), "unknown subclasses cannot bypass the fixed custom-menu class gate");
        check(!WorldLifecycle.mainMenu(new lumien.custommainmenu.gui.GuiCustom(null)), "missing custom-menu config is rejected");
        check(!WorldLifecycle.mainMenu(null) && !WorldLifecycle.mainMenu(new Object()), "null and arbitrary screens are rejected as lifecycle menus");
        BackgroundControl.PassiveMouseHelper helper = new BackgroundControl.PassiveMouseHelper();
        for (Runnable method : new Runnable[] {helper::grabMouseCursor, helper::ungrabMouseCursor, helper::mouseXYChange}) {
            helper.deltaX = 15;
            helper.deltaY = -8;
            method.run();
            check(helper.deltaX == 0 && helper.deltaY == 0, "passive mouse callback clears deltas without requiring native Mouse initialization");
        }
    }

    private static void schedulerTests() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        GameQueue queue = new GameQueue(1);
        GameQueue.Ticket<Integer> first = queue.submit(actions::incrementAndGet, 1000);
        expectCode("busy", () -> queue.submit(actions::incrementAndGet, 1000));
        check(first.cancelBeforeExecution(), "queued cancellation is acknowledged");
        GameQueue.Ticket<Integer> next = queue.submit(actions::incrementAndGet, 1000);
        queue.drain(4);
        check(queue.await(next, 1000) == 1, "cancelled ticket frees capacity without a game tick");
        check(actions.get() == 1, "cancelled action never executes");

        GameQueue.Control preCancelled = new GameQueue.Control();
        preCancelled.cancelBeforeExecution();
        GameQueue.Ticket<Integer> pre = queue.submit(actions::incrementAndGet, 1000, preCancelled);
        queue.drain(4);
        expectExecutionCode("cancelled", () -> queue.await(pre, 1000));
        check(actions.get() == 1, "pre-submit cancellation never executes");

        GameQueue.Ticket<Integer> expired = queue.submit(actions::incrementAndGet, 0);
        queue.drain(4);
        expectExecutionCode("cancelled", () -> queue.await(expired, 1000));
        check(actions.get() == 1, "expired ticket never executes");

        GameQueue.Ticket<Integer> timedOut = queue.submit(actions::incrementAndGet, 1000);
        expectCode("timeout", () -> queue.await(timedOut, 0));
        queue.drain(4);
        check(actions.get() == 1, "await timeout cancels queued action");

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        GameQueue.Control running = new GameQueue.Control();
        GameQueue.Ticket<Integer> active = queue.submit(() -> {
            started.countDown();
            if (!finish.await(2, TimeUnit.SECONDS)) throw new AssertionError("test runner did not release action");
            return actions.incrementAndGet();
        }, 1000, running);
        Thread drainer = new Thread(() -> queue.drain(1), "queue fixture");
        drainer.start();
        check(started.await(2, TimeUnit.SECONDS), "fixture action started");
        check(!running.cancelBeforeExecution(), "running action cannot claim cancellation");
        finish.countDown();
        drainer.join(2000);
        check(queue.await(active, 1000) == 2, "running action completes exactly once");

        for (int i = 0; i < 200; i++) {
            AtomicInteger called = new AtomicInteger();
            GameQueue raceQueue = new GameQueue(1);
            GameQueue.Control control = new GameQueue.Control();
            raceQueue.submit(called::incrementAndGet, 1000, control);
            Thread execution = new Thread(() -> raceQueue.drain(1));
            execution.start();
            boolean cancelled = control.cancelBeforeExecution();
            execution.join(2000);
            check(cancelled ? called.get() == 0 : called.get() == 1, "cancel/start race never executes an acknowledged cancellation");
        }
    }

    private static void renderQueueTests() throws Exception {
        GameQueue queue = new GameQueue(4);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger preparations = new AtomicInteger();
        AtomicBoolean ready = new AtomicBoolean(false);
        queue.drainIf(ready::get, preparations::incrementAndGet);
        check(preparations.get() == 0, "empty render queue does not refresh context");
        GameQueue.Ticket<Integer> first = queue.submit(writes::incrementAndGet, 1000);
        GameQueue.Ticket<Integer> second = queue.submit(writes::incrementAndGet, 1000);
        queue.drainIf(ready::get, preparations::incrementAndGet);
        check(writes.get() == 0 && preparations.get() == 0, "ineligible render leaves client work for the normal tick");
        ready.set(true);
        queue.drainIf(ready::get, () -> { preparations.incrementAndGet(); ready.set(false); });
        check(writes.get() == 0, "context refresh losing eligibility cannot invoke a queued native action");
        ready.set(true);
        queue.drainIf(ready::get, preparations::incrementAndGet);
        check(queue.await(first, 1000) == 1 && writes.get() == 1, "one eligible render invokes exactly one existing ticket");
        queue.drainIf(ready::get, preparations::incrementAndGet);
        check(queue.await(second, 1000) == 2 && writes.get() == 2, "following render advances the next ticket without replay");
        int prepared = preparations.get();
        queue.drainIf(ready::get, preparations::incrementAndGet);
        check(preparations.get() == prepared, "drained render queue avoids all further context work");
        GameQueue.Ticket<Integer> cancelled = queue.submit(writes::incrementAndGet, 1000);
        cancelled.cancelBeforeExecution();
        queue.drainIf(ready::get, preparations::incrementAndGet);
        expectExecutionCode("cancelled", () -> queue.await(cancelled, 1000));
        check(writes.get() == 2, "render opportunity preserves acknowledged cancellation");

        ControlAccess client = new ControlAccess();
        BackgroundControl controls = new BackgroundControl();
        check(!controls.active(client), "manual player mode never permits agent render pump");
        controls.change(client, "background", true, false, () -> { });
        check(controls.active(client), "full actual passive helper and unfocused mode permit pump");
        client.grabbed = true;
        check(!controls.active(client), "native grabbed mouse disables pump even with full configured mode");
        client.grabbed = false;
        client.pause = true;
        check(!controls.active(client), "actual pause setting disables render pump");
        controls.tick(client);
        client.helper = new net.minecraft.util.MouseHelper();
        check(!controls.active(client), "foreign mouse helper disables render pump immediately");
    }

    private static void controlStateTests() throws Exception {
        BackgroundControl controls = new BackgroundControl();
        ControlAccess client = new ControlAccess();
        net.minecraft.util.MouseHelper original = client.helper;
        AtomicInteger releases = new AtomicInteger();
        controls.tick(client);
        check(client.helper == original && client.pause && client.focusClears == 0, "default controls preserve vanilla mouse, pause setting and focus");
        check(controls.inspect(client).get("mouseEnabled").getAsBoolean() && !controls.inspect(client).get("unfocusedEnabled").getAsBoolean(), "default state reports manual mouse and no unfocused override");
        expectCode("background_required", () -> controls.requireLifecycle(client));
        controls.change(client, "mouse", false, false, releases::incrementAndGet);
        net.minecraft.util.MouseHelper protectedMouse = client.helper;
        check(protectedMouse != original && client.pause && !controls.inspect(client).get("enabled").getAsBoolean(), "mouse protection alone preserves pause behavior");
        expectCode("background_required", () -> controls.requireLifecycle(client));
        controls.change(client, "unfocused", true, false, releases::incrementAndGet);
        controls.requireLifecycle(client);
        check(client.helper == protectedMouse && !client.pause && controls.inspect(client).get("enabled").getAsBoolean(), "independent unfocused switch completes full background mode");
        controls.change(client, "unfocused", false, false, releases::incrementAndGet);
        check(client.helper == protectedMouse && client.pause && !controls.inspect(client).get("unfocusedEnabled").getAsBoolean(), "unfocused off restores prior pause without restoring mouse");
        controls.change(client, "unfocused", true, false, releases::incrementAndGet);
        controls.change(client, "mouse", true, false, releases::incrementAndGet);
        check(client.helper == original && !client.pause && controls.inspect(client).get("mouseEnabled").getAsBoolean() && controls.inspect(client).get("unfocusedEnabled").getAsBoolean(), "mouse on restores exact original helper without disabling unfocused running");
        int clears = client.focusClears;
        controls.tick(client);
        check(client.focusClears == clears && client.helper == original, "unfocused-only mode never suppresses manual focus or mouse");
        expectCode("background_required", () -> controls.requireLifecycle(client));
        controls.change(client, "unfocused", false, false, releases::incrementAndGet);
        check(client.pause && client.helper == original && !controls.inspect(client).get("enabled").getAsBoolean(), "four control combinations return to original settings");

        controls.change(client, "background", true, false, releases::incrementAndGet);
        protectedMouse = client.helper;
        controls.change(client, "background", true, false, releases::incrementAndGet);
        check(client.helper == protectedMouse && controls.inspect(client).get("originalPauseOnLostFocus").getAsBoolean(), "repeated legacy enable keeps exact helper and original pause snapshot");
        final int releasesBefore = releases.get();
        final net.minecraft.util.MouseHelper pendingMouse = client.helper;
        expectCode("busy", () -> controls.change(client, "mouse", true, true, releases::incrementAndGet));
        expectCode("busy", () -> controls.change(client, "unfocused", false, true, releases::incrementAndGet));
        expectCode("busy", () -> controls.change(client, "background", false, true, releases::incrementAndGet));
        check(client.helper == pendingMouse && !client.pause && releases.get() == releasesBefore, "pending lifecycle rejects all protection-removing choices without side effects");
        controls.change(client, "mouse", false, true, releases::incrementAndGet);
        controls.requireLifecycle(client);
        check(controls.inspect(client).get("enabled").getAsBoolean(), "pending lifecycle accepts keeping mouse protection on");
        client.grabbed = true;
        expectCode("mouse_grabbed", () -> controls.requireLifecycle(client));
        client.grabbed = false;
        client.pause = true;
        check(!controls.inspect(client).get("enabled").getAsBoolean(), "full mode status detects a pause override broken by external code");
        expectCode("background_required", () -> controls.requireLifecycle(client));
        controls.tick(client);
        controls.restoreForExit(client, releases::incrementAndGet);
        check(client.helper == original && client.pause && !controls.inspect(client).get("unfocusedEnabled").getAsBoolean(), "controlled exit restores both original settings through the same transition logic");

        ControlAccess initiallyRunning = new ControlAccess();
        initiallyRunning.pause = false;
        BackgroundControl preserved = new BackgroundControl();
        preserved.change(initiallyRunning, "unfocused", true, false, () -> { });
        preserved.change(initiallyRunning, "unfocused", false, false, () -> { });
        check(!initiallyRunning.pause, "unfocused off restores original false rather than forcing pause on");

        ControlAccess grabbed = new ControlAccess();
        grabbed.grabbed = true;
        BackgroundControl guarded = new BackgroundControl();
        net.minecraft.util.MouseHelper grabbedMouse = grabbed.helper;
        expectCode("mouse_grabbed", () -> guarded.change(grabbed, "background", true, false, () -> { throw new AssertionError("must validate before releasing input"); }));
        check(grabbed.helper == grabbedMouse && grabbed.pause && !guarded.inspect(grabbed).get("unfocusedEnabled").getAsBoolean(), "grab rejection leaves both legacy settings unchanged");
        guarded.change(grabbed, "unfocused", true, false, () -> { });
        check(grabbed.helper == grabbedMouse && !grabbed.pause, "unfocused-only control does not require native mouse release");
        expectCode("mouse_grabbed", () -> guarded.change(grabbed, "mouse", false, false, () -> { }));
        guarded.change(grabbed, "unfocused", false, false, () -> { });

        ControlAccess failed = new ControlAccess();
        BackgroundControl atomic = new BackgroundControl();
        net.minecraft.util.MouseHelper failedOriginal = failed.helper;
        failed.failPauseOnce = true;
        expectCode("background_unavailable", () -> atomic.change(failed, "background", true, false, () -> { }));
        check(failed.helper == failedOriginal && failed.pause && atomic.inspect(failed).get("mouseEnabled").getAsBoolean() && !atomic.inspect(failed).get("unfocusedEnabled").getAsBoolean(), "failure after helper installation rolls back helper, pause and selected state");
        atomic.change(failed, "background", true, false, () -> { });
        net.minecraft.util.MouseHelper failedPassive = failed.helper;
        failed.failPauseOnce = true;
        expectCode("background_unavailable", () -> atomic.change(failed, "background", false, false, () -> { }));
        check(failed.helper == failedPassive && !failed.pause && atomic.inspect(failed).get("enabled").getAsBoolean(), "failed release rolls back to the complete previous background mode");

        net.minecraft.util.MouseHelper thirdParty = new net.minecraft.util.MouseHelper();
        failed.helper = thirdParty;
        int priorClears = failed.focusClears;
        atomic.tick(failed);
        check(failed.helper == thirdParty && failed.focusClears == priorClears && atomic.inspect(failed).get("mouseEnabled").getAsBoolean()
                && atomic.inspect(failed).get("helperConflict").getAsBoolean() && !atomic.inspect(failed).get("enabled").getAsBoolean(), "external helper replacement is reported as real manual mouse and never unfocused through foreign helper");
        expectCode("invalid_state", () -> atomic.change(failed, "background", false, false, () -> { }));
        expectCode("invalid_state", () -> atomic.change(failed, "mouse", true, false, () -> { }));
        check(failed.helper == thirdParty && !failed.pause && atomic.inspect(failed).get("unfocusedEnabled").getAsBoolean(), "third-party guard leaves both settings untouched on failed combined release");
        atomic.change(failed, "unfocused", false, false, () -> { });
        check(failed.helper == thirdParty && failed.pause, "independent unfocused off can restore pause while preserving a foreign helper");
    }

    private static void pausedDispatchTests() throws Exception {
        GameBridge bridge = new GameBridge();
        java.nio.file.Path policyFile = java.nio.file.Files.createTempFile("mcb-paused-policy-", ".json");
        java.nio.file.Files.write(policyFile, OperationPolicies.defaults().toString().getBytes(StandardCharsets.UTF_8));
        bridge.policies.initialize(policyFile);
        JsonObject choice = new JsonObject(); choice.addProperty("scope", "defaults"); choice.addProperty("mode", "survival_efficient");
        bridge.policies.update(choice); java.nio.file.Files.delete(policyFile);
        Class<?> contextType = Class.forName("com.Goderium.BridgeNH.common.bridge.GameBridge$ServerContext");
        Constructor<?> constructor = contextType.getDeclaredConstructor(net.minecraft.server.integrated.IntegratedServer.class,
                int.class, boolean.class, UUID.class, boolean.class, boolean.class, long.class);
        constructor.setAccessible(true);
        Field context = GameBridge.class.getDeclaredField("serverContext");
        Field profile = GameBridge.class.getDeclaredField("profile");
        Field cancellation = GameQueue.Control.class.getDeclaredField("cancellation");
        context.setAccessible(true);
        profile.setAccessible(true);
        cancellation.setAccessible(true);
        String[] alwaysServer = {"world.tiles", "world.structure", "machine.inspect", "machine.set_working", "dev.teleport"};
        String[] developerRouted = {"world.inspect", "world.scan", "world.inspect_many", "world.entities", "world.spatial"};
        for (String selectedProfile : new String[] {"player", "developer"}) {
            profile.set(bridge, selectedProfile);
            context.set(bridge, constructor.newInstance(null, 0, "developer".equals(selectedProfile), UUID.randomUUID(), true, true, System.nanoTime()));
            List<String> operations = new ArrayList<String>();
            Collections.addAll(operations, alwaysServer);
            if ("developer".equals(selectedProfile)) Collections.addAll(operations, developerRouted);
            for (String operation : operations) {
                GameQueue.Control control = new GameQueue.Control();
                // A missing pause guard fails immediately on cancellation instead of waiting eight seconds.
                control.cancelBeforeExecution();
                expectCode("world_paused", () -> bridge.dispatch(operation, new JsonObject(), control));
                check(cancellation.get(control) == null, "paused " + selectedProfile + " " + operation + " never calls queue submit");
            }
        }

        profile.set(bridge, "player");
        for (String operation : developerRouted) {
            GameQueue.Control control = new GameQueue.Control();
            control.cancelBeforeExecution();
            expectExecutionCode("cancelled", () -> bridge.dispatch(operation, new JsonObject(), control));
            check(cancellation.get(control) != null, "player " + operation + " retains client routing while the local server is paused");
        }
        profile.set(bridge, "developer");
        for (String operation : new String[] {"client.snapshot", "gui.inspect", "gui.close"}) {
            GameQueue.Control control = new GameQueue.Control();
            control.cancelBeforeExecution();
            expectExecutionCode("cancelled", () -> bridge.dispatch(operation, new JsonObject(), control));
            check(cancellation.get(control) != null, "paused world preserves client dispatch for " + operation);
        }
        Field pending = GameQueue.class.getDeclaredField("pending");
        pending.setAccessible(true);
        for (String name : new String[] {"clientQueue", "serverQueue", "renderQueue"}) {
            Field queue = GameBridge.class.getDeclaredField(name);
            queue.setAccessible(true);
            check(((Collection<?>) pending.get(queue.get(bridge))).isEmpty(), "pause-dispatch fixture leaves " + name + " empty without any game tick");
        }
    }

    private static void localCommandTests() throws Exception {
        BackgroundControl controls = new BackgroundControl();
        ControlAccess client = new ControlAccess();
        net.minecraft.util.MouseHelper original = client.helper;
        AtomicInteger releases = new AtomicInteger();
        AtomicBoolean pending = new AtomicBoolean();
        ScanSettings scanDefaults = new ScanSettings();
        List<String> forwarded = new ArrayList<String>();
        ClientControlCommand command = new ClientControlCommand((control, enabled) -> controls.change(client, control, enabled, pending.get(), releases::incrementAndGet), () -> controls.inspect(client), scanDefaults::apply,
                args -> { throw new AssertionError("Unexpected recorder call"); }, forwarded::add);
        ClientControlCommand.register(command);
        check(net.minecraftforge.client.ClientCommandHandler.instance.getCommands().get("mcb") == command
                && net.minecraftforge.client.ClientCommandHandler.instance.getCommands().get("codexbridge") == command
                && net.minecraftforge.client.ClientCommandHandler.instance.getCommands().get("agent") == command, "public client command registration installs fixed local roots and the available agent alias");
        CommandSender sender = new CommandSender();
        check(command.getRequiredPermissionLevel() == 0 && command.canCommandSenderUseCommand(sender), "local controls need no server permission or developer profile");
        check(ClientControlCommand.ownsMessage(" /mcb mouse on ") && ClientControlCommand.ownsMessage("/codexbridge unfocused on")
                && ClientControlCommand.ownsMessage("/agent on"), "bridge chat recognizes only exact local roots");
        check(!ClientControlCommand.ownsMessage("/mcbx mouse on") && !ClientControlCommand.ownsMessage("/codexbridge-other release")
                && !ClientControlCommand.ownsMessage("/agentXYZ on") && !ClientControlCommand.ownsMessage("/agent-other off")
                && !ClientControlCommand.ownsMessage("/tp 1 2 3") && !ClientControlCommand.ownsMessage("mcb status"), "similar roots and arbitrary server commands cannot enter local path");
        expectCode("invalid_args", () -> ClientControlCommand.executeLocal(sender, "/tp 1 2 3"));
        expectCode("invalid_args", () -> ClientControlCommand.parse(new String[] {"mouse", "yes"}));
        expectCode("invalid_args", () -> ClientControlCommand.parse(new String[] {"exec", "anything"}));
        expectCode("invalid_args", () -> ClientControlCommand.parse(new String[] {"release", "extra"}));
        expectCode("invalid_args", () -> ClientControlCommand.parse(new String[] {"agent", "yes"}));
        check(command.addTabCompletionOptions(sender, new String[] {""}).containsAll(java.util.Arrays.asList("agent", "on", "off", "mouse", "unfocused", "background", "takeover", "release", "status", "help")), "completion exposes bounded local control grammar and bare agent switch choices");
        check(command.addTabCompletionOptions(sender, new String[] {"mouse", "o"}).containsAll(java.util.Arrays.asList("on", "off")), "switch completion offers both choices");
        ClientControlCommand.executeLocal(sender, "/mcb unfocused on");
        check(!client.pause && client.helper == original && sender.messages.size() == 1, "actual Forge client handler executes unfocused locally without mouse takeover");
        ClientControlCommand.executeLocal(sender, "/codexbridge takeover");
        check(controls.inspect(client).get("enabled").getAsBoolean(), "takeover alias uses combined control logic");
        pending.set(true);
        ClientControlCommand.executeLocal(sender, "/mcb agent off");
        check(controls.inspect(client).get("enabled").getAsBoolean() && sender.messages.get(sender.messages.size() - 1).contains("busy"), "pending lifecycle command reports failure without releasing protections");
        pending.set(false);
        ClientControlCommand.executeLocal(sender, "/mcb mouse on");
        check(client.helper == original && !client.pause && releases.get() >= 3, "local mouse on restores original mouse, releases held input and preserves unfocused selection");
        ClientControlCommand.executeLocal(sender, "/mcb release");
        check(client.helper == original && client.pause && !controls.inspect(client).get("unfocusedEnabled").getAsBoolean(), "release alias restores independent controls to their original values");
        ClientControlCommand.executeLocal(sender, "/agent on");
        check(controls.inspect(client).get("enabled").getAsBoolean(), "bare agent on executes through the same combined client controls");
        ClientControlCommand.executeLocal(sender, "/mcb agent off");
        check(client.helper == original && client.pause && !controls.inspect(client).get("enabled").getAsBoolean(), "agent off restores both original settings");
        int beforeHelp = releases.get();
        ClientControlCommand.executeLocal(sender, "/mcb status");
        ClientControlCommand.executeLocal(sender, "/mcb help");
        check(releases.get() == beforeHelp && sender.messages.get(sender.messages.size() - 1).contains("mouse on restores manual mouse"), "status and help are local read-only commands with recovery instructions");
        ClientControlCommand.executeLocal(sender, "/mcb scan 4");
        check(scanDefaults.inspect().get("radiusBlocks").getAsInt() == 64 && scanDefaults.inspect().get("verticalRadiusBlocks").getAsInt() == 64, "registered local scan shorthand changes both radii");
        ClientControlCommand.executeLocal(sender, "/mcb scan 2 3");
        check(scanDefaults.inspect().get("radiusBlocks").getAsInt() == 32 && scanDefaults.inspect().get("verticalRadiusBlocks").getAsInt() == 48, "registered local scan accepts independent vertical chunks");
        long scanRevision = scanDefaults.revision();
        ClientControlCommand.executeLocal(sender, "/mcb scan status");
        check(scanDefaults.revision() == scanRevision && releases.get() == beforeHelp && sender.messages.get(sender.messages.size() - 1).contains("session only"), "scan status reports in-memory defaults without changing controls");
        ForeignAgentCommand foreign = new ForeignAgentCommand();
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(foreign);
        ClientControlCommand.register(command);
        check(net.minecraftforge.client.ClientCommandHandler.instance.getCommands().get("agent") == foreign
                && net.minecraftforge.client.ClientCommandHandler.instance.getCommands().get("mcb") == command, "existing agent main command survives bridge registration while mcb remains available");
        expectCode("unsupported", () -> ClientControlCommand.executeLocal(sender, "/agent on"));
        check(foreign.calls == 0 && releases.get() == beforeHelp, "bridge chat neither executes a foreign agent command nor falls through to server chat");
        ClientControlCommand.executeLocal(sender, "/mcb agent on");
        check(controls.inspect(client).get("enabled").getAsBoolean(), "mcb agent remains usable after a bare-root registration conflict");
        ClientControlCommand.executeLocal(sender, "/mcb agent off");
        int controlCalls = releases.get();
        ClientControlCommand.executeLocal(sender, "/mcb say 保留 Case 中文");
        ClientControlCommand.executeLocal(sender, "/mcb steer 先检查 IO");
        ClientControlCommand.executeLocal(sender, "/mcb inbox abcd1234");
        check(forwarded.equals(java.util.Arrays.asList("/mcb say 保留 Case 中文", "/mcb steer 先检查 IO", "/mcb inbox abcd1234"))
                && releases.get() == controlCalls, "dialogue short commands retain content and forward once without changing local controls");
        ClientControlCommand.executeLocal(sender, "/mcb say");
        ClientControlCommand.executeLocal(sender, "/mcb inbox one two");
        check(forwarded.size() == 3, "incomplete dialogue commands do not send server packets");
        ClientControlCommand.executeLocal(sender, "/mcb whoami");
        ClientControlCommand.executeLocal(sender, "/mcb group set Alice user");
        ClientControlCommand.executeLocal(sender, "/mcb debug off");
        ClientControlCommand.executeLocal(sender, "/mcb permissions reload");
        check(forwarded.subList(3, 7).equals(java.util.Arrays.asList("/mcb whoami", "/mcb group set Alice user", "/mcb debug off", "/mcb permissions reload"))
                && releases.get() == controlCalls, "permission commands retain authoritative server handling and do not affect local controls");
        check(command.addTabCompletionOptions(sender, new String[] {""}).containsAll(java.util.Arrays.asList("say", "steer", "inbox")), "short dialogue entries are discoverable");
    }

    private static void registryTests() throws Exception {
        RequestRegistry registry = new RequestRegistry(2, 60000);
        check("not_found".equals(registry.cancel("early")), "unknown cancellation creates tombstone");
        expectCode("cancelled", () -> registry.register("early"));
        registry.register("known");
        expectCode("duplicate_request", () -> registry.register("known"));
        check("cancelled".equals(registry.cancel("known")), "registered pending request cancels");
        expectCode("busy", () -> registry.register("overflow"));

        AtomicLong clock = new AtomicLong();
        RequestRegistry immediateExpiry = new RequestRegistry(1, 0, clock::get);
        GameQueue.Control pending = immediateExpiry.register("id");
        clock.set(TimeUnit.MINUTES.toNanos(5));
        expectCode("duplicate_request", () -> immediateExpiry.register("id"));
        expectCode("busy", () -> immediateExpiry.register("new-id"));
        immediateExpiry.complete("id", new GameQueue.Control());
        expectCode("busy", () -> immediateExpiry.register("new-id"));
        immediateExpiry.complete("id", pending);
        check(immediateExpiry.register("new-id") != null, "only completed records expire, and stale controls cannot complete another request");

        clock.set(0);
        RequestRegistry running = new RequestRegistry(1, 60000, clock::get);
        GameQueue.Control runningControl = running.register("running");
        GameQueue queue = new GameQueue(1);
        GameQueue.Ticket<Integer> ticket = queue.submit(() -> {
            clock.set(TimeUnit.MINUTES.toNanos(5));
            check("already_running".equals(running.cancel("running")), "running cancellation does not end the protected request");
            expectCode("duplicate_request", () -> running.register("running"));
            expectCode("busy", () -> running.register("new"));
            return 1;
        }, 1000, runningControl);
        queue.drain(1);
        check(queue.await(ticket, 100) == 1, "running request completes once");
        running.complete("running", runningControl);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
        expectCode("duplicate_request", () -> running.register("running"));
        expectCode("busy", () -> running.register("new"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        check(running.register("new") != null, "write TTL starts at conclusive completion, not submission");

        clock.set(0);
        RequestRegistry lru = new RequestRegistry(3, 60000, clock::get);
        GameQueue.Control write = lru.register("write", "inventory.supply");
        lru.complete("write", write);
        GameQueue.Control readOne = lru.register("read-one", "client.snapshot");
        lru.complete("read-one", readOne);
        GameQueue.Control readTwo = lru.register("read-two", "gui.widgets");
        lru.complete("read-two", readTwo);
        expectCode("duplicate_request", () -> lru.register("read-one", "client.snapshot"));
        GameQueue.Control readThree = lru.register("read-three", "bridge.health");
        lru.complete("read-three", readThree);
        expectCode("duplicate_request", () -> lru.register("write", "inventory.supply"));
        expectCode("duplicate_request", () -> lru.register("read-one", "client.snapshot"));
        check(lru.register("read-two", "gui.widgets") != null, "capacity evicts the least recently used completed read");

        RequestRegistry protectedReads = new RequestRegistry(2, 60000, clock::get);
        protectedReads.register("pending-read", "client.snapshot");
        protectedReads.cancel("cancelled-read");
        clock.set(TimeUnit.MINUTES.toNanos(5));
        expectCode("duplicate_request", () -> protectedReads.register("pending-read", "client.snapshot"));
        GameQueue.Control cancelledRead = protectedReads.register("cancelled-read", "client.snapshot");
        protectedReads.cancel("cancelled-read");
        protectedReads.complete("cancelled-read", cancelledRead);
        expectCode("cancelled", () -> protectedReads.register("cancelled-read", "client.snapshot"));
        expectCode("busy", () -> protectedReads.register("new-read", "client.snapshot"));
        expectCode("busy", () -> protectedReads.cancel("new-cancel"));

        clock.set(0);
        RequestRegistry lateCancellation = new RequestRegistry(1, 60000, clock::get);
        GameQueue.Control notStarted = lateCancellation.register("late-cancel", "client.snapshot");
        lateCancellation.complete("late-cancel", notStarted);
        clock.set(TimeUnit.SECONDS.toNanos(59));
        check("cancelled".equals(lateCancellation.cancel("late-cancel")), "a completed pre-execution failure may be cancelled later");
        clock.set(TimeUnit.SECONDS.toNanos(60));
        expectCode("cancelled", () -> lateCancellation.register("late-cancel", "client.snapshot"));
        expectCode("busy", () -> lateCancellation.register("new-read", "client.snapshot"));
        clock.set(TimeUnit.SECONDS.toNanos(118));
        check("cancelled".equals(lateCancellation.cancel("late-cancel")), "repeat cancellation retains the original cancellation window");
        clock.set(TimeUnit.SECONDS.toNanos(119));
        check(lateCancellation.register("late-cancel", "client.snapshot") != null, "cancellation TTL starts at first cancellation and is not shortened or refreshed");

        RequestRegistry pressure = new RequestRegistry(1024, 60000, clock::get);
        GameQueue.Control mutation = pressure.register("mutation", "player.interact");
        pressure.complete("mutation", mutation);
        pressure.register("unknown-write", "fixture.future_mutation");
        for (int i = 0; i < 4096; i++) {
            String id = "observation-" + i;
            GameQueue.Control read = pressure.register(id, "machine.inspect");
            pressure.complete(id, read);
        }
        expectCode("duplicate_request", () -> pressure.register("mutation", "player.interact"));
        expectCode("duplicate_request", () -> pressure.register("unknown-write", "fixture.future_mutation"));
        Field entries = RequestRegistry.class.getDeclaredField("entries");
        entries.setAccessible(true);
        check(((java.util.Map<?, ?>) entries.get(pressure)).size() == 1024, "read pressure retains the fixed registry bound");
        for (String operation : new String[] {"player.interact", "inventory.supply", "gui.widget_action", "machine.set_working",
                "qa.run", "qa.command", "qa.cancel", "client.load_world", "client.leave_world", "client.exit", "bridge.profile",
                "world.scan_config", "ae.action", "nei.navigate", "fixture.future_mutation", "machine.inspection"}) {
            check(!RequestRegistry.readOnly(operation), "mutation or unfamiliar operation is protected: " + operation);
        }
    }

    private static void registryHttpTests() throws Exception {
        AtomicLong clock = new AtomicLong();
        RequestRegistry registry = new RequestRegistry(4, 60000, clock::get);
        GameBridge fixture = new GameBridge() {
            @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
            @Override
            public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) throws ExecutionException {
                if ("timeout.fixture".equals(operation)) throw new BridgeException("timeout", "Outcome is unknown.");
                if ("recipes.query".equals(operation)) throw new ExecutionException(new BridgeException("invalid_args", "Fixture read failed."));
                return new JsonObject();
            }
        };
        BridgeHttpServer server = new BridgeHttpServer(fixture, "self-test-token", registry);
        server.start(0);
        try {
            int port = server.port();
            check(request(port, "call", "protected-write", "inventory.supply", true).get("ok").getAsBoolean(), "HTTP records conclusive write completion");
            for (int i = 0; i < 20; i++) {
                check(request(port, "call", "read-" + i, "client.snapshot", true).get("ok").getAsBoolean(), "real HTTP read burst releases completed LRU records");
            }
            check("duplicate_request".equals(errorCode(request(port, "call", "protected-write", "inventory.supply", true))), "HTTP read pressure keeps completed writes protected");
            check("duplicate_request".equals(errorCode(request(port, "call", "read-19", "client.snapshot", true))), "HTTP retains recent completed read IDs");
            for (int i = 0; i < 8; i++) {
                check("invalid_args".equals(errorCode(request(port, "call", "failed-read-" + i, "recipes.query", true))), "conclusive HTTP read failures also release capacity");
            }
            check("timeout".equals(errorCode(request(port, "call", "unknown-write", "timeout.fixture", true))), "HTTP fixture creates an unresolved timeout");
            clock.set(TimeUnit.MINUTES.toNanos(5));
            check("duplicate_request".equals(errorCode(request(port, "call", "unknown-write", "timeout.fixture", true))), "HTTP timeout-unknown ID survives TTL without reexecution");
            check(request(port, "call", "protected-write", "inventory.supply", true).get("ok").getAsBoolean(), "HTTP conclusively completed write expires after its deduplication window");
        } finally { server.stop(); }
    }

    private static void httpTests() throws Exception {
        FixtureBridge bridge = new FixtureBridge();
        BridgeHttpServer server = new BridgeHttpServer(bridge, "self-test-token");
        server.start(0);
        int port = server.port();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            JsonObject good = request(port, "call", "echo-1", "echo", true);
            check(good.get("ok").getAsBoolean(), "real HTTP accepts valid authenticated call");
            check("echo-1".equals(good.get("requestId").getAsString()), "real HTTP preserves requestId");
            int beforeRefusal = bridge.dispatches.get();
            JsonObject denied = request(port, "call", "denied-1", "denied.fixture", true).getAsJsonObject("error");
            check("policy_denied".equals(denied.get("code").getAsString()), "HTTP preserves policy preparation refusal");
            check(!denied.get("outcome_unknown").getAsBoolean() && !denied.get("original_action_executed").getAsBoolean(), "HTTP pre-dispatch refusal is conclusive for all operations");
            check(bridge.dispatches.get() == beforeRefusal, "HTTP preparation refusal never dispatches");
            JsonObject nullableReply = request(port, "call", "nullable-1", "nullable", true);
            check(nullableReply.get("ok").getAsBoolean(), "real HTTP accepts explicit nullable observations");
            JsonObject nullable = nullableReply.getAsJsonObject("data");
            for (String field : new String[] {"occupiable", "canStand", "nextOffset", "stoppedAt"}) {
                check(nullable.has(field) && nullable.get(field).isJsonNull(), "real HTTP preserves explicit null for " + field);
            }
            JsonObject nested = nullable.getAsJsonObject("nested");
            check(nested.has("value") && nested.get("value").isJsonNull(), "real HTTP preserves nested object null");
            JsonArray entries = nullable.getAsJsonArray("entries");
            check(entries.size() == 2 && entries.get(0).isJsonNull(), "real HTTP preserves null array positions");
            check(entries.get(1).getAsJsonObject().has("value") && entries.get(1).getAsJsonObject().get("value").isJsonNull(), "real HTTP preserves object null inside an array");
            check(!nullable.get("loaded").getAsBoolean(), "real HTTP preserves an actual false value beside null observations");
            check(!nullable.has("absent") && !nested.has("absent"), "real HTTP leaves intentionally absent members absent");
            check("duplicate_request".equals(errorCode(request(port, "call", "echo-1", "echo", true))), "real HTTP rejects duplicate call");
            check("unauthorized".equals(errorCode(request(port, "call", "auth-1", "echo", false))), "real HTTP rejects invalid token");
            check("not_found".equals(request(port, "cancel", "cancel-before-call", null, true).getAsJsonObject("data").get("status").getAsString()), "real HTTP tombstone acknowledgement");
            check("cancelled".equals(errorCode(request(port, "call", "cancel-before-call", "hold", true))), "early HTTP cancellation prevents late registration");

            Future<JsonObject> one = callers.submit(() -> request(port, "call", "hold-1", "hold", true));
            Future<JsonObject> two = callers.submit(() -> request(port, "call", "hold-2", "hold", true));
            check(bridge.pending.await(2, TimeUnit.SECONDS), "two handlers are waiting on game-thread fixture");
            String busyId = "12bbd6a2-3a7d-4d88-8c89-02a1ed016033";
            int dispatchesBeforeBusy = bridge.dispatches.get();
            JsonObject busy = request(port, "call", busyId, "lifecycle", true);
            check(!busy.get("ok").getAsBoolean() && "busy".equals(errorCode(busy)), "third real HTTP call retains bounded game-request concurrency");
            check(busyId.equals(busy.get("requestId").getAsString()), "busy HTTP envelope echoes the validated request UUID");
            check(bridge.dispatches.get() == dispatchesBeforeBusy && bridge.lifecycleActions.get() == 0, "busy HTTP call never dispatches or schedules lifecycle work");
            check("unauthorized".equals(errorCode(request(port, "call", "busy-auth", "echo", false))), "busy HTTP path still authenticates before parsing");
            check("invalid_request".equals(errorCode(request(port, "call", "", "echo", true))), "busy HTTP path still validates requestId");
            oversizedChunkedRequest(port);
            long start = System.nanoTime();
            check("cancelled".equals(request(port, "cancel", "hold-1", null, true).getAsJsonObject("data").get("status").getAsString()), "cancel endpoint works while calls occupy workers");
            check("cancelled".equals(request(port, "cancel", "hold-2", null, true).getAsJsonObject("data").get("status").getAsString()), "second pending request cancels");
            check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000, "cancellation ACK is prompt");
            check("cancelled".equals(errorCode(one.get(2, TimeUnit.SECONDS))), "cancel completes original call with failure");
            check("cancelled".equals(errorCode(two.get(2, TimeUnit.SECONDS))), "cancel completes second call with failure");
            bridge.queue.drain(8);
            check(bridge.actions.get() == 0, "cancelled HTTP actions never execute later");
            check(request(port, "call", busyId, "echo", true).get("ok").getAsBoolean(), "busy HTTP rejection never registers or consumes the requestId");
            oversizedChunkedRequest(port);
            check(request(port, "call", "after-oversize", "echo", true).get("ok").getAsBoolean(), "HTTP worker remains available after unfinished oversized upload");
            JsonObject lifecycle = request(port, "call", "lifecycle-1", "lifecycle", true);
            check(lifecycle.get("ok").getAsBoolean() && lifecycle.getAsJsonObject("data").has("ticketId"), "HTTP lifecycle reply exposes scheduled ticket");
            check(bridge.lifecycleReply.await(2, TimeUnit.SECONDS) && bridge.lifecycleDelivered, "HTTP acknowledges lifecycle only after response body is closed");
            check(bridge.lifecycleActions.get() == 0, "scheduled HTTP response does not execute lifecycle on HTTP worker");
            bridge.lifecycle.tick();
            check(bridge.lifecycleActions.get() == 1 && "completed".equals(bridge.lifecycle.inspect().get("status").getAsString()), "client tick runs acknowledged lifecycle once");
            JsonObject oversized = request(port, "call", "lifecycle-oversize", "lifecycle_oversize", true);
            check("result_too_large".equals(errorCode(oversized)), "oversized scheduled result becomes failed HTTP response");
            check(bridge.lifecycleReply.await(2, TimeUnit.SECONDS) && !bridge.lifecycleDelivered, "failed HTTP response is not a lifecycle acknowledgement");
            bridge.lifecycle.tick();
            check(bridge.lifecycleActions.get() == 1 && "cancelled".equals(bridge.lifecycle.inspect().get("status").getAsString()), "failed scheduled HTTP reply cancels deferred lifecycle mutation");
        } finally {
            callers.shutdownNow();
            server.stop();
        }
    }

    private static void oversizedChunkedRequest(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000);
            OutputStream output = socket.getOutputStream();
            output.write(("POST /v1/call HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nAuthorization: Bearer self-test-token\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n186a0\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(new byte[65537]);
            output.flush();
            check(socket.getInputStream().read() == -1, "unfinished oversized chunked body is closed without draining forever");
        }
    }

    private static JsonObject request(int port, String endpoint, String id, String operation, boolean authorized) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("requestId", id);
        if (operation != null) {
            body.addProperty("operation", operation);
            body.add("args", new JsonObject());
        }
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/" + endpoint).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(4000);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", authorized ? "Bearer self-test-token" : "Bearer incorrect");
        connection.setDoOutput(true);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
            try (InputStream input = stream) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) response.write(buffer, 0, count);
                return new JsonParser().parse(new String(response.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } finally { connection.disconnect(); }
    }

    private static String errorCode(JsonObject result) { return result.getAsJsonObject("error").get("code").getAsString(); }

    private static void muiCoordinatePolicyTest() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        expectCode("unsupported", () -> {
            GuiCallbacks.requireCoordinateClick(InheritedMuiFixture.class);
            callbacks.incrementAndGet();
        });
        check(callbacks.get() == 0, "an inherited MUI marker blocks the callback before it can click an old hovered slot");
        GuiCallbacks.requireCoordinateClick(BridgeSelfTest.class);
        callbacks.incrementAndGet();
        check(callbacks.get() == 1, "non-MUI types remain eligible for coordinate callbacks");
        expectCode("unsupported", () -> {
            GuiCallbacks.requireCoordinateClick(InheritedAeFixture.class);
            callbacks.incrementAndGet();
        });
        check(callbacks.get() == 1, "AEBaseGui inheritance blocks cached-hover virtual-slot callbacks before mutation");
    }

    private static void catalogAndMessageTests() throws Exception {
        List<JsonObject> entries = new ArrayList<JsonObject>();
        for (String name : new String[] {"alpha:brick", "beta:brick", "beta:ore"}) {
            JsonObject entry = new JsonObject();
            entry.addProperty("registryName", name);
            entries.add(entry);
        }
        JsonObject args = new JsonObject();
        args.addProperty("offset", 1);
        args.addProperty("limit", 1);
        JsonObject page = CatalogData.page("items", CatalogData.matching(entries, "BRICK", "registryName"), args, 100000);
        check("beta:brick".equals(page.getAsJsonArray("items").get(0).getAsJsonObject().get("registryName").getAsString()), "filtered pagination uses matched offsets and case-insensitive query");
        check(page.get("nextOffset").isJsonNull() && !page.get("hasMore").getAsBoolean(), "catalog last page has null cursor");
        args.addProperty("offset", 100000);
        check(CatalogData.page("items", entries, args, 100000).getAsJsonArray("items").size() == 0, "catalog excessive but legal offset returns an empty page");
        args.addProperty("limit", 129);
        expectCode("invalid_args", () -> CatalogData.page("items", entries, args, 100000));
        args.addProperty("limit", 1.5);
        expectCode("invalid_args", () -> CatalogData.page("items", entries, args, 100000));
        args.addProperty("query", String.join("", Collections.nCopies(129, "x")));
        expectCode("invalid_args", () -> CatalogData.text(args, "query", 128, ""));

        ClientMessages ring = new ClientMessages();
        for (int i = 1; i <= 130; i++) ring.add("message " + i, i);
        JsonObject cursor = new JsonObject();
        cursor.addProperty("limit", 1);
        JsonObject first = ring.inspect(cursor, "fixture-session");
        check(first.get("oldestSeq").getAsLong() == 3 && first.get("lastSequence").getAsLong() == 130, "message ring evicts oldest entries while sequence remains monotonic");
        check(first.get("historyGap").getAsBoolean() && first.get("hasMore").getAsBoolean(), "message response distinguishes history eviction and remaining page entries");
        cursor.addProperty("since", first.get("nextSince").getAsLong());
        check(ring.inspect(cursor, "fixture-session").getAsJsonArray("messages").get(0).getAsJsonObject().get("seq").getAsLong() == 4, "message cursor advances without repeating a returned entry");
        cursor.addProperty("query", "does not exist");
        JsonObject filtered = ring.inspect(cursor, "fixture-session");
        check(filtered.getAsJsonArray("messages").size() == 0 && filtered.get("nextSince").getAsLong() == 130, "empty message query advances cursor past examined history");
        cursor.addProperty("since", -1);
        expectCode("invalid_args", () -> ring.inspect(cursor, "fixture-session"));
        cursor.addProperty("since", 0.25);
        expectCode("invalid_args", () -> ring.inspect(cursor, "fixture-session"));
        cursor.addProperty("since", "5");
        expectCode("invalid_args", () -> ring.inspect(cursor, "fixture-session"));

        ClientCatalog.LimitedVariants variants = new ClientCatalog.LimitedVariants();
        variants.addAll(Collections.nCopies(8192, null));
        check(variants.size() == 8192, "creative-list sink admits only the bounded variant prefix");
        try { variants.add(null); throw new AssertionError("Expected variant bound"); }
        catch (ClientCatalog.VariantLimitReached expected) { check(variants.size() == 8192, "creative add cannot exceed sink capacity"); }
        try { variants.addAll(0, Collections.singletonList(null)); throw new AssertionError("Expected indexed variant bound"); }
        catch (ClientCatalog.VariantLimitReached expected) { check(variants.size() == 8192, "creative indexed addAll cannot bypass sink capacity"); }
        nativeGlassMappingTests();
    }

    private static void nativeGlassMappingTests() {
        net.minecraft.block.Block glass = new net.minecraft.block.Block(net.minecraft.block.material.Material.glass) { };
        net.minecraft.block.Block other = new net.minecraft.block.Block(net.minecraft.block.material.Material.glass) { };
        gregtech.api.util.GlassTier.clearFixture(); gregtech.api.util.GlassTier.mapping(glass, 0, 13, 10); gregtech.api.util.GlassTier.mapping(glass, 1, 14, 12);
        net.minecraft.item.ItemBlock item = new net.minecraft.item.ItemBlock(glass);
        net.minecraft.item.ItemBlock otherItem = new net.minecraft.item.ItemBlock(other);
        // This headless Forge mapping resolves Block.getBlockFromItem through matching registry identities.
        net.minecraft.block.Block.blockRegistry.addObject(3996, "bridge_glass_fixture", glass); net.minecraft.item.Item.itemRegistry.addObject(3996, "bridge_glass_fixture", item);
        net.minecraft.block.Block.blockRegistry.addObject(3997, "bridge_other_glass_fixture", other); net.minecraft.item.Item.itemRegistry.addObject(3997, "bridge_other_glass_fixture", otherItem);
        JsonObject first = ClientCatalog.nativeStructureChannel(new net.minecraft.item.ItemStack(item, 1, 0));
        JsonObject second = ClientCatalog.nativeStructureChannel(new net.minecraft.item.ItemStack(item, 1, 1));
        check(first.get("value").getAsInt() == 13 && first.get("voltageTier").getAsInt() == 10 && second.get("value").getAsInt() == 14 && second.get("voltageTier").getAsInt() == 12, "native channel index and voltage tier remain distinct public mapping values for exact Block and metadata");
        check(first.get("status").getAsString().equals("native_cached_mapping") && !first.get("initializationInvoked").getAsBoolean() && gregtech.api.util.GlassTier.calls == 4, "mapped variants invoke exactly their two pure public getters without initializing GlassTier");
        JsonObject query = new JsonObject(); query.addProperty("registryName", "bridge_glass_fixture"); query.addProperty("metadata", 1);
        check(new ClientCatalog().item(query, null).getAsJsonObject("nativeStructureChannel").get("value").getAsInt() == 14, "existing registry.item endpoint publishes exact native mapping without new parameters or dispatch");
        JsonObject absent = ClientCatalog.nativeStructureChannel(new net.minecraft.item.ItemStack(otherItem, 1, 0));
        check(absent.get("status").getAsString().equals("not_mapped") && absent.get("value").isJsonNull() && absent.get("voltageTier").isJsonNull(), "equal metadata on a different native Block never shares the registered mapping");
        gregtech.api.util.GlassTier.mapping(glass, 2, -1, 5);
        JsonObject pending = ClientCatalog.nativeStructureChannel(new net.minecraft.item.ItemStack(item, 1, 2));
        check(pending.get("status").getAsString().equals("not_initialized") && pending.get("value").isJsonNull() && pending.get("voltageTier").getAsInt() == 5, "uninitialized native channel stays unknown while its registered voltage tier remains readable");
        int calls = gregtech.api.util.GlassTier.calls;
        JsonObject nonblock = ClientCatalog.nativeStructureChannel(new net.minecraft.item.ItemStack(new net.minecraft.item.Item(), 1, 0));
        check(nonblock.get("status").getAsString().equals("not_block_item") && gregtech.api.util.GlassTier.calls == calls, "non-Block ItemStacks never invoke the native glass mapping API");
        gregtech.api.util.GlassTier.clearFixture();
    }

    private static void tickSampleTests() {
        TickSamples samples = new TickSamples();
        Object server = new Object();
        check(!samples.inspect(server, true, 1).get("available").getAsBoolean(), "paused newly loaded server reports unavailable samples");
        for (int i = 1; i <= 129; i++) samples.record(server, i * 1000000L, i * 1000000L);
        JsonObject result = samples.inspect(server, true, 139000000L);
        check(result.get("sampleCount").getAsInt() == 128, "tick execution sample count is bounded");
        check(result.get("meanMillis").getAsDouble() == 65.5 && result.get("maxMillis").getAsDouble() == 129, "tick ring reports only the recent bounded window");
        check(result.get("lastSampleAgeMillis").getAsLong() == 10 && result.get("paused").getAsBoolean(), "paused diagnostics preserves explicit sample age");
        check(!samples.inspect(new Object(), false, 139000000L).get("available").getAsBoolean(), "tick samples never cross server identities");
        Object replacement = new Object();
        samples.record(replacement, 2000000, 200000000L);
        check(samples.inspect(replacement, false, 201000000L).get("sampleCount").getAsInt() == 1, "replacement server starts a fresh sample window");
    }

    private static void teleportPolicyTests() throws Exception {
        JsonObject args = new JsonObject();
        args.addProperty("x", -1.25);
        args.addProperty("y", 4.0);
        args.addProperty("z", 2.75);
        TeleportPolicy.Target target = TeleportPolicy.target(args, 180);
        check(target.x == -1.25 && target.y == 4 && target.dimension == 180, "teleport accepts finite feet coordinates without integer truncation");
        check(!target.allowTrackingChunkLoad, "tracking chunk loading stays disabled for the original default policy");
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180, false, true));
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180, true, true));
        args.addProperty("dimension", 0);
        expectCode("permission_denied", () -> TeleportPolicy.target(args, 180));
        expectCode("permission_denied", () -> TeleportPolicy.target(args, 180, false));
        TeleportPolicy.Target optedIn = TeleportPolicy.target(args, 180, true);
        check(optedIn.dimension == 0 && optedIn.x == -1.25 && optedIn.y == 4 && optedIn.z == 2.75,
                "dimension opt-in preserves the requested target and fractional coordinates");
        check(!optedIn.allowTrackingChunkLoad, "dimension opt-in alone retains the already-loaded tracking window requirement");
        expectCode("permission_denied", () -> TeleportPolicy.target(args, 180, false, true));
        TeleportPolicy.Target trackingOptedIn = TeleportPolicy.target(args, 180, true, true);
        check(trackingOptedIn.dimension == 0 && trackingOptedIn.allowTrackingChunkLoad
                && trackingOptedIn.x == optedIn.x && trackingOptedIn.y == optedIn.y && trackingOptedIn.z == optedIn.z,
                "explicit cross-dimension tracking opt-in preserves the exact target");
        check(!TeleportPolicy.target(args, 180, true, false).allowTrackingChunkLoad,
                "explicit false retains the strict tracking-window guard for a cross-dimension move");
        args.addProperty("changeDimension", true);
        expectCode("permission_denied", () -> TeleportPolicy.target(args, 180));
        args.remove("dimension");
        check(TeleportPolicy.target(args, 180, true).dimension == 180,
                "dimension opt-in without a target retains the current dimension");
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180, true, true));
        JsonObject malformedTracking = new JsonObject();
        malformedTracking.addProperty("allowTrackingChunkLoad", "true");
        expectCode("invalid_args", () -> GuiDataAdapter.booleanArg(malformedTracking, "allowTrackingChunkLoad", false));
        check(!GuiDataAdapter.booleanArg(new JsonObject(), "allowTrackingChunkLoad", false), "absent request flag does not enable tracking chunk loading");
        args.addProperty("x", Double.NaN);
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180));
        args.addProperty("x", 30000001);
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180));
        args.addProperty("x", 0);
        args.addProperty("y", 256);
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180));
        expectCode("invalid_args", () -> TeleportPolicy.target(args, 180, true));
        TeleportPolicy.requireDestinationBody(-0.3, 70, -0.3, 0.3, 71.8, 0.3, 256);
        TeleportPolicy.requireDestinationBody(-0.3, 254.2, -0.3, 0.3, 256, 0.3, 256);
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(-0.3, 255, -0.3, 0.3, 256.8, 0.3, 256));
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(-0.3, -0.1, -0.3, 0.3, 1.7, 0.3, 256));
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(29999999.5, 70, -0.3, 30000000.1, 71.8, 0.3, 256));
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(-30000000.1, 70, -0.3, -29999999.5, 71.8, 0.3, 256));
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(-0.3, 70, Double.NaN, 0.3, 71.8, 0.3, 256));
        expectCode("invalid_args", () -> TeleportPolicy.requireDestinationBody(-0.3, 70, -0.3, 0.3, 71.8, 0.3, 64));
        TeleportPolicy.requireReady(true, false, false, true, false, false, true);
        check(true, "only fully ready creative local state passes teleport policy");
        expectCode("permission_denied", () -> TeleportPolicy.requireReady(false, false, false, true, false, false, true));
        expectCode("invalid_state", () -> TeleportPolicy.requireReady(true, true, false, true, false, false, true));
        expectCode("invalid_state", () -> TeleportPolicy.requireReady(true, false, true, true, false, false, true));
        expectCode("invalid_state", () -> TeleportPolicy.requireReady(true, false, false, false, false, false, true));
        expectCode("invalid_state", () -> TeleportPolicy.requireReady(true, false, false, true, true, false, true));
        expectCode("invalid_state", () -> TeleportPolicy.requireReady(true, false, false, true, false, true, true));
        expectCode("chunk_unloaded", () -> TeleportPolicy.requireReady(true, false, false, true, false, false, false));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void flightPolicyTests() throws Exception {
        FlightPolicy.requireChange(true, true, false, false, true);
        FlightPolicy.requireChange(true, true, true, false, false);
        check(true, "existing creative permission permits airborne flight enable and ordinary ground disable");
        expectCode("permission_denied", () -> FlightPolicy.requireChange(false, true, false, false, true));
        expectCode("permission_denied", () -> FlightPolicy.requireChange(true, false, false, false, true));
        expectCode("invalid_state", () -> FlightPolicy.requireChange(true, true, true, false, true));
        expectCode("invalid_state", () -> FlightPolicy.requireChange(true, true, false, true, true));
        TeleportPolicy.requireFlight(true, true, true);
        TeleportPolicy.requireFlight(false, false, false);
        check(true, "airborne teleport accepts server flight and optional guard preserves ordinary teleport");
        expectCode("invalid_state", () -> TeleportPolicy.requireFlight(true, true, false));
        expectCode("invalid_state", () -> TeleportPolicy.requireFlight(true, false, true));
        check(TeleportPolicy.flightRequired(false, false, true, true, false), "airborne destination automatically requires creative flight even without a caller flag");
        check(!TeleportPolicy.flightRequired(true, false, true, true, false), "supported destination preserves grounded operation");
        check(TeleportPolicy.flightRequired(false, false, false, true, true), "survival airborne teleport accepts already acquired real flight");
        expectCode("permission_denied", () -> TeleportPolicy.flightRequired(false, false, true, false, false));
        expectCode("permission_denied", () -> TeleportPolicy.flightRequired(false, false, false, true, false));
        JsonObject pose = new JsonObject(); pose.addProperty("x", -1.5); pose.addProperty("feetY", 10.0); pose.addProperty("z", 2.5);
        pose.addProperty("flying", true); pose.addProperty("onGround", false);
        JsonObject control = new JsonObject(); control.addProperty("available", true); control.addProperty("sampleId", 10);
        control.addProperty("dimension", 180); control.add("pose", pose.deepCopy());
        JsonObject world = new JsonObject(); world.addProperty("dimension", 180);
        JsonObject observed = new JsonObject(); observed.add("player", pose); observed.add("serverControl", control); observed.add("world", world);
        TeleportPolicy.Target suspended = new TeleportPolicy.Target(-1.5, 10, 2.5, 180);
        check(TeleportPolicy.arrived(observed, suspended, true, 9), "actual matching dual pose and new flight sample prove airborne arrival");
        check(!TeleportPolicy.arrived(observed, suspended, true, 10), "stale sample cannot prove post-teleport arrival");
        pose.addProperty("onGround", true);
        check(!TeleportPolicy.arrived(observed, suspended, true, 9), "old grounded state cannot satisfy airborne arrival");
        pose.addProperty("onGround", false); control.getAsJsonObject("pose").addProperty("flying", false);
        check(!TeleportPolicy.arrived(observed, suspended, true, 9), "client-only flight cannot prove airborne arrival");
        control.getAsJsonObject("pose").addProperty("flying", true); pose.addProperty("feetY", 9.9);
        check(!TeleportPolicy.arrived(observed, suspended, true, 9), "a falling client position is not accepted as arrival");
        pose.addProperty("feetY", 10); world.addProperty("dimension", 0);
        check(!TeleportPolicy.arrived(observed, suspended, true, 9), "source-dimension snapshot is not destination arrival");
    }

    private static void expectCode(String expected, CheckedAction action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (BridgeException error) { check(expected.equals(error.code), "expected error code " + expected); }
    }

    private static void interactionViewPolicyTests() throws Exception {
        JsonObject target = new JsonObject(); target.addProperty("x", 1); target.addProperty("y", 64); target.addProperty("z", 2);
        check(!GameBridge.interactionRestoreView(target, "use", false), "ordinary explicit block use retains the target view by default without a separate look request");
        check(!GameBridge.interactionRestoreView(target, "use", true), "developer explicit use also retains the target view by default");
        check(!GameBridge.interactionRestoreView(target, "attack", false) && !GameBridge.interactionRestoreView(target, "attack", true), "explicit block attack retains the target-facing view by default for both profiles");
        target.addProperty("restoreView", false);
        check(!GameBridge.interactionRestoreView(target, "use", false), "ordinary explicit false also retains the target view");
        check(!GameBridge.interactionRestoreView(target, "attack", false), "ordinary explicit attack accepts false without an additional view request");
        target.addProperty("restoreView", true);
        expectCode("permission_denied", () -> GameBridge.interactionRestoreView(target, "use", false));
        expectCode("permission_denied", () -> GameBridge.interactionRestoreView(target, "attack", false));
        check(GameBridge.interactionRestoreView(target, "use", true), "developer explicit true preserves the existing scoped view restoration option");
        check(GameBridge.interactionRestoreView(target, "attack", true), "developer explicit attack shares the existing scoped view restoration option");
        target.addProperty("restoreView", "true");
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "use", true));
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "attack", true));
        target.addProperty("restoreView", 1);
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "use", true));
        target.addProperty("restoreView", false);
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "other", true));
        target.remove("z");
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "use", true));
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(target, "attack", true));
        JsonObject crosshair = new JsonObject();
        check(!GameBridge.interactionRestoreView(crosshair, "use", false) && !GameBridge.interactionRestoreView(crosshair, "attack", true), "crosshair actions retain their existing path without requesting an explicit aim policy");
        crosshair.addProperty("restoreView", false);
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(crosshair, "use", true));
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(crosshair, "attack", true));
        crosshair.addProperty("restoreView", true);
        expectCode("invalid_args", () -> GameBridge.interactionRestoreView(crosshair, "use", true));
    }

    private static void expectExecutionCode(String expected, CheckedAction action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (ExecutionException error) { check(error.getCause() instanceof BridgeException && expected.equals(((BridgeException) error.getCause()).code), "expected execution error " + expected); }
    }

    private static void screenSemanticsTests() {
        JsonObject absent = ScreenSemantics.describe(new JsonObject(), null);
        check(!absent.get("screenOpen").getAsBoolean() && absent.get("screenKind").getAsString().equals("none"), "no GUI has explicit closed/none metadata");
        JsonObject unknown = ScreenSemantics.describe(new JsonObject(), new Object());
        check(unknown.get("screenOpen").getAsBoolean() && unknown.get("screenKind").getAsString().equals("unknown"), "unknown screens remain open and do not imply all operations are unsupported");
        check(ScreenSemantics.classKind(InheritedMuiFixture.class).equals("modularui"), "MUI interface ancestry classifies derived screens without optional class loading");
        check(ScreenSemantics.classKind(InheritedAeFixture.class).equals("ae"), "AE class ancestry classifies derived screens");
        check(ScreenSemantics.classKind(NeiMuiFixture.class).equals("nei_recipe"), "NEI family takes precedence over a generic MUI marker");
        check(ScreenSemantics.classKind(AeMuiFixture.class).equals("modularui"), "actual MUI marker takes precedence over an inherited AE base");
        check(ScreenSemantics.classKind(ContainerMuiFixture.class).equals("modularui"), "MUI container wrapper semantics take precedence over generic GuiContainer");
        check(ScreenSemantics.classKind(net.minecraft.client.gui.inventory.GuiContainerCreative.class).equals("creative_inventory"), "creative inventory is classified before the generic container family");
        check(ScreenSemantics.classKind(net.minecraft.client.gui.inventory.GuiInventory.class).equals("inventory"), "player inventory is classified before the generic container family");
        check(ScreenSemantics.classKind(net.minecraft.client.gui.inventory.GuiContainer.class).equals("container"), "ordinary containers retain a generic supported family");
        check(ScreenSemantics.classKind(net.minecraft.client.gui.GuiMainMenu.class).equals("main_menu")
                && ScreenSemantics.classKind(net.minecraft.client.gui.GuiIngameMenu.class).equals("pause_menu")
                && ScreenSemantics.classKind(net.minecraft.client.gui.GuiChat.class).equals("chat"), "native menu and chat classes have stable distinct kinds");
        check(ScreenSemantics.classKind(InheritedMuiFixture.class).equals(ScreenSemantics.classKind(InheritedMuiFixture.class)), "cached class kind remains stable across repeat observations");
        JsonArray kinds = ScreenSemantics.kinds(); kinds.add(new com.google.gson.JsonPrimitive("caller modification"));
        check(ScreenSemantics.kinds().size() == 19, "published enum copies cannot mutate the classifier's stable vocabulary");
        lumien.custommainmenu.configuration.GuiConfig custom = new lumien.custommainmenu.configuration.GuiConfig(); custom.name = "mainmenu";
        lumien.custommainmenu.gui.GuiCustom customScreen = new lumien.custommainmenu.gui.GuiCustom(custom);
        check(ScreenSemantics.kind(customScreen).equals("main_menu"), "known CustomMainMenu uses its existing public per-instance main-menu identity");
        custom.name = "other";
        check(ScreenSemantics.kind(customScreen).equals("unknown"), "class caching does not misclassify other CustomMainMenu configurations as the main menu");
    }

    private interface CheckedAction { void run() throws Exception; }

    private static class ControlAccess implements BackgroundControl.Access {
        net.minecraft.util.MouseHelper helper = new net.minecraft.util.MouseHelper();
        boolean pause = true;
        boolean grabbed;
        boolean failPauseOnce;
        int focusClears;
        public net.minecraft.util.MouseHelper mouse() { return helper; }
        public void mouse(net.minecraft.util.MouseHelper helper) { this.helper = helper; }
        public boolean pause() { return pause; }
        public void pause(boolean value) {
            pause = value;
            if (failPauseOnce) { failPauseOnce = false; throw new IllegalStateException("fixture failure after pause write"); }
        }
        public boolean grabbed() { return grabbed; }
        public void clearFocus() { focusClears++; }
    }

    private static class CommandSender implements net.minecraft.command.ICommandSender {
        final List<String> messages = new ArrayList<String>();
        public String getCommandSenderName() { return "fixture"; }
        public net.minecraft.util.IChatComponent getFormattedCommandSenderName() { return new net.minecraft.util.ChatComponentText("fixture"); }
        public void addChatMessage(net.minecraft.util.IChatComponent message) { messages.add(message.getUnformattedText()); }
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) { return false; }
        public net.minecraft.util.ChunkCoordinates getCommandSenderPosition() { return new net.minecraft.util.ChunkCoordinates(0, 0, 0); }
        public net.minecraft.world.World getEntityWorld() { return null; }
    }

    private static class ForeignAgentCommand extends net.minecraft.command.CommandBase {
        int calls;
        public String getCommandName() { return "agent"; }
        public String getCommandUsage(net.minecraft.command.ICommandSender sender) { return "foreign agent command"; }
        public void processCommand(net.minecraft.command.ICommandSender sender, String[] args) { calls++; }
    }

    private interface ExtendedMuiFixture extends com.cleanroommc.modularui.api.IMuiScreen { }

    private static class MuiFixture implements ExtendedMuiFixture { }

    private static class InheritedMuiFixture extends MuiFixture { }

    private static class InheritedAeFixture extends appeng.client.gui.AEBaseGui { }

    private static class AeMuiFixture extends appeng.client.gui.AEBaseGui implements ExtendedMuiFixture { }

    private static class NeiMuiFixture extends codechicken.nei.recipe.GuiRecipe implements ExtendedMuiFixture {
        NeiMuiFixture() { super(null, Collections.emptyList()); }
    }

    private static abstract class ContainerMuiFixture extends net.minecraft.client.gui.inventory.GuiContainer implements ExtendedMuiFixture {
        ContainerMuiFixture() { super(null); }
    }

    private static class UnknownCustomMenu extends lumien.custommainmenu.gui.GuiCustom {
        UnknownCustomMenu(lumien.custommainmenu.configuration.GuiConfig config) { super(config); }
    }

    private static class FixtureBridge extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) {
            if ("denied.fixture".equals(op)) throw new BridgeException("policy_denied", "Fixture preparation refusal.");
            return args;
        }
        final GameQueue queue = new GameQueue(4);
        final CountDownLatch pending = new CountDownLatch(2);
        final AtomicInteger actions = new AtomicInteger();
        final AtomicInteger dispatches = new AtomicInteger();
        final LifecycleTasks lifecycle = new LifecycleTasks();
        final AtomicInteger lifecycleActions = new AtomicInteger();
        volatile GameQueue.Control lifecycleControl;
        volatile CountDownLatch lifecycleReply = new CountDownLatch(0);
        volatile boolean lifecycleDelivered;

        @Override
        public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) throws ExecutionException, InterruptedException {
            dispatches.incrementAndGet();
            if ("nullable".equals(operation)) {
                JsonObject result = new JsonObject();
                for (String field : new String[] {"occupiable", "canStand", "nextOffset", "stoppedAt"}) result.add(field, JsonNull.INSTANCE);
                result.addProperty("loaded", false);
                JsonObject nested = new JsonObject();
                nested.add("value", JsonNull.INSTANCE);
                result.add("nested", nested);
                JsonArray entries = new JsonArray();
                entries.add(JsonNull.INSTANCE);
                entries.add(nested);
                result.add("entries", entries);
                return result;
            }
            if ("lifecycle".equals(operation) || "lifecycle_oversize".equals(operation)) {
                lifecycleControl = control;
                lifecycleReply = new CountDownLatch(1);
                JsonObject result = lifecycle.schedule(operation, "fixture", control, () -> true, lifecycleActions::incrementAndGet, () -> true);
                lifecycle.tick();
                if ("lifecycle_oversize".equals(operation)) result.addProperty("oversize", String.join("", Collections.nCopies(1048577, "x")));
                return result;
            }
            if ("hold".equals(operation)) {
                GameQueue.Ticket<JsonObject> ticket = queue.submit(() -> {
                    actions.incrementAndGet();
                    return new JsonObject();
                }, 8000, control);
                pending.countDown();
                return queue.await(ticket, 8000);
            }
            JsonObject result = new JsonObject();
            result.addProperty("fixture", true);
            return result;
        }

        @Override
        public void responseFinished(GameQueue.Control control, boolean delivered) {
            if (control == lifecycleControl) {
                lifecycleDelivered = delivered;
                lifecycle.responseFinished(control, delivered);
                lifecycleReply.countDown();
            }
        }
    }
}
