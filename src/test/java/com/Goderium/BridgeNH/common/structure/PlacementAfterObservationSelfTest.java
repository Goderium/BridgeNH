package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

/** Scalar AFTER fixtures exercise truthful completeness and readback fallback; no placement is replayed. */
public final class PlacementAfterObservationSelfTest {
    private static int checks;
    public static void main(String[] args) { System.out.println("PlacementAfterObservation checks=" + run()); }
    public static int run() { checks = 0; ordinary(); inventories(); fluids(); identities(); limits(); failures(); legacyFluids(); return checks; }
    private static void ordinary() {
        Fixture f = new Fixture(); JsonObject proof = f.read();
        check(proof.get("complete").getAsBoolean() && !proof.get("partial").getAsBoolean(), "real loaded ordinary block has complete public identity coverage");
        check(proof.get("worldTick").getAsLong() == 80 && proof.get("source").getAsString().equals("integrated_server"), "AFTER keeps its actual owner tick and source");
        check(proof.get("sessionId").getAsString().equals("session") && proof.get("playerId").getAsString().equals("player")
                && proof.get("worldName").getAsString().equals("world") && proof.getAsJsonObject("target").get("y").getAsInt() == 5, "owner and exact target are explicit");
        JsonObject data = proof.getAsJsonObject("data");
        check(!data.has("inventory") && !data.has("fluids") && !data.has("tileClass"), "ordinary block never receives invented empty storage");
        check(proof.getAsJsonObject("coverage").get("inventory").getAsString().equals("not_exposed"), "absent interface is explicit coverage, not an empty inventory");
        check(f.dataReads == 1 && f.ownerReads == 1, "one optional owner read has no hidden extra operation or retry");
    }
    private static void inventories() {
        Fixture empty = new Fixture(); empty.inventory = slots(2); empty.tile = true;
        check(empty.read().get("complete").getAsBoolean(), "explicit nulls for every actual slot prove an empty exposed inventory");
        Fixture occupied = new Fixture(); occupied.inventory = slots(2); occupied.tile = true;
        occupied.inventory.getAsJsonArray("slots").get(1).getAsJsonObject().add("item", item());
        JsonObject proof = occupied.read(); check(proof.get("complete").getAsBoolean()
                && proof.getAsJsonObject("data").getAsJsonObject("inventory").getAsJsonArray("slots").get(1).getAsJsonObject().get("item").isJsonObject(), "occupied slots are preserved instead of counted as empty");
        for (int fault = 0; fault < 7; fault++) {
            Fixture f = new Fixture(); f.tile = true; f.inventory = slots(2);
            if (fault == 0) f.inventory.addProperty("truncated", true);
            if (fault == 1) f.inventory.addProperty("totalSlots", 257);
            if (fault == 2) f.inventory.getAsJsonArray("slots").get(1).getAsJsonObject().remove("item");
            if (fault == 3) f.inventory.getAsJsonArray("slots").get(1).getAsJsonObject().addProperty("index", 0);
            if (fault >= 4) {
                JsonObject stack = item();
                if (fault == 4) stack.addProperty("count", 0);
                if (fault == 5) stack.addProperty("registryName", "null");
                if (fault == 6) stack.addProperty("hasTag", "false");
                f.inventory.getAsJsonArray("slots").get(1).getAsJsonObject().add("item", stack);
            }
            partial(f.read(), "missing/truncated/malformed inventory coverage fault " + fault);
        }
        Fixture unknown = new Fixture(); unknown.tile = true; unknown.inventoryExposed = true;
        partial(unknown.read(), "exposed inventory with missing data remains unknown");
    }
    private static void fluids() {
        Fixture empty = new Fixture(); empty.tile = true; empty.fluids = tanks(0);
        check(empty.read().get("complete").getAsBoolean(), "known native zero-tank array proves public tank coverage");
        Fixture full = new Fixture(); full.tile = true; full.fluids = tanks(1);
        full.fluids.getAsJsonArray("tanks").get(0).getAsJsonObject().addProperty("fluid", "water");
        full.fluids.getAsJsonArray("tanks").get(0).getAsJsonObject().addProperty("amount", 100);
        check(full.read().get("complete").getAsBoolean(), "actual nonempty tank content remains observed");
        for (int fault = 0; fault < 6; fault++) {
            Fixture f = new Fixture(); f.tile = true; f.fluids = tanks(1);
            if (fault == 0) f.rawTanksKnown = false;
            if (fault == 1) f.fluids.addProperty("truncated", true);
            if (fault == 2) f.fluids.addProperty("totalTanks", 33);
            if (fault == 3) f.fluids.getAsJsonArray("tanks").get(0).getAsJsonObject().addProperty("capacity", -1);
            if (fault >= 4) {
                JsonObject tank = f.fluids.getAsJsonArray("tanks").get(0).getAsJsonObject(); tank.addProperty("fluid", "water");
                if (fault == 5) tank.addProperty("amount", 2001);
            }
            partial(f.read(), "unknown/truncated/malformed native tank coverage fault " + fault);
        }
        Fixture unknown = new Fixture(); unknown.tile = true; unknown.fluidsExposed = true;
        partial(unknown.read(), "missing native tank information never proves empty tanks");
    }
    private static void identities() {
        for (int fault = 0; fault < 8; fault++) {
            Fixture f = new Fixture();
            if (fault == 0) f.data.addProperty("source", "client");
            if (fault == 1) f.data.addProperty("dimension", 1);
            if (fault == 2) f.data.addProperty("x", 1);
            if (fault == 3) f.data.addProperty("metadata", 0);
            if (fault == 4) f.data.addProperty("registryName", "fixture:other");
            if (fault == 5) f.data.addProperty("metadata", "81");
            if (fault == 6) f.unchanged = false;
            if (fault == 7) f.advanceTick = true;
            partial(f.read(), "same AFTER owner and typed exact identity required fault " + fault);
        }
        Fixture gt = new Fixture(); gt.tile = true; JsonObject identity = new JsonObject(); identity.addProperty("metaTileId", 604); identity.addProperty("metaClass", "FixtureMeta");
        gt.target.add("gregtech", copy(identity)); gt.data.add("gregtech", copy(identity));
        check(gt.read().get("complete").getAsBoolean(), "exact public GT identity agrees with original serverTarget");
        gt.data.getAsJsonObject("gregtech").addProperty("metaTileId", 304); partial(gt.read(), "different GT meta ID cannot claim complete");
        gt.data.getAsJsonObject("gregtech").addProperty("metaTileId", 604); gt.data.getAsJsonObject("gregtech").addProperty("metaClass", "OtherMeta");
        partial(gt.read(), "different GT meta class cannot claim complete");
        gt.data.remove("gregtech"); partial(gt.read(), "missing public GT identity remains unknown");
        Fixture pendingGt = new Fixture(); pendingGt.tile = true; pendingGt.gregtechExposed = true;
        partial(pendingGt.read(), "recognized GT tile without an initialized public meta identity remains unknown");
    }
    private static void limits() {
        Fixture time = new Fixture(); time.readNanos = PlacementAfterObservation.WORK_NANOS;
        JsonObject timed = time.read(); partial(timed, "slow public getter is explicit partial coverage");
        check(timed.getAsJsonObject("coverage").get("budgetLimited").getAsBoolean(), "read-only budget limit is visible");
        Fixture bytes = new Fixture(); StringBuilder large = new StringBuilder(); for (int i = 0; i < PlacementAfterObservation.BYTE_LIMIT; i++) large.append('x');
        bytes.data.addProperty("oversized", large.toString()); JsonObject bounded = bytes.read(); partial(bounded, "oversized public data is not advertised complete");
        check(!bounded.has("data") && bounded.getAsJsonObject("error").get("code").getAsString().equals("result_too_large"), "oversized optional data is excluded while explicit error remains");
        Fixture deadline = new Fixture(); deadline.deadline = 0; partial(deadline.read(), "operation deadline prevents optional extra read");
        check(deadline.dataReads == 0, "expired operation does not call the target getters");
    }
    private static void failures() {
        Fixture unloaded = new Fixture(); unloaded.loaded = false; JsonObject known = unloaded.read(); partial(known, "known unloaded target is not empty");
        check(known.get("loaded").isJsonPrimitive() && !known.get("loaded").getAsBoolean() && unloaded.dataReads == 0, "known unloaded is explicit and performs no block read");
        Fixture loadError = new Fixture(); loadError.loadedError = true; JsonObject unknown = loadError.read(); partial(unknown, "failed loaded getter remains unknown");
        check(unknown.get("loaded").isJsonNull(), "unknown loaded is never rewritten as known false");
        Fixture error = new Fixture(); error.readError = true; JsonObject receipt = new JsonObject(); receipt.addProperty("serverConfirmed", true);
        receipt.addProperty("serverConsumptionConfirmed", true); JsonObject originalAfter = new JsonObject(); originalAfter.addProperty("complete", true); originalAfter.addProperty("totalSlots", 41);
        receipt.add("serverAfter", originalAfter); receipt.add("serverPlacementObservation", error.read());
        check(receipt.get("serverConfirmed").getAsBoolean() && receipt.get("serverConsumptionConfirmed").getAsBoolean()
                && receipt.get("serverAfter") == originalAfter && error.dataReads == 1, "optional failure preserves original confirmed accounting and never repeats a mutation");
        partial(receipt.getAsJsonObject("serverPlacementObservation"), "optional read exception is explicit unavailable evidence");
    }
    private static void legacyFluids() {
        IFluidHandler handler = new IFluidHandler() {
            public int fill(ForgeDirection side, FluidStack resource, boolean apply) { throw new AssertionError("read must not fill"); }
            public FluidStack drain(ForgeDirection side, FluidStack resource, boolean apply) { throw new AssertionError("read must not drain"); }
            public FluidStack drain(ForgeDirection side, int amount, boolean apply) { throw new AssertionError("read must not drain"); }
            public boolean canFill(ForgeDirection side, net.minecraftforge.fluids.Fluid fluid) { return false; }
            public boolean canDrain(ForgeDirection side, net.minecraftforge.fluids.Fluid fluid) { return false; }
            public FluidTankInfo[] getTankInfo(ForgeDirection side) { return null; }
        };
        check(JsonData.fluids(handler).get("totalTanks").getAsInt() == 0, "ordinary world.inspect preserves its existing null-info response shape");
        try { JsonData.fluids(handler, null); throw new AssertionError("new captured path must retain unknown"); }
        catch (BridgeException failure) { check(failure.code.equals("inspection_failed"), "captured native null tank info is rejected instead of becoming empty"); }
    }
    private static JsonObject slots(int total) {
        JsonObject value = new JsonObject(); value.addProperty("totalSlots", total); value.addProperty("truncated", false); JsonArray rows = new JsonArray();
        for (int i = 0; i < total; i++) { JsonObject row = new JsonObject(); row.addProperty("index", i); row.add("item", JsonNull.INSTANCE); rows.add(row); } value.add("slots", rows); return value;
    }
    private static JsonObject tanks(int total) {
        JsonObject value = new JsonObject(); value.addProperty("totalTanks", total); value.addProperty("truncated", false); JsonArray tanks = new JsonArray();
        for (int i = 0; i < total; i++) { JsonObject tank = new JsonObject(); tank.addProperty("index", i); tank.addProperty("capacity", 1000); tanks.add(tank); } value.add("tanks", tanks); return value;
    }
    private static JsonObject item() { JsonObject item = new JsonObject(); item.addProperty("registryName", "fixture:item"); item.addProperty("metadata", 81); item.addProperty("count", 1); item.addProperty("hasTag", false); return item; }
    private static JsonObject copy(JsonObject value) { return new JsonParser().parse(value.toString()).getAsJsonObject(); }
    private static void partial(JsonObject proof, String text) { check(!proof.get("complete").getAsBoolean() && proof.get("partial").getAsBoolean() && proof.has("error"), text); }
    private static void check(boolean condition, String text) { if (!condition) throw new AssertionError(text); checks++; }
    private static final class Fixture implements PlacementAfterObservation.Access {
        final AtomicLong clock = new AtomicLong(); final JsonObject target = new JsonObject(), data = new JsonObject();
        JsonObject inventory, fluids; boolean tile, inventoryExposed, fluidsExposed, gregtechExposed, rawTanksKnown = true;
        boolean loaded = true, unchanged = true, advanceTick, loadedError, readError;
        long tick = 80, readNanos, deadline = Long.MAX_VALUE; int dataReads, ownerReads;
        Fixture() {
            target.addProperty("registryName", "fixture:block"); target.addProperty("metadata", 81);
            data.addProperty("source", "integrated_server"); data.addProperty("dimension", 0);
            data.addProperty("x", 0); data.addProperty("y", 5); data.addProperty("z", 0);
            data.addProperty("registryName", "fixture:block"); data.addProperty("metadata", 81);
        }
        JsonObject read() { return PlacementAfterObservation.read("session", "player", "world", 0, 0, 5, 0, target, this, deadline, clock::get); }
        public boolean loaded() { if (loadedError) throw new IllegalStateException("unavailable"); return loaded; }
        public long tick() { return tick; }
        public JsonObject data(PlacementAfterObservation.Coverage coverage) {
            dataReads++; if (readError) throw new IllegalStateException("read failed");
            JsonObject result = copy(data); coverage.interfaces(tile, inventory != null || inventoryExposed, fluids != null || fluidsExposed,
                    gregtechExposed || target.has("gregtech") || data.has("gregtech"));
            if (tile) result.addProperty("tileClass", "FixtureTile");
            if (inventory != null) { result.add("inventory", copy(inventory)); coverage.inventory(result.getAsJsonObject("inventory")); }
            if (fluids != null) { result.add("fluids", copy(fluids)); coverage.fluids(result.getAsJsonObject("fluids"), rawTanksKnown); }
            if (data.has("gregtech")) coverage.gregtech(data.getAsJsonObject("gregtech"));
            clock.addAndGet(readNanos); return result;
        }
        public boolean unchanged() { ownerReads++; if (advanceTick) tick++; return unchanged; }
    }
}
