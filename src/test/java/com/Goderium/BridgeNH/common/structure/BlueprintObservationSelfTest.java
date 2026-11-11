package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Exercises the same instrument ownership/copy/budget/cache path with native iteration replaced by a fixture. */
public final class BlueprintObservationSelfTest {
    private static int checks;
    public static int run() {
        checks = 0;
        captureAndCopies();
        paginationAndPlanning();
        largeCaptureAndRoleSelection();
        busyAndExceptions();
        limits();
        invalidation();
        positionCandidateGetter();
        return checks;
    }

    private static void captureAndCopies() {
        Fixture f = new Fixture();
        AtomicInteger calls = new AtomicInteger();
        JsonObject supplied = description("Input Bus");
        boolean accepted = f.adapter.execute(f.scope, () -> {
            calls.incrementAndGet();
            f.observe(10, "L", supplied);
            f.observe(11, "L", supplied);
            f.adapter.observe("unrelated", f.world, 90, 91, 92, 0, 0, 0, "Other", "other", () -> supplied);
            f.adapter.observe(f.instrument.token, new Object(), 90, 91, 92, 0, 0, 0, "Other", "other", () -> supplied);
            return false;
        });
        check(!accepted && calls.get() == 1, "native return value and exact one-call semantics survive observation");
        check(f.instrument.enables == 1 && f.instrument.disables == 1 && !f.instrument.enabled(), "normal call releases its instrument");
        supplied.addProperty("fixtureCategory", "mutated");
        JsonObject first = f.page();
        check(first.get("available").getAsBoolean() && first.get("nativeInvocationReturned").getAsBoolean(), "native capture is readable");
        check(first.get("capturedPositions").getAsInt() == 2 && first.get("observedVisits").getAsInt() == 2, "foreign identifiers/worlds cannot contaminate capture");
        check(first.getAsJsonArray("groups").size() == 1 && first.getAsJsonArray("groups").get(0).getAsJsonObject().get("capturedVisits").getAsInt() == 2,
                "one public definition element groups repeated visited coordinates");
        JsonObject position = first.getAsJsonArray("positions").get(0).getAsJsonObject();
        check(position.get("x").getAsInt() == 10 && position.get("a").getAsInt() == 13 && position.get("piece").isJsonNull(), "world XYZ and piece-origin ABC are copied without guessing piece");
        check(!first.getAsJsonArray("groups").get(0).toString().contains("mutated"), "producer metadata mutation cannot alter a capture");
        position.addProperty("x", -999);
        check(f.page().getAsJsonArray("positions").get(0).getAsJsonObject().get("x").getAsInt() == 10, "reader mutation cannot alter immutable cached payload");
        check(first.get("partial").getAsBoolean() && !first.get("completeStructure").getAsBoolean(), "client hints never claim complete structure or formation");
        check(first.get("capturedVisitsComplete").getAsBoolean() && first.getAsJsonObject("observedBounds").get("complete").getAsBoolean(),
                "complete captured visits and bounds remain distinct from complete machine structure");
        check(first.getAsJsonArray("groups").get(0).getAsJsonObject().get("elementKey").isJsonNull()
                && "unavailable_cross_side_mapping_not_guaranteed".equals(first.getAsJsonArray("groups").get(0).getAsJsonObject().get("elementKeyCoverage").getAsString()),
                "client-only grouping does not invent a stable server element key");
        check(first.get("contextMatches").getAsBoolean() && first.get("facingMatches").getAsBoolean()
                && first.get("evidenceOnly").getAsBoolean(), "matching public context remains explicit client evidence only");
        JsonObject filtered = f.adapter.page(0, f.tile, f.meta, f.definition, "NORTH", 2, 1, 2, 3, "Input Bus", 1, 131072);
        check(filtered.getAsJsonArray("positions").size() == 1 && filtered.get("matchingCapturedPositions").getAsInt() == 2 && filtered.get("pageLimited").getAsBoolean(),
                "description filtering and bounded coordinate pages report omitted matches");
        check(!f.adapter.page(0, f.tile, f.meta, f.definition, "NORTH", 2, 1, 2, 3, "", 128, 100).get("available").getAsBoolean(), "combined structure result byte budget is respected");
    }

    private static void paginationAndPlanning() {
        Fixture f = new Fixture(); AtomicInteger getters = new AtomicInteger();
        f.adapter.execute(f.scope, () -> {
            f.adapter.observe(f.instrument.token, f.world, -4, 9, 7, 0, 0, 0, "Fixture", "L",
                    () -> { getters.incrementAndGet(); return description("Input Bus"); });
            f.adapter.observe(f.instrument.token, f.world, 10, 15, -2, 1, 0, 0, "Fixture", "O",
                    () -> { getters.incrementAndGet(); return description("Output Bus"); });
            f.adapter.observe(f.instrument.token, f.world, 3, 12, 2, 2, 0, 0, "Fixture", "L", () -> description("must not run"));
            return true;
        });
        JsonObject first = f.page(null, 0, "Input Bus", 1, 10);
        String capture = first.get("captureId").getAsString();
        check(first.get("runtimeOffset").getAsInt() == 0 && first.get("nextRuntimeOffset").getAsInt() == 1
                && first.get("hasMore").getAsBoolean(), "first page returns a capture-bound raw coordinate continuation");
        check(first.get("matchingCapturedPositions").getAsInt() == 2 && first.getAsJsonArray("positions").size() == 1,
                "matching count covers immutable capture independently of page size");
        JsonObject bounds = first.getAsJsonObject("observedBounds");
        check(bounds.get("complete").getAsBoolean() && bounds.get("boundedVisits").getAsInt() == 3
                && bounds.getAsJsonObject("worldXYZ").getAsJsonObject("min").get("y").getAsInt() == 9
                && bounds.getAsJsonObject("worldXYZ").getAsJsonObject("max").get("y").getAsInt() == 15,
                "observed bounds include other groups and later pages before filtering");
        JsonObject plan = first.getAsJsonObject("planning");
        check(plan.get("available").getAsBoolean() && plan.get("minimumObservedY").getAsInt() == 9
                && plan.get("controllerRelativeMinY").getAsInt() == 7 && plan.get("minimumControllerY").getAsInt() == 4
                && plan.get("raiseBlocks").getAsInt() == 2 && plan.get("floorConflict").getAsBoolean(),
                "planning uses the bound controller and literal floor block formula");
        check(plan.getAsJsonObject("dimensionsFromObservedBounds").get("x").getAsInt() == 15
                && plan.getAsJsonObject("dimensionsFromObservedBounds").get("y").getAsInt() == 7
                && plan.getAsJsonObject("dimensionsFromObservedBounds").get("z").getAsInt() == 10,
                "planning dimensions come from all observed XYZ visits");
        JsonObject second = f.page(capture, 1, "Input Bus", 1, 8);
        check(second.get("nextRuntimeOffset").getAsInt() == 3 && !second.get("hasMore").getAsBoolean()
                && second.get("runtimePageVisited").getAsInt() == 2
                && second.getAsJsonArray("positions").get(0).getAsJsonObject().get("x").getAsInt() == 3,
                "filtered continuation advances raw indices past nonmatching groups without duplicates");
        check(second.getAsJsonObject("planning").get("raiseBlocks").getAsInt() == 0
                && !second.getAsJsonObject("planning").get("floorConflict").getAsBoolean(), "floor below the lowest visit requires no elevation");
        check(f.page(capture, 0, "", 128, 248).getAsJsonObject("planning").get("available").getAsBoolean(),
                "a shifted observed top layer exactly at Y255 remains within world height");
        JsonObject terminal = f.page(capture, 3, "", 128, 10);
        check(terminal.getAsJsonArray("positions").size() == 0 && !terminal.get("hasMore").getAsBoolean()
                && terminal.getAsJsonObject("planning").get("available").getAsBoolean(), "terminal coordinate page preserves the capture planning summary");
        bounds.getAsJsonObject("worldXYZ").getAsJsonObject("min").addProperty("y", -999);
        check(f.page().getAsJsonObject("observedBounds").getAsJsonObject("worldXYZ").getAsJsonObject("min").get("y").getAsInt() == 9
                && getters.get() == 2, "page/bounds readers cannot mutate cache or repeat public native descriptions");
        JsonObject noFloor = f.page().getAsJsonObject("planning");
        check("floor_not_requested".equals(noFloor.get("reason").getAsString()), "no floor is silently assumed");
        check(noFloor.get("currentBoundsFitWorld").getAsBoolean() && !noFloor.get("worldHeightConflict").getAsBoolean()
                && noFloor.get("minimumObservedY").getAsInt() == 9 && noFloor.get("maximumObservedY").getAsInt() == 15
                && noFloor.get("worldMinY").getAsInt() == 0 && noFloor.get("worldMaxY").getAsInt() == 255,
                "height evidence remains readable before selecting a floor");
        check(noFloor.get("observedVisitsComplete").getAsBoolean() && !noFloor.get("completeStructure").getAsBoolean()
                && !noFloor.get("fullStructureChecked").getAsBoolean() && !noFloor.has("raiseBlocks"),
                "all visited bounds never certify a complete machine envelope or silently suggest elevation");
        JsonObject mismatch = f.adapter.page(0, f.tile, f.meta, f.definition, "NORTH", 3, 1, 2, 3, "", capture, 0, 10, 128, 131072);
        check(!mismatch.getAsJsonObject("planning").get("available").getAsBoolean()
                && "client_server_context_mismatch".equals(mismatch.getAsJsonObject("planning").get("reason").getAsString()),
                "known client/server mode disagreement disables floor decisions while retaining evidence");
        check(mismatch.getAsJsonObject("planning").get("floorConflict").getAsBoolean()
                && mismatch.getAsJsonObject("planning").has("worldHeightConflict")
                && !mismatch.getAsJsonObject("planning").has("raiseBlocks"),
                "context mismatch retains literal observed conflicts without an actionable elevation");
        expect("invalid_args", () -> f.page(null, 1, "", 128, null));
        expect("invalid_args", () -> f.page(capture, 4, "", 128, null));
        expect("stale_capture", () -> f.page("different-capture", 0, "", 128, null));
        f.capture();
        expect("stale_capture", () -> f.page(capture, 1, "", 128, null));
        check(!capture.equals(f.page().get("captureId").getAsString()), "a new native hint invocation cannot continue an old coordinate capture");

        Fixture tall = new Fixture();
        tall.adapter.execute(tall.scope, () -> {
            tall.adapter.observe(tall.instrument.token, tall.world, 1, 0, 3, 0, 0, 0, "Fixture", "L", () -> description("Hatch"));
            tall.adapter.observe(tall.instrument.token, tall.world, 1, 250, 3, 0, 1, 0, "Fixture", "L", () -> description("Hatch"));
            return true;
        });
        JsonObject height = tall.page(null, 0, "", 128, 10).getAsJsonObject("planning");
        check(!height.get("available").getAsBoolean() && "world_height_unsupported".equals(height.get("reason").getAsString())
                && "unsupported".equals(height.get("code").getAsString()) && height.get("raiseBlocks").getAsInt() == 11,
                "elevation cannot propose an observed upper layer outside world height");
        Fixture low = new Fixture();
        low.adapter.execute(low.scope, () -> {
            low.adapter.observe(low.instrument.token, low.world, 1, 0, 3, 0, 0, 0, "Fixture", "L", () -> description("Hatch")); return true;
        });
        JsonObject controllerHeight = low.page(null, 0, "", 128, 254).getAsJsonObject("planning");
        check(!controllerHeight.get("available").getAsBoolean() && controllerHeight.get("minimumControllerY").getAsInt() == 257,
                "controller height itself is checked even when shifted observed visits fit at Y255");

        Fixture below = new Fixture();
        below.adapter.execute(below.scope, () -> {
            below.adapter.observe(below.instrument.token, below.world, 1, -4, 3, 0, 0, 0, "Fixture", "L", () -> description("Hatch"));
            below.adapter.observe(below.instrument.token, below.world, 1, 5, 3, 0, 1, 0, "Fixture", "L", () -> description("Hatch"));
            return true;
        });
        JsonObject belowNoFloor = below.page().getAsJsonObject("planning");
        check(belowNoFloor.get("worldHeightConflict").getAsBoolean() && !belowNoFloor.get("currentBoundsFitWorld").getAsBoolean()
                && !belowNoFloor.has("raiseBlocks"), "a captured lower-world conflict is visible without a floor request");
        JsonObject repairedHeight = below.page(null, 0, "", 128, 0).getAsJsonObject("planning");
        check(repairedHeight.get("available").getAsBoolean() && repairedHeight.get("raiseBlocks").getAsInt() == 5
                && repairedHeight.get("raisedMinimumObservedY").getAsInt() == 1
                && repairedHeight.get("raisedMaximumObservedY").getAsInt() == 10
                && repairedHeight.get("raisedBoundsFitWorld").getAsBoolean(),
                "out-of-world observed lower bounds may be raised when the entire visited range and controller then fit");
        Fixture above = new Fixture();
        above.adapter.execute(above.scope, () -> {
            above.adapter.observe(above.instrument.token, above.world, 1, 256, 3, 0, 0, 0, "Fixture", "L", () -> description("Hatch")); return true;
        });
        JsonObject upperNoFloor = above.page().getAsJsonObject("planning");
        check(upperNoFloor.get("worldHeightConflict").getAsBoolean() && upperNoFloor.get("maximumObservedY").getAsInt() == 256,
                "upper-world conflict is also visible before selecting a floor");
        JsonObject upperFloor = above.page(null, 0, "", 128, 0).getAsJsonObject("planning");
        check(!upperFloor.get("available").getAsBoolean() && !upperFloor.get("raisedBoundsFitWorld").getAsBoolean()
                && "world_height_unsupported".equals(upperFloor.get("reason").getAsString()),
                "a floor elevation cannot hide an already invalid observed top layer");

        Fixture timed = new Fixture();
        timed.adapter.execute(timed.scope, () -> { for (int i = 0; i < 5; i++) timed.observe(i, "L", description("Hatch")); return true; });
        timed.readAdvance.set(2000001L);
        JsonObject yielded = timed.page();
        check(yielded.get("hasMore").getAsBoolean() && yielded.get("nextRuntimeOffset").getAsInt() > 0
                && yielded.get("nextRuntimeOffset").getAsInt() < 5 && yielded.get("pageSoftBudgetPartial").getAsBoolean(),
                "page copying yields to its soft budget while retaining a usable raw continuation");
    }

    private static void largeCaptureAndRoleSelection() {
        Fixture f = new Fixture(); AtomicInteger descriptions = new AtomicInteger();
        JsonObject input = description("Input Bus");
        f.adapter.execute(f.scope, () -> {
            for (int i = 0; i < 7259; i++) {
                String group = i == 7258 ? "seed_declared_group" : "ordinary_declared_group";
                f.adapter.observe(f.instrument.token, f.world, i, 20, 30, i, 0, 0, "Fixture", group,
                        () -> { descriptions.incrementAndGet(); return input; });
            }
            return true;
        });
        JsonObject first = f.page(); String capture = first.get("captureId").getAsString();
        check(first.get("capturedPositions").getAsInt() == 7259 && first.get("observedVisits").getAsInt() == 7259
                && first.get("capturedVisitsComplete").getAsBoolean() && !first.get("eventCaptureTruncated").getAsBoolean(),
                "a 7259-visit capture survives both the previous position cap and response-sized storage cap");
        check(first.get("uncapturedVisits").getAsLong() == 0 && !first.get("captureLossDetected").getAsBoolean()
                && !first.get("captureContinuationAvailable").getAsBoolean(), "loss counters distinguish retained paging from native capture continuation");
        check(first.getAsJsonObject("limits").get("positions").getAsInt() == BlueprintObservationAdapter.POSITION_LIMIT
                && first.getAsJsonObject("limits").get("captureBytes").getAsInt() == BlueprintObservationAdapter.CAPTURE_BYTE_LIMIT
                && first.getAsJsonObject("limits").get("bytes").getAsInt() == BlueprintObservationAdapter.BYTE_LIMIT,
                "capture storage and each response have separate explicit bounded limits");
        int next = 0, returned = 0, pages = 0; JsonObject ordinary = null, seed = null;
        do {
            JsonObject page = f.page(capture, next, "", 128, null);
            check(page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= BlueprintObservationAdapter.BYTE_LIMIT,
                    "each large-capture response stays below the existing UTF-8 response limit");
            for (com.google.gson.JsonElement element : page.getAsJsonArray("positions")) {
                JsonObject position = element.getAsJsonObject();
                check(position.get("x").getAsInt() == returned, "large coordinate pages preserve exact immutable raw order without gaps or repeats");
                returned++;
            }
            for (com.google.gson.JsonElement element : page.getAsJsonArray("groups")) {
                JsonObject group = element.getAsJsonObject();
                if (group.get("id").getAsInt() == 0) ordinary = group;
                else if (group.get("id").getAsInt() == 1) seed = group;
            }
            int previous = next; next = page.get("nextRuntimeOffset").getAsInt(); pages++;
            check(next > previous && next <= 7259 && page.get("capturedVisitsComplete").getAsBoolean(),
                    "each retained page advances within the same complete native capture");
            if (!page.get("hasMore").getAsBoolean()) break;
        } while (pages <= 7259);
        check(returned == 7259 && next == 7259 && pages >= 57 && descriptions.get() == 2,
                "all 7259 visits are recoverable by bounded pages without repeating public native getters");
        check(ordinary != null && seed != null && ordinary.get("capturedVisits").getAsInt() == 7258
                && seed.get("capturedVisits").getAsInt() == 1, "separate observed input groups retain their own complete captured cardinalities");
        JsonObject normalSelector = ordinary.getAsJsonObject("roleSelection"), seedSelector = seed.getAsJsonObject("roleSelection");
        check(normalSelector.get("available").getAsBoolean() && seedSelector.get("available").getAsBoolean()
                && capture.equals(normalSelector.get("captureId").getAsString())
                && capture.equals(seedSelector.get("captureId").getAsString())
                && normalSelector.get("groupId").getAsInt() == 0 && seedSelector.get("groupId").getAsInt() == 1,
                "the same public category supports distinct capture-scoped caller role selectors without a machine adapter");
        check(!normalSelector.get("semanticRoleVerified").getAsBoolean() && !seedSelector.get("semanticRoleVerified").getAsBoolean()
                && normalSelector.getAsJsonArray("descriptionKeys").equals(seedSelector.getAsJsonArray("descriptionKeys")),
                "separate group selectors do not invent an automatic seed/ordinary chamber semantic from category names");
        normalSelector.addProperty("groupId", -99);
        check(f.page().getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("roleSelection").get("groupId").getAsInt() == 0,
                "mutating a returned selector cannot alter the cached public group identity");
        Fixture unknown = new Fixture(); unknown.adapter.execute(unknown.scope, () -> { unknown.observe(1, null, input); return true; });
        JsonObject classOnly = unknown.page().getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("roleSelection");
        check(!classOnly.get("available").getAsBoolean() && "public_element_identity_unavailable".equals(classOnly.get("reason").getAsString()),
                "class-only grouping cannot authorize a selected special input role");
        Fixture truncated = new Fixture(); JsonObject partial = description("Input Bus"); partial.addProperty("descriptionTruncated", true);
        truncated.adapter.execute(truncated.scope, () -> { truncated.observe(1, "L", partial); return true; });
        check(!truncated.page().getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonObject("roleSelection").get("available").getAsBoolean(),
                "a truncated public category declaration cannot authorize role selection");
        expect("invalid_args", () -> f.page(capture, BlueprintObservationAdapter.POSITION_LIMIT + 1, "", 128, null));
    }

    private static void busyAndExceptions() {
        Fixture busy = new Fixture();
        Object owner = new Object(); busy.instrument.token = owner;
        AtomicInteger calls = new AtomicInteger();
        check(busy.adapter.execute(busy.scope, () -> { calls.incrementAndGet(); return true; }), "busy instrument does not suppress native use");
        check(calls.get() == 1 && busy.instrument.token == owner && busy.instrument.enables == 0 && busy.instrument.disables == 0,
                "busy instrument is neither replaced nor disabled");
        check(!busy.page().get("available").getAsBoolean(), "busy unobserved use is not mislabeled as a capture");
        check("instrumentation_busy".equals(busy.page().get("reason").getAsString()), "busy observation has a target-specific unavailable reason");
        IllegalStateException busyFailure = new IllegalStateException("busy native failure");
        try {
            busy.adapter.execute(busy.scope, () -> { calls.incrementAndGet(); throw busyFailure; });
            throw new AssertionError("busy native exception was suppressed");
        } catch (IllegalStateException actual) { check(actual == busyFailure && calls.get() == 2, "busy native exception propagates without retry"); }
        Fixture failedEnable = new Fixture(); failedEnable.instrument.failEnable = true;
        AtomicInteger enableCalls = new AtomicInteger();
        check(failedEnable.adapter.execute(failedEnable.scope, () -> { enableCalls.incrementAndGet(); return true; })
                && enableCalls.get() == 1, "failed observer preparation cannot suppress or retry native use");
        check("instrument_enable_failed".equals(failedEnable.page().get("reason").getAsString())
                && failedEnable.page().getAsJsonObject("diagnostic").get("errorClass").getAsString().equals(IllegalStateException.class.getName()),
                "instrument setup failure is reported without retaining an exception object");
        Fixture failed = new Fixture();
        IllegalStateException original = new IllegalStateException("native failure");
        try {
            failed.adapter.execute(failed.scope, () -> { failed.observe(5, "L", description("Output Bus")); throw original; });
            throw new AssertionError("native exception was suppressed");
        } catch (IllegalStateException actual) { check(actual == original, "original native exception propagates unchanged"); }
        check(!failed.instrument.enabled() && failed.instrument.disables == 1, "native exception reliably clears current-thread instrument");
        JsonObject result = failed.page();
        check(!result.get("nativeInvocationReturned").getAsBoolean() && result.get("eventCaptureTruncated").getAsBoolean(), "failed native invocation is reported as partial evidence");
        Fixture observerFailure = new Fixture();
        check(observerFailure.adapter.execute(observerFailure.scope, () -> {
            observerFailure.adapter.observe(observerFailure.instrument.token, observerFailure.world, 1, 2, 3, 4, 5, 6,
                    "Fixture", "L", () -> { throw new IllegalArgumentException("descriptor failure"); });
            return true;
        }), "descriptor failure does not change the native use result");
        check(!observerFailure.instrument.enabled() && observerFailure.page().get("detailErrors").getAsInt() == 1, "descriptor failure remains observable and releases instrumentation");
    }

    private static void limits() {
        Fixture positions = new Fixture();
        AtomicInteger nativeFinished = new AtomicInteger();
        JsonObject compact = description("Hatch");
        positions.adapter.execute(positions.scope, () -> {
            for (int i = 0; i < BlueprintObservationAdapter.POSITION_LIMIT + 2; i++) positions.adapter.observe(positions.instrument.token, positions.world,
                    i >= BlueprintObservationAdapter.POSITION_LIMIT ? 100 : 0, 0, 0, 0, 0, 0, "FixtureElement", "L", () -> compact);
            nativeFinished.incrementAndGet(); return true;
        });
        JsonObject pos = positions.page();
        check(pos.get("capturedPositions").getAsInt() == BlueprintObservationAdapter.POSITION_LIMIT
                && pos.get("observedVisits").getAsInt() == BlueprintObservationAdapter.POSITION_LIMIT + 2,
                "coordinate collection caps without aborting later native iteration");
        check(pos.getAsJsonObject("truncation").get("positions").getAsBoolean() && nativeFinished.get() == 1, "position omission is explicit while original use completes");
        check(!pos.get("capturedVisitsComplete").getAsBoolean() && pos.getAsJsonObject("observedBounds").get("complete").getAsBoolean()
                && pos.getAsJsonObject("observedBounds").get("boundedVisits").getAsInt() == BlueprintObservationAdapter.POSITION_LIMIT + 2
                && pos.getAsJsonObject("observedBounds").getAsJsonObject("worldXYZ").getAsJsonObject("max").get("x").getAsInt() == 100,
                "O(1) bounds still include later visits omitted by the coordinate cap");
        check(pos.get("uncapturedVisits").getAsInt() == 2 && pos.get("captureLossDetected").getAsBoolean()
                && !pos.get("captureContinuationAvailable").getAsBoolean(), "exact omitted native visit count remains explicit and cannot be continued by paging");
        String lostCapture = pos.get("captureId").getAsString();
        JsonObject exhausted = positions.page(lostCapture, BlueprintObservationAdapter.POSITION_LIMIT, "", 128, null);
        check(!exhausted.get("hasMore").getAsBoolean() && !exhausted.get("capturedVisitsComplete").getAsBoolean()
                && exhausted.get("uncapturedVisits").getAsInt() == 2 && exhausted.get("captureLossDetected").getAsBoolean(),
                "a terminal stored page cannot turn a lossy native capture into a complete structure plan");
        Fixture groups = new Fixture();
        groups.adapter.execute(groups.scope, () -> { for (int i = 0; i < 130; i++) groups.observe(i, "L" + i, description("Group" + i)); return true; });
        check(groups.page().getAsJsonObject("truncation").get("groups").getAsBoolean() && groups.page().get("capturedPositions").getAsInt() == 128,
                "element group cap reports partial capture");
        Fixture budget = new Fixture();
        budget.adapter.execute(budget.scope, () -> {
            budget.adapter.observe(budget.instrument.token, budget.world, 1, 2, 3, 4, 5, 6, "Fixture", "L",
                    () -> { budget.clock.addAndGet(4000001L); return description("Slow Getter"); });
            budget.observe(2, "L", description("Slow Getter")); return true;
        });
        check(budget.page().getAsJsonObject("truncation").get("workBudget").getAsBoolean() && budget.page().get("capturedPositions").getAsInt() == 1,
                "a non-preemptible getter can exceed the soft budget, then further collection stops");
        JsonObject partialBounds = budget.page(null, 0, "", 128, 0);
        check(!partialBounds.getAsJsonObject("observedBounds").get("complete").getAsBoolean()
                && partialBounds.getAsJsonObject("observedBounds").get("boundedVisits").getAsInt() == 1
                && !partialBounds.getAsJsonObject("planning").get("available").getAsBoolean()
                && "observed_bounds_partial".equals(partialBounds.getAsJsonObject("planning").get("reason").getAsString()),
                "work budget also stops bounds and disables floor decisions rather than trusting a captured prefix");
        check(!partialBounds.getAsJsonObject("planning").get("observedVisitsComplete").getAsBoolean()
                && partialBounds.getAsJsonObject("planning").has("worldHeightConflict")
                && !partialBounds.getAsJsonObject("planning").has("raiseBlocks"),
                "partial bounds preserve observed height evidence but cannot authorize an elevation");
        Fixture nativeTime = new Fixture();
        nativeTime.adapter.execute(nativeTime.scope, () -> {
            nativeTime.clock.addAndGet(50000000L); nativeTime.observe(1, "L", description("Hatch"));
            nativeTime.clock.addAndGet(50000000L); nativeTime.observe(2, "L", description("Hatch")); return true;
        });
        check(!nativeTime.page().getAsJsonObject("truncation").get("workBudget").getAsBoolean() && nativeTime.page().get("capturedPositions").getAsInt() == 2,
                "native blueprint time is excluded from observation work budget");
        Fixture bytes = new Fixture();
        JsonObject huge = description("Hatch"); StringBuilder large = new StringBuilder();
        for (int i = 0; i < BlueprintObservationAdapter.CAPTURE_BYTE_LIMIT; i++) large.append('x'); huge.addProperty("oversized", large.toString());
        bytes.adapter.execute(bytes.scope, () -> { bytes.observe(1, "L", huge); return true; });
        check(bytes.page().getAsJsonObject("truncation").get("bytes").getAsBoolean() && bytes.page().get("capturedPositions").getAsInt() == 0,
                "oversized descriptor cannot exceed the capture byte limit");
        Fixture metadata = new Fixture();
        JsonObject trigger = new JsonObject(); trigger.addProperty("channelData", large.substring(0, 6000));
        BlueprintObservationAdapter.Context largeTrigger = new BlueprintObservationAdapter.Context(metadata.world, metadata.tile,
                metadata.meta, metadata.definition, 0, 1, 2, 3, "session", "player", "NORTH", 2, trigger.toString(), null, "tool");
        metadata.adapter.execute(largeTrigger, () -> { metadata.observe(1, "L", description("Hatch")); return true; });
        check(!metadata.adapter.page(0, metadata.tile, metadata.meta, metadata.definition, "NORTH", 2, 1, 2, 3, "", 128, 4096)
                .get("available").getAsBoolean(), "large trigger metadata respects the remaining combined response byte budget");
    }

    private static void invalidation() {
        Fixture f = new Fixture(); f.capture(); f.adapter.invalidate();
        check(!f.page().get("available").getAsBoolean(), "world/profile/session invalidation discards captures");
        Fixture during = new Fixture();
        during.adapter.execute(during.scope, () -> { during.observe(1, "L", description("Hatch")); during.adapter.invalidate(); return true; });
        check(!during.page().get("available").getAsBoolean(), "invalidation during a native use prevents publishing its old scope");
        Fixture ttl = new Fixture(); ttl.capture();
        check(ttl.page().getAsJsonObject("limits").get("ttlMillis").getAsLong() == 120000L,
                "hint retention covers a bounded full construction task without changing live validation");
        ttl.clock.set(BlueprintObservationAdapter.TTL_NANOS + 1);
        check(!ttl.page().get("available").getAsBoolean(), "short-lived observation expires");
        check("native_observation_expired".equals(ttl.page().get("reason").getAsString()), "expired capture is distinguishable from an uninstrumented use");
        Fixture expiredCursor = new Fixture(); expiredCursor.capture(); String expiredId = expiredCursor.page().get("captureId").getAsString();
        expiredCursor.clock.set(BlueprintObservationAdapter.TTL_NANOS + 1);
        expect("stale_capture", () -> expiredCursor.page(expiredId, 0, "", 128, null));
        Fixture validation = new Fixture(); validation.capture(); validation.clock.set(1000000001L);
        check(!validation.page().get("available").getAsBoolean(), "server reader requires recent client-thread binding validation");
        check("client_validation_not_recent".equals(validation.page().get("reason").getAsString()), "validation lag has an explicit unavailable reason");
        Fixture mode = new Fixture(); mode.capture();
        JsonObject disagreement = mode.adapter.page(0, mode.tile, mode.meta, mode.definition, "NORTH", 3, 1, 2, 3, "", 128, 131072);
        check(disagreement.get("available").getAsBoolean() && !disagreement.get("contextMatches").getAsBoolean()
                && !disagreement.get("modeMatches").getAsBoolean(), "server mode mismatch preserves native client evidence with an explicit warning");
        check(disagreement.get("capturedMode").getAsInt() == 2 && disagreement.get("serverMode").getAsInt() == 3
                && disagreement.get("evidenceOnly").getAsBoolean() && disagreement.has("contextWarning")
                && !disagreement.get("completeStructure").getAsBoolean(), "mode disagreement reports both public modes without claiming a server plan");
        check(mode.page().get("available").getAsBoolean(), "reading a mode disagreement does not silently discard original observation");
        JsonObject unknownMode = mode.adapter.page(0, mode.tile, mode.meta, mode.definition, "NORTH", null, 1, 2, 3, "", 128, 131072);
        check(unknownMode.get("available").getAsBoolean() && unknownMode.get("serverMode").isJsonNull()
                && !unknownMode.get("contextMatches").getAsBoolean(), "an unavailable server mode is explicit and cannot claim agreement with a known captured mode");
        Fixture facing = new Fixture(); facing.capture();
        check(!facing.adapter.page(0, facing.tile, facing.meta, facing.definition, "SOUTH", 2, 1, 2, 3, "", 128, 131072).get("available").getAsBoolean(),
                "authoritative facing mismatch prevents stale coordinates");
        JsonObject facingReason = facing.page();
        check("server_facing_mismatch".equals(facingReason.get("reason").getAsString())
                && "NORTH".equals(facingReason.getAsJsonObject("diagnostic").get("capturedFacing").getAsString())
                && "SOUTH".equals(facingReason.getAsJsonObject("diagnostic").get("currentFacing").getAsString()),
                "facing rejection retains bounded captured/current values for diagnosis");
        facingReason.getAsJsonObject("diagnostic").addProperty("currentFacing", "mutated");
        check("SOUTH".equals(facing.page().getAsJsonObject("diagnostic").get("currentFacing").getAsString()), "diagnostic readers cannot mutate cached failure details");
        Fixture replacement = new Fixture(); replacement.capture(); replacement.page();
        check(!replacement.adapter.page(0, new Object(), replacement.meta, replacement.definition, "NORTH", 2, 1, 2, 3, "", 128, 131072).get("available").getAsBoolean(),
                "same-class server controller replacement invalidates its bound capture");
        check("server_controller_identity_changed".equals(replacement.page().get("reason").getAsString()),
                "mode evidence relaxation does not relax server controller identity safety");
        Fixture changedClass = new Fixture(); changedClass.capture();
        check("server_meta_class_mismatch".equals(changedClass.adapter.page(0, changedClass.tile, "different class", changedClass.definition,
                "NORTH", 2, 1, 2, 3, "", 128, 131072).get("reason").getAsString()), "meta class mismatch remains rejected and diagnosed");
        Fixture boundedReason = new Fixture(); boundedReason.capture();
        boundedReason.adapter.page(0, boundedReason.tile, boundedReason.meta, boundedReason.definition, "SOUTH", 2, 1, 2, 3, "", 128, 131072);
        check("structure_page_byte_budget".equals(boundedReason.adapter.page(0, boundedReason.tile, boundedReason.meta, boundedReason.definition,
                "NORTH", 2, 1, 2, 3, "", 128, 100).get("reason").getAsString()), "unavailable diagnostics share the bounded response budget");
        boundedReason.clock.set(BlueprintObservationAdapter.TTL_NANOS + 1);
        check("no_current_native_hint_observation".equals(boundedReason.page().get("reason").getAsString()), "diagnostics expire and do not become persistent controller data");
        Fixture capped = new Fixture();
        BlueprintObservationAdapter.Context oldest = capped.scope;
        for (int i = 0; i < 5; i++) {
            BlueprintObservationAdapter.Context scope = capped.scope(i + 1);
            capped.adapter.execute(scope, () -> { capped.observe(1, "L", description("Hatch")); return true; });
        }
        check(!capped.adapter.page(0, capped.tile, capped.meta, capped.definition, "NORTH", 2, oldest.x, 2, 3, "", 128, 131072).get("available").getAsBoolean(),
                "only four recent controllers are retained");
    }

    private static JsonObject description(String category) {
        JsonObject result = new JsonObject(); JsonArray keys = new JsonArray(); keys.add(new com.google.gson.JsonPrimitive(category));
        result.add("descriptionKeys", keys); result.add("description", keys); result.add("suggestedCandidates", new JsonArray());
        return result;
    }
    private static JsonObject request(int x, int group) {
        JsonObject row = new JsonObject(); row.addProperty("x", x); row.addProperty("y", 20); row.addProperty("z", 30); row.addProperty("group", group); return row;
    }
    private static JsonObject candidateDetail(int metadata) {
        JsonObject detail = description("Hatch"); detail.addProperty("loaded", true); detail.addProperty("candidateCoverage", "public_stack_suggestions");
        if (metadata >= 0) {
            JsonObject candidate = new JsonObject(); candidate.addProperty("registryName", "fixture:casing"); candidate.addProperty("metadata", metadata);
            candidate.addProperty("count", 1); candidate.addProperty("hasTag", false); candidate.addProperty("nbtIdentity", "");
            detail.getAsJsonArray("suggestedCandidates").add(candidate);
        }
        return detail;
    }
    private static void positionCandidateGetter() {
        Fixture f = new Fixture(); Object element = new Object(); f.scope.elementGroups.put(element, "L");
        f.adapter.execute(f.scope, () -> { f.observe(10, "L", description("Hatch")); f.observe(11, "L", description("Hatch")); f.observe(12, "L", description("Hatch")); return true; });
        JsonObject runtime = f.page(); JsonArray requested = new JsonArray(); requested.add(request(10, 0)); requested.add(request(11, 0)); requested.add(request(12, 0));
        AtomicInteger calls = new AtomicInteger();
        BlueprintObservationAdapter.CandidateGetter getter = (actual, scope, point) -> { check(actual == element, "getter retains the exact public element, not its class"); calls.incrementAndGet(); return candidateDetail(point.x); };
        JsonObject candidates = f.adapter.candidatePage(f.scope, runtime, requested, 0, getter);
        check(candidates.get("complete").getAsBoolean() && candidates.get("processedCount").getAsInt() == 3 && calls.get() == 3,
                "position reads invoke the current public getter separately at every requested captured XYZ");
        check(candidates.getAsJsonArray("positions").get(1).getAsJsonObject().getAsJsonObject("detail").getAsJsonArray("suggestedCandidates").get(0).getAsJsonObject().get("metadata").getAsInt() == 11,
                "later XYZ cannot borrow the first representative's candidate");
        check(!candidates.get("fullStructureChecked").getAsBoolean() && !candidates.get("formationVerified").getAsBoolean()
                && candidates.get("readOnly").getAsBoolean() && candidates.get("ownerBound").getAsBoolean(), "fresh suggestions never claim construction or formation");
        candidates.getAsJsonArray("positions").get(0).getAsJsonObject().getAsJsonObject("detail").addProperty("loaded", false);
        check(f.adapter.candidatePage(f.scope, runtime, requested, 0, getter).getAsJsonArray("positions").get(0).getAsJsonObject().getAsJsonObject("detail").get("loaded").getAsBoolean(),
                "candidate data is freshly copied rather than reused from a mutable response");
        JsonObject zero = f.adapter.candidatePage(f.scope, runtime, requested, 0, (actual, scope, point) -> candidateDetail(-1));
        check(zero.getAsJsonArray("positions").get(0).getAsJsonObject().get("complete").getAsBoolean()
                && zero.getAsJsonArray("positions").get(0).getAsJsonObject().get("candidateCount").getAsInt() == 0, "complete known zero suggestions remain distinct from unknown");
        JsonObject unknown = f.adapter.candidatePage(f.scope, runtime, requested, 0, (actual, scope, point) -> { JsonObject detail = candidateDetail(-1); detail.addProperty("candidateCoverage", "unavailable_or_predicate_only"); return detail; });
        check(!unknown.getAsJsonArray("positions").get(0).getAsJsonObject().get("complete").getAsBoolean(), "predicate-only data never invents stack candidates");
        JsonObject truncated = f.adapter.candidatePage(f.scope, runtime, requested, 0, (actual, scope, point) -> { JsonObject detail = candidateDetail(1); detail.addProperty("candidatesTruncated", true); return detail; });
        check(!truncated.getAsJsonArray("positions").get(0).getAsJsonObject().get("complete").getAsBoolean(), "truncated candidate lists stay unknown");
        JsonObject failed = f.adapter.candidatePage(f.scope, runtime, requested, 0, (actual, scope, point) -> { throw new IllegalStateException("fixture getter"); });
        check("unknown".equals(failed.getAsJsonArray("positions").get(0).getAsJsonObject().get("status").getAsString()), "public getter failures remain read-only unknown rows");
        JsonArray foreign = new JsonArray(); foreign.add(request(99, 0)); expect("invalid_args", () -> f.adapter.candidatePage(f.scope, runtime, foreign, 0, getter));
        JsonArray wrongGroup = new JsonArray(); wrongGroup.add(request(10, 1)); expect("invalid_args", () -> f.adapter.candidatePage(f.scope, runtime, wrongGroup, 0, getter));
        JsonArray duplicate = new JsonArray(); duplicate.add(request(10, 0)); duplicate.add(request(10, 0)); expect("invalid_args", () -> f.adapter.candidatePage(f.scope, runtime, duplicate, 0, getter));
        JsonArray excessive = new JsonArray(); for (int i = 0; i < 17; i++) excessive.add(request(i, 0)); expect("invalid_args", () -> f.adapter.candidatePage(f.scope, runtime, excessive, 0, getter));
        expect("invalid_args", () -> f.adapter.candidatePage(f.scope, runtime, requested, 3, getter));
        BlueprintObservationAdapter.Context session = new BlueprintObservationAdapter.Context(f.world, f.tile, f.meta, f.definition, 0, 1, 2, 3, "other", "player", "NORTH", 2, "{}", null, "tool");
        expect("stale_capture", () -> f.adapter.candidatePage(session, runtime, requested, 0, getter));
        JsonObject partial = new com.google.gson.JsonParser().parse(runtime.toString()).getAsJsonObject(); partial.addProperty("eventCaptureTruncated", true);
        expect("stale_capture", () -> f.adapter.candidatePage(f.scope, partial, requested, 0, getter));
        f.clock.set(0); BlueprintObservationAdapter.CandidateGetter slow = (actual, scope, point) -> { f.clock.addAndGet(BlueprintObservationAdapter.WORK_BUDGET_NANOS); return candidateDetail(point.x); };
        JsonObject first = f.adapter.candidatePage(f.scope, runtime, requested, 0, slow);
        check(first.get("partial").getAsBoolean() && first.get("nextCandidateOffset").getAsInt() == 1 && first.get("budgetLimited").getAsBoolean(), "cooperative getter pages return exact positive prefixes");
        JsonObject second = f.adapter.candidatePage(f.scope, runtime, requested, 1, getter);
        check(second.get("complete").getAsBoolean() && second.get("processedCount").getAsInt() == 2
                && second.getAsJsonArray("positions").get(0).getAsJsonObject().get("requestIndex").getAsInt() == 1, "candidate continuations preserve original request indices");
        f.clock.set(BlueprintObservationAdapter.TTL_NANOS + 1);
        expect("stale_capture", () -> f.adapter.candidatePage(f.scope, runtime, requested, 0, getter));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { check(code.equals(error.code), "error code " + code); }
    }
    private static final class FakeInstrument implements BlueprintObservationAdapter.Instrument {
        Object token; int enables, disables; boolean failEnable;
        public boolean enabled() { return token != null; }
        public void enable(Object id) { if (enabled()) throw new IllegalStateException("busy"); if (failEnable) throw new IllegalStateException("fixture enable failure"); token = id; enables++; }
        public void disable() { if (!enabled()) throw new IllegalStateException("not enabled"); token = null; disables++; }
    }
    private static final class Fixture {
        final AtomicLong clock = new AtomicLong(), readAdvance = new AtomicLong(); final FakeInstrument instrument = new FakeInstrument();
        final Object world = new Object(), tile = new Object(), meta = new Object(), definition = new Object();
        final BlueprintObservationAdapter adapter = new BlueprintObservationAdapter(instrument, () -> clock.getAndAdd(readAdvance.get()));
        final BlueprintObservationAdapter.Context scope = scope(1);
        BlueprintObservationAdapter.Context scope(int x) {
            return new BlueprintObservationAdapter.Context(world, tile, meta, definition, 0, x, 2, 3, "session", "player", "NORTH", 2, "{}", null, "tool");
        }
        void observe(int x, String group, JsonObject details) {
            adapter.observe(instrument.token, world, x, 20, 30, x + 3, 4, 5, "FixtureElement", group, () -> details);
        }
        void capture() { adapter.execute(scope, () -> { observe(1, "L", description("Hatch")); return true; }); }
        JsonObject page() { return adapter.page(0, tile, meta, definition, "NORTH", 2, 1, 2, 3, "", 128, 131072); }
        JsonObject page(String captureId, int offset, String query, int limit, Integer floor) {
            return adapter.page(0, tile, meta, definition, "NORTH", 2, 1, 2, 3, query, captureId, offset, floor, limit, 131072);
        }
    }
}
