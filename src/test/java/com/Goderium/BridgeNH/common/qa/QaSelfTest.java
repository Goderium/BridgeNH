package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ownership and public-only report regressions; no optional mod or live world is loaded. */
public final class QaSelfTest {
    private static int checks;

    public static int run() {
        checks = 0;
        ownership();
        worldBinding();
        boundedReports();
        commandArguments();
        runnerMetadata();
        return checks;
    }

    private static void ownership() {
        QaRunTracker tracker = new QaRunTracker();
        tracker.bind(new Object(), new Object(), new Object(), 0);
        Object previous = new Object(); Object fresh = new Object(); Object foreign = new Object();
        QaRunTracker.Ticket pending = tracker.started("fixture", Arrays.asList("fixture:test"), previous, "STARTED", previous);
        check(pending.report(previous) == null, "pre-start global success is never reused for a QA ticket");
        check(!pending.owned() && "running".equals(pending.state(previous, true)), "bridge start receipt does not prove asynchronous result ownership");
        check(pending.report(fresh) == fresh && !pending.owned(), "fresh global report is readable but remains unverified");
        check("completion_unverified".equals(pending.state(fresh, false)), "native idle plus fresh success does not declare owned completion");
        check(pending.report(foreign) == foreign && !pending.owned(), "later foreign run cannot become owned by an old ticket");
        check("completion_unverified".equals(pending.state(previous, false)), "missing report after abort remains unverified");
        QaRunTracker.Ticket synchronous = tracker.started("fixture", Arrays.asList("fixture:test"), previous, "COMPLETED", fresh);
        check(synchronous.owned() && synchronous.report(foreign) == fresh, "synchronous start completion keeps its exact immutable result");
        check("completed".equals(synchronous.state(foreign, true)), "later active native run does not change owned synchronous completion");
        QaRunTracker.Ticket unpublished = tracker.started("fixture", Collections.<String>emptyList(), previous, "COMPLETED", previous);
        check(!unpublished.owned() && unpublished.report(previous) == null, "COMPLETED without a new published result never reuses prior success");
    }

    private static void worldBinding() {
        QaRunTracker tracker = new QaRunTracker(); Object server = new Object(); Object world = new Object(); Object lifecycle = new Object();
        tracker.bind(server, world, lifecycle, 0);
        QaRunTracker.Ticket first = tracker.started("all", Collections.<String>emptyList(), null, "STARTED", null);
        tracker.bind(server, world, lifecycle, 0);
        check(tracker.find(first.id) == first, "unchanged server/world session preserves its QA ticket");
        tracker.bind(server, new Object(), lifecycle, 0);
        expect("stale_run", () -> tracker.find(first.id));
        check(tracker.latest() == null, "world replacement clears latest ticket");
        QaRunTracker.Ticket second = tracker.started("all", Collections.<String>emptyList(), null, "STARTED", null);
        tracker.bind(server, world, new Object(), 0);
        expect("stale_run", () -> tracker.find(second.id));
        QaRunTracker.Ticket third = tracker.started("all", Collections.<String>emptyList(), null, "STARTED", null);
        tracker.bind(server, world, lifecycle, 1);
        expect("stale_run", () -> tracker.find(third.id));
        QaRunTracker.Ticket oldest = tracker.started("all", Collections.<String>emptyList(), null, "STARTED", null);
        for (int i = 0; i < 8; i++) tracker.started("all", Collections.<String>emptyList(), null, "STARTED", null);
        expect("stale_run", () -> tracker.find(oldest.id));
        check(tracker.find(tracker.latest().id) == tracker.latest(), "bounded ticket history retains latest ticket");
    }

    private static void boundedReports() {
        PublicCase fixture = new PublicCase();
        JsonObject item = HorizonQaAdapter.caseResult(fixture, false);
        check("not_requested".equals(item.get("detailsCoverage").getAsString()) && !item.has("failureTrace") && !item.has("outputLines") && !item.has("outputTotal"), "compact case reports omit unrequested details without inventing empty logs");
        check(fixture.traceCalls == 0 && fixture.outputCalls == 0, "compact case report does not call public trace or output getters");
        check("fixture:test".equals(item.get("id").getAsString()) && "FAILED".equals(item.get("status").getAsString()) && item.get("tickCount").getAsInt() == 50 && item.get("required").getAsBoolean(), "compact case preserves identity, outcome, required flag and timings");
        item = HorizonQaAdapter.caseResult(fixture, true);
        check(item.get("failureTrace").getAsString().length() == 4096 && item.get("failureTraceTruncated").getAsBoolean(), "native failure trace is bounded with explicit coverage");
        check(item.get("failureMessage").getAsString().length() == 1024 && item.get("failureMessageTruncated").getAsBoolean(), "native failure message preserves bounded detail");
        check(item.getAsJsonArray("outputLines").size() == 16 && item.get("outputTotal").getAsInt() == 100, "native output is bounded without hiding total line count");
        check(item.getAsJsonArray("outputLines").get(0).getAsString().length() == 512 && item.get("outputTruncated").getAsBoolean(), "individual output line truncation is reported");
        check("requested".equals(item.get("detailsCoverage").getAsString()) && item.get("detailsTruncated").getAsBoolean() && fixture.traceCalls == 1 && fixture.outputCalls == 1, "requested case detail reads existing public getters once and declares truncation");
        expect("qa_api_unavailable", () -> HorizonQaAdapter.caseResult(new PrivateCase(), false));
        PublicIssue issue = new PublicIssue();
        JsonObject compactIssue = HorizonQaAdapter.issueResult(issue, false);
        check("not_requested".equals(compactIssue.get("detailsCoverage").getAsString()) && !compactIssue.has("details") && !compactIssue.has("stackTrace") && issue.detailCalls == 0 && issue.traceCalls == 0, "compact issue omits and does not read unrequested native detail");
        check(compactIssue.get("fatalInCi").getAsBoolean() && "REPORT_WRITE_ERROR".equals(compactIssue.get("kind").getAsString()) && "native report error".equals(compactIssue.get("message").getAsString()), "compact issue preserves core diagnostic fields");
        JsonObject detailedIssue = HorizonQaAdapter.issueResult(issue, true);
        check(detailedIssue.get("details").getAsString().length() == 2048 && detailedIssue.get("stackTrace").getAsString().length() == 4096 && detailedIssue.get("detailsTruncated").getAsBoolean() && "requested".equals(detailedIssue.get("detailsCoverage").getAsString()), "requested native issue detail retains bounded details and stack trace");
        check(!HorizonQaAdapter.includeDetails(new JsonObject()), "qa.results details default to false");
        JsonObject requested = new JsonObject(); requested.addProperty("includeDetails", true);
        check(HorizonQaAdapter.includeDetails(requested), "qa.results accepts explicit boolean detail request");
        for (JsonPrimitive invalid : new JsonPrimitive[] {new JsonPrimitive("true"), new JsonPrimitive(1)}) {
            JsonObject invalidArgs = new JsonObject(); invalidArgs.add("includeDetails", invalid);
            expect("invalid_args", () -> HorizonQaAdapter.includeDetails(invalidArgs));
        }
    }

    private static void commandArguments() {
        JsonObject args = new JsonObject(); JsonArray tokens = new JsonArray();
        tokens.add(new JsonPrimitive("fixture:Suite.test[case-1]")); tokens.add(new JsonPrimitive("-12")); tokens.add(new JsonPrimitive("中文")); args.add("args", tokens);
        check(HorizonQaAdapter.arguments(args).size() == 3, "native token arguments preserve selectors, coordinates and UTF-8 names");
        for (String bad : new String[] {"", "two words", "x\t/stop", "x\n/stop", "x\r/stop", "x\u0000", "x\u007f", repeated('x', 257)}) {
            JsonObject invalid = new JsonObject(); JsonArray array = new JsonArray(); array.add(new JsonPrimitive(bad)); invalid.add("args", array);
            expect("invalid_args", () -> HorizonQaAdapter.arguments(invalid));
        }
        JsonObject tooMany = new JsonObject(); JsonArray array = new JsonArray(); for (int i = 0; i < 9; i++) array.add(new JsonPrimitive("x")); tooMany.add("args", array);
        expect("invalid_args", () -> HorizonQaAdapter.arguments(tooMany));
    }

    private static void runnerMetadata() {
        List<String> invoked = new ArrayList<String>();
        JsonObject config = HorizonQaAdapter.runnerConfig((owner, method) -> {
            invoked.add(owner + "." + method);
            if ("gridOriginX".equals(method)) return -400;
            if ("gridOriginY".equals(method)) return 72;
            if ("gridOriginZ".equals(method)) return 1200;
            if ("modeName".equals(method)) return "interactive";
            if ("turboMultiplier".equals(method)) return 32;
            if ("isTurboActive".equals(method)) return false;
            if ("isBatchActive".equals(method)) return true;
            throw new AssertionError("Unexpected public getter: " + owner + "." + method);
        });
        check(config.get("available").getAsBoolean() && !config.get("partial").getAsBoolean(), "complete fixed public runner configuration has explicit coverage");
        check(config.get("gridOriginX").getAsInt() == -400 && config.get("gridOriginY").getAsInt() == 72 && config.get("gridOriginZ").getAsInt() == 1200, "runner metadata preserves actual configured coordinates");
        check(config.get("turboMultiplier").getAsInt() == 32 && !config.get("turboActive").getAsBoolean() && config.get("nativeBatchActive").getAsBoolean(), "configured turbo multiplier is separate from actual turbo/batch activity");
        check(invoked.equals(Arrays.asList("HorizonQAProperties.gridOriginX", "HorizonQAProperties.gridOriginY", "HorizonQAProperties.gridOriginZ", "HorizonQAProperties.modeName", "HorizonQAProperties.turboMultiplier", "internal.GameTestRunner.isTurboActive", "internal.GameTestRunner.isBatchActive")), "runner metadata reads only the seven verified fixed getters");
        JsonObject partial = HorizonQaAdapter.runnerConfig((owner, method) -> {
            if ("gridOriginX".equals(method)) return 55;
            throw new BridgeException("qa_api_unavailable", "Fixture API is missing.");
        });
        check(partial.get("available").getAsBoolean() && partial.get("partial").getAsBoolean() && partial.get("gridOriginX").getAsInt() == 55 && !partial.has("gridOriginY"), "missing optional getter does not invent a default coordinate or hide available values");
        check(!partial.getAsJsonObject("coverage").getAsJsonObject("gridOriginY").get("available").getAsBoolean() && "public_api_unavailable".equals(partial.getAsJsonObject("coverage").getAsJsonObject("gridOriginY").get("reason").getAsString()), "unavailable runner getter has a field-level reason");
        JsonObject unavailable = HorizonQaAdapter.runnerConfig((owner, method) -> { throw new NoClassDefFoundError("optional fixture"); });
        check(!unavailable.get("available").getAsBoolean() && "public_api_unavailable".equals(unavailable.get("reason").getAsString()), "missing optional runner API yields explicit unavailable metadata");
        JsonObject wrongTypes = HorizonQaAdapter.runnerConfig((owner, method) -> "invented");
        check(!wrongTypes.has("gridOriginX") && !wrongTypes.getAsJsonObject("coverage").getAsJsonObject("gridOriginX").get("available").getAsBoolean(), "wrong public getter type cannot become an invented coordinate");
        JsonObject scope = HorizonQaAdapter.reportedRunScope();
        check(scope.get("dimension").getAsInt() == 0 && scope.get("clearsFixtureCellsBeforePlacement").getAsBoolean() && scope.get("clearIncludesCellMargin").getAsBoolean() && !scope.get("boundsAvailable").getAsBoolean(), "reported fixture placement identifies overworld cell clearing but never invents complete bounds");
        JsonObject warp = HorizonQaAdapter.gtFastForwardScope(true);
        check(warp.get("advancesEUSupplyJobs").getAsBoolean() && !warp.get("advancesAEWorldTicks").getAsBoolean() && !warp.get("advancesWorldTime").getAsBoolean(), "GT fast-forward contract distinguishes GT/EU simulation from AE and world ticks");
        JsonObject noWarp = HorizonQaAdapter.gtFastForwardScope(false);
        check(!noWarp.get("available").getAsBoolean() && !noWarp.has("advancesAEWorldTicks"), "missing public fast-forward method does not advertise an available feature");
    }

    public static class PublicCase {
        int traceCalls, outputCalls;
        public String id() { return "fixture:test"; }
        public String classname() { return "fixture.Suite"; }
        public String name() { return "test"; }
        public String status() { return "FAILED"; }
        public String failureType() { return "ASSERTION"; }
        public String blockedByIssueId() { return ""; }
        public String failureMessage() { return repeated('x', 2000); }
        public String failureTrace() { traceCalls++; return repeated('x', 9000); }
        public boolean required() { return true; }
        public int tickCount() { return 50; }
        public double timeSeconds() { return 2.5; }
        public List<String> outputLines() { outputCalls++; List<String> output = new ArrayList<String>(); for (int i = 0; i < 100; i++) output.add(repeated('x', 1000)); return output; }
    }
    public static class PublicIssue {
        int detailCalls, traceCalls;
        public String id() { return "reporting:fixture"; }
        public String kind() { return "REPORT_WRITE_ERROR"; }
        public String classname() { return "fixture.report"; }
        public String name() { return "report"; }
        public String message() { return "native report error"; }
        public boolean fatalInCi() { return true; }
        public String details() { detailCalls++; return repeated('x', 5000); }
        public String stackTrace() { traceCalls++; return repeated('x', 9000); }
    }
    private static class PrivateCase extends PublicCase {
        @Override public String id() { return "private"; }
    }
    private static String repeated(char c, int count) { char[] chars = new char[count]; Arrays.fill(chars, c); return new String(chars); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void expect(String code, Runnable action) {
        checks++;
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException error) { if (!code.equals(error.code)) throw new AssertionError("Expected " + code + ", got " + error.code); }
    }
}
