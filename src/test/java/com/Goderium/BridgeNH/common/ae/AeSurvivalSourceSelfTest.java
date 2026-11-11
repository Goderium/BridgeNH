package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Directed backing fixtures only; these do not substitute for an actual AE grid/client test. */
public final class AeSurvivalSourceSelfTest {
    private static int checks;
    private static Item fixtureItem;
    public static void main(String[] args) { System.out.println("AE survival directed assertions: " + run()); }
    static int run() {
        checks = 0;
        fixtureItem = new Item() { @Override public String getItemStackDisplayName(ItemStack stack) { return "AE source fixture"; } };
        Item.itemRegistry.addObject(32302, "ae_survival_fixture:material", fixtureItem);
        requests(); inspectionRequestsAndGuards(); finite(); simulationAndEnergy(); creative(); failures(); bounds(); playerAccounting();
        return checks;
    }
    public static JsonObject sourceArgs(String mode) {
        JsonObject result = new JsonObject(); result.addProperty("type", "ae_cells"); result.addProperty("mode", mode);
        result.addProperty("selector", "tile"); result.addProperty("x", 3); result.addProperty("y", 10); result.addProperty("z", -5);
        result.addProperty("side", 2); result.addProperty("expectedGridId", "00000000-0000-0000-0000-000000000001"); return result;
    }
    private static AeSurvivalSource source(Fixture fixture, String mode) {
        return new AeSurvivalSource(new AeSurvivalSource.Request(sourceArgs(mode)), fixture);
    }
    private static ItemStack type(int metadata, String nbt) {
        ItemStack stack = new ItemStack(fixtureItem, 1, metadata);
        if (nbt != null) { stack.stackTagCompound = new NBTTagCompound(); stack.stackTagCompound.setString("fullIdentity", nbt); }
        return stack;
    }
    private static ItemStack quantity(ItemStack type, int count) { ItemStack stack = type.copy(); stack.stackSize = count; return stack; }
    private static long committed(AeSurvivalSource source) {
        long result = 0; for (SurvivalConstructionAdapter.Amount entry : source.committed.values()) result += entry.count(); return result;
    }
    private static int returned(Map<ItemStack, Integer> values) { int result = 0; for (int count : values.values()) result += count; return result; }
    private static void requests() {
        check("bound_cell_container".equals(AeSurvivalSource.capabilities().get("scope").getAsString()), "Explicit bounded cell scope is reported without whole-grid claims");
        for (String required : new String[] {"type", "mode", "selector", "x", "y", "z", "side", "expectedGridId"}) {
            JsonObject args = sourceArgs("finite"); args.remove(required); expect("invalid_args", () -> new AeSurvivalSource.Request(args));
        }
        JsonObject unknown = sourceArgs("finite"); unknown.addProperty("provider", "all"); expect("invalid_args", () -> new AeSurvivalSource.Request(unknown));
        JsonObject scope = sourceArgs("finite"); scope.addProperty("type", "ae_network"); expect("unsupported", () -> new AeSurvivalSource.Request(scope));
        for (String mode : new String[] {"auto", "infinite", "player"}) { JsonObject args = sourceArgs(mode); expect("invalid_args", () -> new AeSurvivalSource.Request(args)); }
        JsonObject uuid = sourceArgs("finite"); uuid.addProperty("expectedGridId", "1-1-1-1-1"); expect("invalid_args", () -> new AeSurvivalSource.Request(uuid));
        JsonObject part = sourceArgs("finite"); part.addProperty("selector", "part"); check("part".equals(new AeSurvivalSource.Request(part).selector), "Part selection is explicit");
    }
    private static JsonObject inspectionArgs() {
        JsonObject result = sourceArgs("finite"); result.remove("type"); result.remove("mode"); result.remove("expectedGridId");
        result.addProperty("expectedSessionId", "session"); result.addProperty("expectedWorld", "world"); result.addProperty("dimension", 180); return result;
    }
    private static JsonObject readGuard() {
        JsonObject result = new JsonObject(); result.addProperty("version", 1); result.addProperty("expectedSessionId", "session");
        result.addProperty("expectedWorld", "world"); result.addProperty("expectedProfile", "developer"); result.addProperty("requireBackground", true); return result;
    }
    private static void inspectionRequestsAndGuards() {
        AeSurvivalSource.InspectionRequest request = new AeSurvivalSource.InspectionRequest(inspectionArgs());
        check(request.dimension == 180 && request.energyProbeItems == 65536 && request.anchor.gridId == null, "Read request obtains actual public grid identity rather than requiring a guessed UUID");
        for (String required : new String[] {"expectedSessionId", "expectedWorld", "dimension", "x", "y", "z", "side", "selector"}) {
            JsonObject args = inspectionArgs(); args.remove(required); expect("invalid_args", () -> new AeSurvivalSource.InspectionRequest(args));
        }
        for (int probe : new int[] {0, 65537}) {
            JsonObject args = inspectionArgs(); args.addProperty("energyProbeItems", probe); expect("invalid_args", () -> new AeSurvivalSource.InspectionRequest(args));
        }
        JsonObject assumed = inspectionArgs(); assumed.addProperty("expectedGridId", "guessed"); expect("invalid_args", () -> new AeSurvivalSource.InspectionRequest(assumed));
        GuardedContext guard = GuardedContext.parse(AeSurvivalSource.INSPECT_OPERATION, readGuard()); guard.requireAeMaterialSourceRead();
        check(!guard.action && !guard.outcomeUnknown() && RequestRegistry.readOnly(AeSurvivalSource.INSPECT_OPERATION), "Source query is fixed read-only, with AFTER context and read-grade deduplication");
        check("after".equals(guard.result(new JsonObject(), new JsonObject()).context.get("phase").getAsString()), "Source inspection returns AFTER phase");
        for (String missing : new String[] {"expectedWorld", "requireBackground", "expectedProfile"}) {
            JsonObject invalid = readGuard();
            if ("expectedWorld".equals(missing)) invalid.remove(missing);
            else if ("requireBackground".equals(missing)) invalid.addProperty(missing, false);
            else invalid.addProperty(missing, "player");
            expect("invalid_request", () -> GuardedContext.parse(AeSurvivalSource.INSPECT_OPERATION, invalid).requireAeMaterialSourceRead());
        }
    }
    private static void finite() {
        ItemStack sameType = type(7, "a"), otherNbt = type(7, "b");
        Fixture fixture = new Fixture(2).add(sameType, 2, 5).add(otherNbt, 20, 20);
        AeSurvivalSource source = source(fixture, "finite"); source.beginBatch();
        check(source.takeAll(quantity(sameType, 6), false), "Exact request spans two native backing cells");
        check(fixture.amount(0, sameType) == 0 && fixture.amount(1, sameType) == 1 && fixture.amount(0, otherNbt) == 20, "Finite actual deduction preserves differing full NBT");
        check(committed(source) == 6 && source.receipt().get("finiteDecrementVerified").getAsBoolean(), "Finite proof equals committed native return");
        JsonArray ledger = source.receipt().getAsJsonArray("backingLedger");
        check(ledger.size() == 2 && ledger.get(1).getAsJsonObject().get("backingBefore").getAsLong() == 5, "Each actual cell retains independent fresh backing receipt");
        check(source.takeOne(sameType, false) && committed(source) == 7 && source.receipt().getAsJsonArray("backingLedger").size() == 2, "Repeated placements aggregate rows instead of adding one DTO per block");
        check(!source.takeOne(sameType, false) && fixture.modulations == 3, "Exhausted exact source stops without new commits");
        Fixture large = new Fixture(1).add(sameType, (long) Integer.MAX_VALUE + 25);
        AeSurvivalSource longSource = source(large, "finite"); check(longSource.takeAll(quantity(sameType, 4), false), "Actual backing quantity can exceed int");
        check(longSource.receipt().getAsJsonArray("backingLedger").get(0).getAsJsonObject().get("backingAfter").getAsLong() == (long) Integer.MAX_VALUE + 21, "Long conservation is not truncated");
        Fixture beyondJsonDouble = new Fixture(1).add(sameType, 9007199254741993L); AeSurvivalSource exactSource = source(beyondJsonDouble, "finite"); exactSource.takeOne(sameType, false);
        check("9007199254741992".equals(exactSource.receipt().getAsJsonArray("backingLedger").get(0).getAsJsonObject().get("backingAfterExact").getAsString()), "Exact decimal receipt preserves backing counts above JSON double precision");
        source.beginBatch(); check(!source.receipt().get("materialsObserved").getAsBoolean() && !source.receipt().get("finiteDecrementVerified").getAsBoolean(), "New empty batch does not claim observed finite conservation");
    }
    private static void simulationAndEnergy() {
        ItemStack first = type(1, "energy1"), second = type(2, "energy2");
        Fixture fixture = new Fixture(2).add(first, 2, 0).add(second, 5, 5); fixture.energy = 3;
        AeSurvivalSource source = source(fixture, "finite");
        check(returned(source.take(stack -> true, true, 8)) == 3, "One energy allowance bounds combined simulated types and cells");
        check(fixture.modulations == 0 && fixture.energy == 3 && committed(source) == 0 && fixture.amount(0, first) == 2, "Simulation never modulates backing or native energy");
        check(!source.takeAll(quantity(first, 4), false) && fixture.modulations == 0, "Insufficient combined native energy prevents exact commit");
        check(!source.takeAll(stack -> true, false, 4) && fixture.modulations == 0, "Predicate takeAll preflights complete amount before committing");
        check(returned(source.take(stack -> true, false, 8)) == 3 && fixture.energy == 0 && committed(source) == 3, "Partial take returns only actual energy-bounded commits");
        Fixture shortMaterials = new Fixture(2).add(first, 1, 1); AeSurvivalSource shortSource = source(shortMaterials, "finite");
        check(!shortSource.takeAll(quantity(first, 3), false) && !shortSource.takeAll(stack -> true, false, 3) && shortMaterials.modulations == 0, "Material shortage performs no exact or predicate-all commits");
        Fixture simulationMutation = new Fixture(1).add(first, 2); simulationMutation.simulationMutation = true;
        AeSurvivalSource mutationSource = source(simulationMutation, "finite"); expect("source_accounting_unverified", () -> mutationSource.takeOne(first, true));
        check(mutationSource.receipt().get("outcomeUnknown").getAsBoolean() && simulationMutation.modulations == 0, "Unexpected simulation mutation stops before real extraction");
        expect("source_stopped", () -> mutationSource.takeOne(first, false));
    }
    private static void creative() {
        ItemStack item = type(9, "infinite"); long supply = (1L << 52) - 1;
        Fixture fixture = new Fixture(1).add(item, supply); fixture.creative[0] = true;
        AeSurvivalSource source = source(fixture, "creative_infinite"); check(source.takeAll(quantity(item, 7), false), "Creative identified backing uses actual powered-return path");
        JsonObject receipt = source.receipt(); check(fixture.amount(0, item) == supply && fixture.energy == Integer.MAX_VALUE - 7, "Native creative supply remains unchanged while extraction energy is used");
        check(receipt.get("supplyDidNotDecrease").getAsBoolean() && !receipt.get("finiteDecrementVerified").getAsBoolean() && committed(source) == 7, "Infinite receipt never claims finite conservation");
        check(!receipt.get("wholeGridProviderScopeProven").getAsBoolean() && !receipt.get("automaticReplayAllowed").getAsBoolean(), "Explicit scope and no replay remain truthful");
        Fixture mixed = new Fixture(2).add(item, 5, supply); mixed.creative[1] = true;
        expect("unsupported", () -> source(mixed, "finite")); expect("unsupported", () -> source(mixed, "creative_infinite"));
        expect("unsupported", () -> source(new Fixture(1).add(item, 5), "creative_infinite"));
    }
    private static void failures() {
        ItemStack item = type(3, "failure");
        for (String failure : new String[] {"permission_denied", "source_offline", "stale_source", "unsupported"}) {
            Fixture fixture = new Fixture(1).add(item, 10); AeSurvivalSource source = source(fixture, "finite");
            check(source.takeOne(item, false), "Known extraction precedes directed binding failure"); fixture.validationFailure = failure;
            expect(failure, () -> source.takeOne(item, false)); check(committed(source) == 1 && fixture.modulations == 1 && !source.receipt().get("accountingComplete").getAsBoolean(), "Context failure retains known quantities and invalidates accounting");
            expect("source_stopped", () -> source.takeOne(item, false));
        }
        Fixture changed = new Fixture(1).add(item, 10); AeSurvivalSource changedSource = source(changed, "finite"); changedSource.takeOne(item, false); changed.counts.get(0)[0]++;
        expect("source_changed", () -> changedSource.takeOne(item, false)); check(changed.modulations == 1, "Changed backing between extractions is rejected before second commit");
        for (String fault : new String[] {"partial", "wrong_nbt", "extra_deduction", "throw_after_submission"}) {
            Fixture fixture = new Fixture(1).add(item, 10); AeSurvivalSource source = source(fixture, "finite"); source.takeOne(item, false);
            fixture.fault = fault; String code = "partial".equals(fault) ? "native_partial_extraction" : "throw_after_submission".equals(fault) ? "native_source_failed" : "source_accounting_unverified";
            expect(code, () -> source.takeAll(quantity(item, 3), false)); JsonObject receipt = source.receipt();
            check(fixture.modulations == 2 && receipt.has("failureObservation") && !receipt.get("accountingComplete").getAsBoolean() && receipt.get("outcomeUnknown").getAsBoolean(), "Submitted uncertain extraction retains observation and stops: " + fault);
            check(committed(source) == ("partial".equals(fault) ? 3 : "extra_deduction".equals(fault) ? 4 : 1), "Only known actual matching returns enter committed ledger: " + fault);
            expect("source_stopped", () -> source.takeOne(item, false)); check(fixture.modulations == 2, "Failed source never replays native mutation");
        }
        for (long badCount : new long[] {-1, Long.MAX_VALUE}) {
            Fixture fixture = new Fixture(1).add(item, badCount); AeSurvivalSource source = source(fixture, "finite");
            expect("source_accounting_unverified", () -> source.takeOne(item, false)); check(fixture.modulations == 0 && !source.receipt().get("accountingComplete").getAsBoolean(), "Negative/saturated quantity cannot claim a complete proof");
        }
        Fixture badEnergy = new Fixture(1).add(item, 5); badEnergy.invalidEnergy = true;
        AeSurvivalSource energySource = source(badEnergy, "finite"); expect("source_accounting_unverified", () -> energySource.takeOne(item, false)); check(badEnergy.modulations == 0, "Invalid native energy limit is rejected before mutation");
        Fixture callback = new Fixture(1).add(item, 5); AeSurvivalSource callbackSource = source(callback, "finite"); callbackSource.takeOne(item, false); callbackSource.callbackFailed();
        check(committed(callbackSource) == 1 && callbackSource.receipt().get("outcomeUnknown").getAsBoolean(), "World callback failure retains known source receipt without pretending full completion");
        expect("source_stopped", callbackSource::beginBatch);
    }
    private static void bounds() {
        expect("unsupported", () -> source(new Fixture(0), "finite")); expect("unsupported", () -> source(new Fixture(65), "finite"));
        Fixture candidates = new Fixture(1); for (int i = 0; i <= AeSurvivalSource.MAX_TYPES; i++) candidates.add(type(i, null), 1);
        AeSurvivalSource large = source(candidates, "finite"); expect("result_too_large", () -> large.take(stack -> true, true, 1)); check(candidates.modulations == 0, "Predicate candidates are refused rather than silently truncated");
        Fixture rows = new Fixture(1); for (int i = 0; i < 300; i++) rows.add(type(i, "row" + i), 1);
        AeSurvivalSource ledger = source(rows, "finite"); expect("result_too_large", () -> ledger.take(stack -> true, false, 300));
        check(rows.modulations > 0 && rows.modulations < 300 && committed(ledger) == rows.modulations && ledger.receipt().getAsJsonArray("backingLedger").size() == rows.modulations,
                "Bounded aggregate ledger stops before overflow, preserving every known committed row");
        Fixture bytes = new Fixture(1).add(type(1, new String(new char[50000]).replace('\0', 'x')), 1);
        AeSurvivalSource byteSource = source(bytes, "finite"); expect("result_too_large", () -> byteSource.takeOne(bytes.types.get(0), false)); check(bytes.modulations == 0, "Full NBT ledger byte budget is checked before extraction");
    }
    private static void playerAccounting() {
        ItemStack item = type(11, "player"); Fixture fixture = new Fixture(1).add(item, 5); AeSurvivalSource source = source(fixture, "finite"); source.takeOne(item, false);
        InventoryBasic inventory = new InventoryBasic("complete player fixture", false, 41); inventory.setInventorySlotContents(2, quantity(item, 2)); inventory.setInventorySlotContents(40, type(12, "last"));
        SurvivalConstructionAdapter.Inventory before = new SurvivalConstructionAdapter.Inventory(inventory, 2, null);
        JsonObject result = SurvivalConstructionAdapter.aeAccounting(before, new SurvivalConstructionAdapter.Inventory(inventory, 2, null), source, 1);
        check(result.get("playerInventoryUnchanged").getAsBoolean() && result.get("accountingComplete").getAsBoolean(), "AE backing extraction proves unchanged complete actual player inventory");
        inventory.getStackInSlot(40).stackTagCompound.setString("fullIdentity", "changed");
        JsonObject changed = SurvivalConstructionAdapter.aeAccounting(before, new SurvivalConstructionAdapter.Inventory(inventory, 2, null), source, 1);
        check(!changed.get("accountingComplete").getAsBoolean() && !changed.get("finiteDecrementVerified").getAsBoolean(), "NBT-only change in an unrelated actual player slot stops AE batch proof");
        Fixture empty = new Fixture(1).add(item, 5); AeSurvivalSource emptySource = source(empty, "finite");
        check(!SurvivalConstructionAdapter.aeAccounting(before, before, emptySource, 1).get("accountingComplete").getAsBoolean(), "Positive callback progress without observed source commits is not an external accounting proof");
        Fixture done = new Fixture(1).add(item, 5); AeSurvivalSource doneSource = source(done, "finite");
        JsonObject noMaterial = SurvivalConstructionAdapter.aeAccounting(before, before, doneSource, -1);
        check(noMaterial.get("accountingComplete").getAsBoolean() && !noMaterial.get("finiteDecrementVerified").getAsBoolean(), "Native done with no takes remains a no-material observation, separate from formation and finite proof");
        Fixture afterFailure = new Fixture(1).add(item, 5); AeSurvivalSource afterFailureSource = source(afterFailure, "finite");
        afterFailureSource.takeOne(item, false); afterFailure.validationFailure = "stale_source";
        JsonObject failed = SurvivalConstructionAdapter.aeAccounting(before, before, afterFailureSource, 1);
        check(failed.get("playerInventoryUnchanged").getAsBoolean() && !failed.get("accountingComplete").getAsBoolean()
                && failed.get("outcomeUnknown").getAsBoolean() && !failed.get("finiteDecrementVerified").getAsBoolean()
                && "stale_source".equals(failed.get("batchEndObservationError").getAsString()),
                "Failed source AFTER observation preserves known unchanged player inventory and marks submitted callback outcome uncertain");
    }
    private static final class Fixture implements AeSurvivalSource.Backing {
        final boolean[] creative;
        final List<ItemStack> types = new ArrayList<ItemStack>();
        final List<long[]> counts = new ArrayList<long[]>();
        int energy = Integer.MAX_VALUE, modulations;
        String validationFailure, fault;
        boolean simulationMutation, invalidEnergy;
        Fixture(int cells) { creative = new boolean[cells]; }
        Fixture add(ItemStack type, long... amounts) {
            if (amounts.length != creative.length) throw new AssertionError("Each actual fixture cell needs its own amount");
            types.add(type.copy()); counts.add(amounts.clone()); return this;
        }
        private int index(ItemStack type) { for (int i = 0; i < types.size(); i++) if (AeSurvivalSource.same(types.get(i), type)) return i; return -1; }
        long amount(int cell, ItemStack type) { int index = index(type); return index < 0 ? 0 : counts.get(index)[cell]; }
        @Override public void validate() { if (validationFailure != null) throw new BridgeException(validationFailure, "Directed backing validation refusal"); }
        @Override public int cells() { return creative.length; }
        @Override public boolean creative(int cell) { return creative[cell]; }
        @Override public List<ItemStack> candidates() { return types; }
        @Override public long count(int cell, ItemStack type) { return amount(cell, type); }
        @Override public int energyLimit(int count) { return invalidEnergy ? count + 1 : Math.min(count, energy); }
        @Override public AeSurvivalSource.Extraction extract(int cell, ItemStack type, int count, boolean simulate) {
            int index = index(type); long found = index < 0 ? 0 : Math.min(count, Math.min(energy, counts.get(index)[cell]));
            if (simulate) { if (simulationMutation && found > 0) counts.get(index)[cell]--; return found == 0 ? null : new AeSurvivalSource.Extraction(type, found); }
            modulations++;
            if ("partial".equals(fault) && found > 1) found--;
            if (!creative[cell] && index >= 0) counts.get(index)[cell] -= found + ("extra_deduction".equals(fault) ? 1 : 0);
            energy -= (int) found;
            if ("throw_after_submission".equals(fault)) throw new BridgeException("native_source_failed", "Directed native throw after actual submit");
            ItemStack returnedType = type.copy(); if ("wrong_nbt".equals(fault)) returnedType.stackTagCompound.setString("fullIdentity", "unexpected");
            return found == 0 ? null : new AeSurvivalSource.Extraction(returnedType, found);
        }
        @Override public JsonObject describe() { JsonObject result = new JsonObject(); result.addProperty("binding", "directed_fixture_only"); return result; }
    }
    private static void expect(String code, Runnable operation) {
        try { operation.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException actual) { check(code.equals(actual.code), "Expected directed failure " + code + ", got " + actual.code); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
