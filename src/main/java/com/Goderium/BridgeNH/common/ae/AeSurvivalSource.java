package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.common.world.WorldScan;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.IItemSource;
import cpw.mods.fml.common.Loader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Explicit real cell-container source. Calls only public APIs, through optional AE class loading. */
public final class AeSurvivalSource implements IItemSource {
    static final int MAX_CELLS = 64, MAX_TYPES = 4096, MAX_LEDGER_TYPES = 256, LEDGER_IDENTITY_BYTES = 128 * 1024;
    static final String SCOPE = "bound_cell_container";
    public static final String INSPECT_OPERATION = "ae.material_source";

    public static final class Request {
        @com.google.common.annotations.VisibleForTesting
        public final String mode, gridId, selector;
        final int x, y, z, side;
        Request(int x, int y, int z, int side, String selector) {
            this.x = x; this.y = y; this.z = z; this.side = side; this.selector = selector; mode = "finite"; gridId = null;
        }
        public Request(JsonObject args) {
            Set<String> keys = new HashSet<String>(Arrays.asList("type", "mode", "x", "y", "z", "side", "selector", "expectedGridId"));
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!keys.contains(entry.getKey()))
                throw new BridgeException("invalid_args", "Unexpected materialSource argument: " + entry.getKey());
            if (!"ae_cells".equals(JsonData.string(args, "type"))) throw new BridgeException("unsupported", "Only explicit ae_cells materialSource is supported; default omission uses the real player source.");
            mode = JsonData.string(args, "mode");
            if (!"finite".equals(mode) && !"creative_infinite".equals(mode)) throw new BridgeException("invalid_args", "materialSource.mode must be finite or creative_infinite.");
            selector = JsonData.string(args, "selector");
            if (!"tile".equals(selector) && !"part".equals(selector)) throw new BridgeException("invalid_args", "materialSource.selector must explicitly select tile or part.");
            x = JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            y = JsonData.integer(args, "y", 0, 255); z = JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX);
            side = JsonData.integer(args, "side", 0, 5); gridId = JsonData.string(args, "expectedGridId");
            try { if (!UUID.fromString(gridId).toString().equals(gridId)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException error) { throw new BridgeException("invalid_args", "materialSource.expectedGridId must be the exact canonical public grid UUID."); }
        }
    }
    public static final class InspectionRequest {
        public final String session, world;
        public final int dimension, energyProbeItems;
        final Request anchor;
        public InspectionRequest(JsonObject args) {
            Set<String> keys = new HashSet<String>(Arrays.asList("expectedSessionId", "expectedWorld", "dimension", "x", "y", "z", "side", "selector", "energyProbeItems"));
            for (Map.Entry<String, JsonElement> entry : args.entrySet()) if (!keys.contains(entry.getKey()))
                throw new BridgeException("invalid_args", "Unexpected AE source inspection argument: " + entry.getKey());
            session = JsonData.string(args, "expectedSessionId"); world = JsonData.string(args, "expectedWorld");
            if (session.isEmpty() || world.isEmpty() || session.length() > 128 || world.length() > 128)
                throw new BridgeException("invalid_args", "Inspection session/world identities require 1..128 characters.");
            dimension = JsonData.integer(args, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            String selector = JsonData.string(args, "selector");
            if (!"tile".equals(selector) && !"part".equals(selector)) throw new BridgeException("invalid_args", "selector must explicitly select tile or part.");
            anchor = new Request(JsonData.integer(args, "x", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), JsonData.integer(args, "y", 0, 255),
                    JsonData.integer(args, "z", WorldScan.WORLD_MIN, WorldScan.WORLD_MAX), JsonData.integer(args, "side", 0, 5), selector);
            energyProbeItems = args.has("energyProbeItems") ? JsonData.integer(args, "energyProbeItems", 1, SurvivalConstructionAdapter.MAX_EXPLICIT_BUDGET) : SurvivalConstructionAdapter.MAX_EXPLICIT_BUDGET;
        }
    }
    public static JsonObject inspect(EntityPlayerMP player, InspectionRequest request) {
        JsonObject result = capabilities(); result.addProperty("source", "integrated_server"); result.addProperty("operation", INSPECT_OPERATION);
        result.addProperty("sessionId", request.session); result.addProperty("dimension", request.dimension);
        result.addProperty("x", request.anchor.x); result.addProperty("y", request.anchor.y); result.addProperty("z", request.anchor.z);
        result.addProperty("side", request.anchor.side); result.addProperty("selector", request.anchor.selector);
        result.addProperty("generationPerformed", false); result.addProperty("materialsExtracted", false); result.addProperty("accountingVerified", false);
        result.addProperty("supported", false); result.addProperty("sourceMode", "unknown");
        result.addProperty("bindingVerified", false);
        result.addProperty("wholeGridProviderScopeProven", false); result.addProperty("energyProbeItems", request.energyProbeItems);
        result.addProperty("sourceScope", SCOPE);
        if (!Loader.isModLoaded("appliedenergistics2")) { reason(result, "unsupported", "Applied Energistics 2 is not loaded."); return result; }
        try {
            NativeBacking backing = new NativeBacking(player, request.anchor, true);
            JsonObject binding = backing.describe(); for (Map.Entry<String, JsonElement> entry : binding.entrySet()) result.add(entry.getKey(), entry.getValue());
            boolean nodeActive = backing.active(backing.node), actionableActive = backing.active(backing.actionableNode), powered = backing.powered(), permitted = backing.permitted();
            result.addProperty("nodeActive", nodeActive); result.addProperty("actionableNodeActive", actionableActive);
            result.addProperty("networkPowered", powered); result.addProperty("extractPermission", permitted);
            result.addProperty("nativeExtractPermissionChecked", true); result.addProperty("bindingValidatedAtExtraction", false);
            result.addProperty("nativeExtractionCalls", 0); result.addProperty("worldTick", player.worldObj.getTotalWorldTime());
            if (!permitted) reason(result, "permission_denied", "Actual actor lacks EXTRACT permission; no cell contents were inspected.");
            else if (!nodeActive || !actionableActive || !powered) reason(result, "source_offline", "Actual source node/grid is offline, booting, unpowered or lacks channels.");
            else if (backing.discoveryFailure != null) reason(result, backing.discoveryFailure.code, backing.discoveryFailure.getMessage());
            else {
                int creative = 0; for (Cell cell : backing.cells) if (cell.creative) creative++;
                String mode = creative == 0 ? "finite" : creative == backing.cells.size() ? "creative_infinite" : "mixed";
                result.addProperty("sourceMode", mode); result.addProperty("cellCount", backing.cells.size());
                result.addProperty("nativeEnergyItemAllowance", backing.energyLimit(request.energyProbeItems));
                if ("mixed".equals(mode)) reason(result, "unsupported", "Mixed finite/creative cells are not combined into a source proof.");
                else {
                    JsonObject pinned = new JsonObject(); pinned.addProperty("type", "ae_cells"); pinned.addProperty("mode", mode);
                    pinned.addProperty("x", request.anchor.x); pinned.addProperty("y", request.anchor.y); pinned.addProperty("z", request.anchor.z);
                    pinned.addProperty("side", request.anchor.side); pinned.addProperty("selector", request.anchor.selector); pinned.add("expectedGridId", binding.get("gridId"));
                    result.add("materialSource", pinned); result.addProperty("supported", true);
                    result.addProperty("nextOperation", SurvivalConstructionAdapter.OPERATION);
                    result.addProperty("proofRequiredAtCommit", "Fresh exact backing before/after and actual native return; unchanged full player inventory. This read does not prove future material availability or formation.");
                }
            }
            backing.requireIdentity(); result.addProperty("bindingVerified", true); return result;
        } catch (BridgeException error) { reason(result, error.code, error.getMessage()); return result; }
        catch (RuntimeException | LinkageError error) { reason(result, "inspection_failed", "Public AE binding inspection failed: " + error.getClass().getSimpleName()); return result; }
    }
    private static void reason(JsonObject result, String code, String message) {
        result.addProperty("supported", false); result.remove("materialSource"); JsonObject reason = new JsonObject();
        reason.addProperty("code", code); reason.addProperty("message", JsonData.bounded(message, 512)); result.add("reason", reason);
    }

    public static JsonObject capabilities() {
        JsonObject result = new JsonObject(); result.addProperty("type", "ae_cells"); result.addProperty("scope", SCOPE);
        result.addProperty("defaultSource", "actual_player_fromPlayer"); result.addProperty("optionalDependency", "appliedenergistics2");
        result.addProperty("binding", "Same actual server actor/world; explicit x,y,z,side,selector and expectedGridId; active public ICellContainer and IActionHost required.");
        result.addProperty("finite", "Fresh full-type backing counts must deduct exactly the actual native powered extraction; player inventory must remain unchanged.");
        result.addProperty("creative_infinite", "Only identified native creative cells; real security, handler filters and powered extraction; fresh backing supply must remain unchanged. This is not finite conservation.");
        result.addProperty("unsupported", "No fallback for an unloaded/offline/unknown/non-cell anchor, unknown backing inventory, mixed finite/creative cells or external network wrappers.");
        result.addProperty("interactionMeaning", "Explicit server material source for the real public survival blueprint callback; separate from normal GUI/remote interaction, without moving or faking the actor.");
        result.addProperty("automaticReplayAllowed", false); result.addProperty("partialFailure", "After native extraction starts, retain known receipts and stop; no automatic replay or rollback.");
        result.addProperty("maxCells", MAX_CELLS); result.addProperty("maxPredicateTypes", MAX_TYPES);
        result.addProperty("bindingInspectionOperation", INSPECT_OPERATION); return result;
    }

    interface Backing {
        void validate();
        int cells();
        boolean creative(int cell);
        List<ItemStack> candidates();
        long count(int cell, ItemStack type);
        int energyLimit(int count);
        Extraction extract(int cell, ItemStack type, int count, boolean simulate);
        JsonObject describe();
    }
    static final class Extraction {
        final ItemStack type;
        final long amount;
        Extraction(ItemStack type, long amount) { this.type = type == null ? null : copyType(type); this.amount = amount; }
    }
    static final class Planned {
        final int cell, count;
        final ItemStack type;
        Planned(int cell, ItemStack type, int count) { this.cell = cell; this.type = copyType(type); this.count = count; }
    }
    static final class Row {
        final int cell;
        final ItemStack type;
        final long before;
        long after, extracted;
        int extractions;
        Row(int cell, ItemStack type, long before) { this.cell = cell; this.type = copyType(type); this.before = before; this.after = before; }
    }

    final Request request;
    final Backing backing;
    public final Map<String, SurvivalConstructionAdapter.Amount> committed = new LinkedHashMap<String, SurvivalConstructionAdapter.Amount>();
    private final List<Row> rows = new ArrayList<Row>();
    private List<ItemStack> predicateTypes;
    private boolean failed, uncertain;
    private int nativeCalls, ledgerIdentityBytes;
    private JsonObject failureObservation;

    AeSurvivalSource(Request request, Backing backing) {
        this.request = request; this.backing = backing; validate();
        if (backing.cells() < 1 || backing.cells() > MAX_CELLS) throw new BridgeException("unsupported", "The actual selected source must expose 1..64 bounded native cells.");
        for (int cell = 0; cell < backing.cells(); cell++) if (backing.creative(cell) != "creative_infinite".equals(request.mode))
            throw new BridgeException("unsupported", "Selected cells do not match the explicit finite/creative_infinite mode; mixed backing is not silently combined.");
    }
    public static AeSurvivalSource bind(EntityPlayerMP player, Request request) {
        if (!Loader.isModLoaded("appliedenergistics2")) throw new BridgeException("unsupported", "Applied Energistics 2 is not loaded.");
        return new AeSurvivalSource(request, new NativeBacking(player, request));
    }
    void validate() {
        if (failed) throw new BridgeException("source_stopped", "This source previously failed or partially committed; it cannot be replayed.");
        try { backing.validate(); }
        catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    public void requireActor(EntityPlayerMP player) {
        if (!(backing instanceof NativeBacking) || ((NativeBacking) backing).actor != player)
            stop("stale_source", "The actual authoritative source actor changed, including respawn.", false);
        validate();
    }
    public void beginBatch() { validate(); committed.clear(); rows.clear(); predicateTypes = null; nativeCalls = ledgerIdentityBytes = 0; failureObservation = null; uncertain = false; }
    public void callbackFailed() { failed = true; uncertain = true; }
    public void verifyBatchEnd() {
        validate();
        try {
            for (Row row : rows) if (checkedCount(backing.count(row.cell, row.type)) != row.after)
                stop("source_changed", "Actual backing changed after the last observed extraction in this native callback; no later batch may start.", true);
        } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    public JsonObject receipt() {
        JsonObject result = backing.describe(); result.addProperty("sourceType", "ae_cells"); result.addProperty("sourceMode", request.mode);
        result.addProperty("sourceScope", SCOPE); result.addProperty("completeBackingIdentity", !failed);
        result.addProperty("externalSourceScopeProven", !failed); result.addProperty("wholeGridProviderScopeProven", false);
        result.addProperty("nativeExtractionCalls", nativeCalls); result.addProperty("accountingComplete", !failed && !uncertain);
        result.addProperty("bindingValidatedAtExtraction", nativeCalls > 0);
        result.addProperty("materialsObserved", !committed.isEmpty());
        result.addProperty("finiteDecrementVerified", "finite".equals(request.mode) && !committed.isEmpty() && !failed && !uncertain);
        result.addProperty("supplyDidNotDecrease", "creative_infinite".equals(request.mode) && !committed.isEmpty() && !failed && !uncertain);
        result.addProperty("automaticReplayAllowed", false); result.addProperty("outcomeUnknown", uncertain);
        result.addProperty("proofMeaning", "Per observed extraction from the explicitly bound real cells; no whole-network/provider conservation claim.");
        result.add("committedItems", SurvivalConstructionAdapter.amounts(committed)); JsonArray ledger = new JsonArray();
        for (Row row : rows) {
            JsonObject value = new JsonObject(); value.addProperty("cellIndex", row.cell); value.add("item", SurvivalConstructionAdapter.item(row.type));
            value.addProperty("backingBefore", row.before); value.addProperty("backingAfter", row.after); value.addProperty("actualExtracted", row.extracted);
            value.addProperty("backingBeforeExact", Long.toString(row.before)); value.addProperty("backingAfterExact", Long.toString(row.after));
            value.addProperty("verifiedExtractions", row.extractions); value.addProperty("quantityType", "long"); ledger.add(value);
        }
        result.add("backingLedger", ledger); if (failureObservation != null) result.add("failureObservation", failureObservation); return result;
    }
    @Override public Map<ItemStack, Integer> take(Predicate<ItemStack> predicate, boolean simulate, int count) {
        if (predicate == null || count < 1) throw new BridgeException("invalid_args", "A real source request needs a predicate and positive count.");
        validate(); Map<ItemStack, Integer> result = new LinkedHashMap<ItemStack, Integer>(); int remaining = energyLimit(count);
        for (ItemStack type : candidates()) {
            if (remaining == 0) break;
            if (!predicate.test(type.copy())) continue;
            List<Planned> plan = plan(type, remaining); int found = amount(plan);
            if (found > 0) { if (!simulate) commit(plan); result.put(copyType(type), found); remaining -= found; }
        }
        return result;
    }
    @Override public boolean takeAll(Predicate<ItemStack> predicate, boolean simulate, int count) {
        if (predicate == null || count < 1) throw new BridgeException("invalid_args", "A real source request needs a predicate and positive count.");
        validate(); int remaining = energyLimit(count); if (remaining != count) return false;
        List<Planned> combined = new ArrayList<Planned>();
        for (ItemStack type : candidates()) {
            if (remaining == 0) break;
            if (!predicate.test(type.copy())) continue;
            List<Planned> planned = plan(type, remaining); combined.addAll(planned); remaining -= amount(planned);
        }
        if (remaining != 0) return false;
        if (!simulate) commit(combined); return true;
    }
    @Override public boolean takeOne(ItemStack stack, boolean simulate) { return takeExact(stack, 1, simulate); }
    @Override public boolean takeAll(ItemStack stack, boolean simulate) {
        if (stack == null || stack.stackSize < 1) throw new BridgeException("invalid_args", "Exact source request needs a positive actual Stack.");
        return takeExact(stack, stack.stackSize, simulate);
    }
    private boolean takeExact(ItemStack stack, int count, boolean simulate) {
        if (stack == null || count < 1) throw new BridgeException("invalid_args", "Exact source request needs an actual type and positive count.");
        validate(); List<Planned> plan = plan(stack, count);
        if (amount(plan) != count) return false;
        if (!simulate) commit(plan); return true;
    }
    private List<ItemStack> candidates() {
        try {
        if (predicateTypes == null) {
            List<ItemStack> found = backing.candidates();
            if (found == null || found.size() > MAX_TYPES) throw new BridgeException("result_too_large", "Opaque predicate source has too many actual candidate types; none were silently truncated.");
            predicateTypes = new ArrayList<ItemStack>();
            for (ItemStack type : found) { SurvivalConstructionAdapter.item(type); if (!contains(predicateTypes, type)) predicateTypes.add(copyType(type)); }
        }
        return predicateTypes;
        } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    private List<Planned> plan(ItemStack type, int count) {
        try {
        validate(); SurvivalConstructionAdapter.item(copyType(type)); List<Planned> result = new ArrayList<Planned>();
        int remaining = energyLimit(count);
        for (int cell = 0; cell < backing.cells() && remaining > 0; cell++) {
            long available = checkedCount(backing.count(cell, type));
            int wanted = (int) Math.min(available, remaining); if (wanted == 0) continue;
            Extraction simulated;
            long found;
            try {
                simulated = backing.extract(cell, type, wanted, true); found = validReturn(simulated, type, wanted);
                if (checkedCount(backing.count(cell, type)) != available)
                    stop("source_accounting_unverified", "Native simulation changed actual backing supply; no commit or replay is allowed.", true);
            } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
            if (found > 0) { result.add(new Planned(cell, type, (int) found)); remaining -= (int) found; }
        }
        return result;
        } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    private int energyLimit(int count) {
        int available;
        try { available = backing.energyLimit(count); }
        catch (RuntimeException | LinkageError error) { failed = true; throw error; }
        if (available < 0 || available > count) stop("source_accounting_unverified", "Native energy preflight returned an invalid item limit.", false);
        return available;
    }
    private void commit(List<Planned> plan) {
        try {
        for (Planned step : plan) {
            validate(); long before = checkedCount(backing.count(step.cell, step.type)); Row row = row(step.cell, step.type, before);
            if (row.after != before) stop("source_changed", "Backing changed between observed extractions; no further source commit may start.", false);
            if (before < step.count) stop("source_changed", "The preflight material amount changed before the real commit.", false);
            nativeCalls++; Extraction actual;
            try { actual = backing.extract(step.cell, step.type, step.count, false); }
            catch (RuntimeException | LinkageError error) {
                failureObservation = observation(step, before, null, null); failed = true; uncertain = true;
                throw error instanceof BridgeException ? (BridgeException) error : new BridgeException("native_source_failed", "Real powered extraction failed after submission; do not replay it.");
            }
            long extracted;
            try { extracted = validReturn(actual, step.type, step.count); }
            catch (RuntimeException error) { failureObservation = observation(step, before, null, actual == null ? null : actual.amount); failed = true; uncertain = true; throw error; }
            if (extracted > 0) {
                try { SurvivalConstructionAdapter.add(committed, actual.type, extracted); }
                catch (RuntimeException error) { failureObservation = observation(step, before, null, extracted); failed = true; uncertain = true; throw error; }
            }
            long after;
            try { backing.validate(); after = checkedCount(backing.count(step.cell, step.type)); }
            catch (RuntimeException | LinkageError error) { failureObservation = observation(step, before, null, extracted); failed = true; uncertain = true; throw error; }
            failureObservation = observation(step, before, after, extracted);
            if (extracted != step.count) stop("native_partial_extraction", "Real extraction differed from the simulated request; known committed quantities are retained, with no replay or rollback.", true);
            boolean verified = "finite".equals(request.mode) ? before - after == extracted : after == before;
            if (!verified) stop("source_accounting_unverified", "Fresh actual cell backing does not match the explicit finite or creative-infinite mode; no later commit may start.", true);
            row.after = after; row.extracted = Math.addExact(row.extracted, extracted); row.extractions++; failureObservation = null;
        }
        } catch (RuntimeException | LinkageError error) { failed = true; throw error; }
    }
    private Row row(int cell, ItemStack type, long before) {
        for (Row row : rows) if (row.cell == cell && same(row.type, type)) return row;
        if (rows.size() >= MAX_LEDGER_TYPES) stop("result_too_large", "The bounded complete backing ledger is full before another extraction; no detail was truncated.", false);
        int identityBytes = SurvivalConstructionAdapter.item(type).toString().getBytes(StandardCharsets.UTF_8).length;
        if ((long) ledgerIdentityBytes + 3L * identityBytes + 512 > LEDGER_IDENTITY_BYTES)
            stop("result_too_large", "Complete ledger and committed full-NBT identities exceed their byte budget before another extraction; none were truncated.", false);
        ledgerIdentityBytes += 3 * identityBytes + 512;
        Row row = new Row(cell, type, before); rows.add(row); return row;
    }
    private void stop(String code, String message, boolean unknown) { failed = true; uncertain |= unknown; throw new BridgeException(code, message); }
    private JsonObject observation(Planned step, long before, Long after, Long extracted) {
        JsonObject result = new JsonObject(); result.addProperty("cellIndex", step.cell); result.add("item", SurvivalConstructionAdapter.item(step.type));
        result.addProperty("backingBefore", before); result.addProperty("requested", step.count);
        result.addProperty("backingBeforeExact", Long.toString(before));
        if (after != null) { result.addProperty("backingAfter", after); result.addProperty("backingAfterExact", after.toString()); }
        if (extracted != null) result.addProperty("actualExtracted", extracted); return result;
    }
    static long checkedCount(long count) {
        if (count < 0 || count == Long.MAX_VALUE) throw new BridgeException("source_accounting_unverified", "Negative or saturated backing quantities cannot prove exact conservation.");
        return count;
    }
    static long validReturn(Extraction extracted, ItemStack wanted, int count) {
        if (extracted == null) return 0;
        if (extracted.amount < 1 || extracted.amount > count || extracted.type == null || !same(wanted, extracted.type))
            throw new BridgeException("source_accounting_unverified", "Native extraction returned an unexpected full item/NBT identity or quantity.");
        return extracted.amount;
    }
    static boolean same(ItemStack left, ItemStack right) { return left != null && right != null && ItemStack.areItemStacksEqual(copyType(left), copyType(right)); }
    static ItemStack copyType(ItemStack stack) { if (stack == null) throw new BridgeException("source_accounting_unverified", "Actual source type is null."); ItemStack copy = stack.copy(); copy.stackSize = 1; return copy; }
    private static boolean contains(List<ItemStack> types, ItemStack type) { for (ItemStack existing : types) if (same(existing, type)) return true; return false; }
    private static int amount(List<Planned> plan) { int count = 0; for (Planned step : plan) count = Math.addExact(count, step.count); return count; }

    /** No fields or private methods are read. Preserve outer handler restrictions for every native extraction. */
    private static final class NativeBacking implements Backing {
        final EntityPlayerMP actor;
        final World world;
        final int dimension;
        final Request request;
        final TileEntity tile;
        final PublicApi api;
        final Object host, node, actionableNode, grid, security, energy, helper, actionSource;
        final List<Cell> cells;
        final BridgeException discoveryFailure;
        NativeBacking(EntityPlayerMP player, Request request) { this(player, request, false); }
        NativeBacking(EntityPlayerMP player, Request request, boolean inspection) {
            actor = player; world = player.worldObj; dimension = player.dimension; this.request = request;
            if (!world.blockExists(request.x, request.y, request.z)) throw new BridgeException("chunk_unloaded", "The explicit AE anchor is not loaded; no world generation was requested.");
            tile = world.getTileEntity(request.x, request.y, request.z);
            if (tile == null) throw new BridgeException("unsupported", "The explicit anchor has no real AE host tile.");
            api = new PublicApi(tile.getClass().getClassLoader());
            host = "part".equals(request.selector) ? api.call("appeng.api.parts.IPartHost", "getPart", tile, ForgeDirection.getOrientation(request.side)) : tile;
            if (!api.is(host, "appeng.api.storage.ICellContainer") || !api.is(host, "appeng.api.networking.security.IActionHost"))
                throw new BridgeException("unsupported", "Selected anchor does not expose public ICellContainer/IActionHost; terminal/global/provider fallback is not used.");
            node = "part".equals(request.selector) ? api.call("appeng.api.parts.IPart", "getGridNode", host)
                    : api.call("appeng.api.networking.IGridHost", "getGridNode", host, ForgeDirection.getOrientation(request.side));
            actionableNode = api.call("appeng.api.networking.security.IActionHost", "getActionableNode", host);
            if (node == null || actionableNode == null) throw new BridgeException("unsupported", "Explicit node/actionable node is unavailable.");
            grid = api.call("appeng.api.networking.IGridNode", "getGrid", node);
            if (grid == null) throw new BridgeException("source_offline", "Actual selected node has no current public grid.");
            security = api.call("appeng.api.networking.IGrid", "getCache", grid, api.type("appeng.api.networking.security.ISecurityGrid"));
            energy = api.call("appeng.api.networking.IGrid", "getCache", grid, api.type("appeng.api.networking.energy.IEnergyGrid"));
            helper = api.call("appeng.api.IAppEngApi", "storage", api.call("appeng.api.AEApi", "instance", null));
            actionSource = api.create("appeng.api.networking.security.PlayerSource", player, host);
            requireIdentity();
            if (inspection) {
                List<Cell> observed = new ArrayList<Cell>(); BridgeException failure = null;
                if (permitted() && active(node) && active(actionableNode) && powered()) {
                    try { observed = resolveCells(); }
                    catch (BridgeException error) { failure = error; }
                }
                cells = observed; discoveryFailure = failure;
            } else { requireContext(); cells = resolveCells(); discoveryFailure = null; validate(); }
        }
        private void requireIdentity() {
            if (actor.isDead || actor.worldObj != world || actor.dimension != dimension || world.isRemote
                    || !world.blockExists(request.x, request.y, request.z) || world.getTileEntity(request.x, request.y, request.z) != tile)
                throw new BridgeException("stale_source", "The actual living source actor/world/loaded anchor changed.");
            Object selected = "part".equals(request.selector) ? api.call("appeng.api.parts.IPartHost", "getPart", tile, ForgeDirection.getOrientation(request.side)) : tile;
            Object currentNode = "part".equals(request.selector) ? api.call("appeng.api.parts.IPart", "getGridNode", selected)
                    : api.call("appeng.api.networking.IGridHost", "getGridNode", selected, ForgeDirection.getOrientation(request.side));
            if (selected != host || currentNode != node || api.call("appeng.api.networking.security.IActionHost", "getActionableNode", host) != actionableNode
                    || api.call("appeng.api.networking.IGridNode", "getWorld", node) != world
                    || api.call("appeng.api.networking.IGridNode", "getWorld", actionableNode) != world
                    || api.call("appeng.api.networking.IGridNode", "getGrid", node) != grid
                    || api.call("appeng.api.networking.IGridNode", "getGrid", actionableNode) != grid
                    || request.gridId != null && !request.gridId.equals(String.valueOf(api.call("appeng.api.networking.IGrid", "getId", grid))))
                throw new BridgeException("stale_source", "The explicitly bound host/node/public grid UUID changed; no other subnet was selected.");
        }
        private boolean active(Object node) { return Boolean.TRUE.equals(api.call("appeng.api.networking.IGridNode", "isActive", node)); }
        private boolean powered() { return Boolean.TRUE.equals(api.call("appeng.api.networking.energy.IEnergyGrid", "isNetworkPowered", energy)); }
        private boolean permitted() {
            return Boolean.TRUE.equals(api.call("appeng.api.networking.security.ISecurityGrid", "hasPermission", security, actor,
                    api.enumeration("appeng.api.config.SecurityPermissions", "EXTRACT")));
        }
        private void requireContext() {
            requireIdentity();
            if (!active(node) || !active(actionableNode) || !powered())
                throw new BridgeException("source_offline", "The actual source host/node/grid is offline, booting, unpowered or lacks required channels.");
            if (!permitted())
                throw new BridgeException("permission_denied", "Actual actor lacks native AE EXTRACT permission.");
        }
        private List<Cell> resolveCells() {
            Object actual = api.call("appeng.api.storage.ICellProvider", "getCellArray", host, api.enumeration("appeng.api.storage.StorageChannel", "ITEMS"));
            if (!(actual instanceof List<?>) || ((List<?>) actual).isEmpty() || ((List<?>) actual).size() > MAX_CELLS)
                throw new BridgeException("unsupported", "Explicit native cell array is empty, unavailable or outside its bounded scope.");
            List<Cell> found = new ArrayList<Cell>(); Set<Object> identities = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            Set<Object> physicalCells = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            for (Object handler : (List<?>) actual) {
                Object current = handler; Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
                for (int depth = 0; depth < 8 && current != null && !api.is(current, "appeng.api.storage.ICellInventory"); depth++) {
                    if (!visited.add(current) || !api.is(current, "appeng.api.storage.IMEInventoryHandler")) throw new BridgeException("unsupported", "Unknown/cyclic non-cell backing wrapper.");
                    if (api.call("appeng.api.storage.IMEInventoryHandler", "getExternalNetworkInventory", current) != null)
                        throw new BridgeException("unsupported", "External network backing is outside the explicit cell-container proof.");
                    current = api.is(current, "appeng.api.storage.ICellInventoryHandler") ? api.call("appeng.api.storage.ICellInventoryHandler", "getCellInv", current)
                            : api.call("appeng.api.storage.IMEInventoryHandler", "getInternal", current);
                }
                boolean creative = current != null && current.getClass() == api.type("appeng.me.storage.CreativeCellInventory");
                if (!creative && (current == null || current.getClass() != api.type("appeng.me.storage.ItemCellInventory")))
                    throw new BridgeException("unsupported", "Public wrapper path does not prove native finite item-cell or creative-cell backing.");
                if (!identities.add(current)) throw new BridgeException("unsupported", "Duplicate backing cell aliases cannot prove independent extraction accounting.");
                Object cellStack = api.call("appeng.api.storage.ICellInventory", "getItemStack", current);
                if (!(cellStack instanceof ItemStack)) throw new BridgeException("unsupported", "Actual physical cell Stack is unavailable.");
                if (!physicalCells.add(cellStack)) throw new BridgeException("unsupported", "Duplicate physical cell Stack aliases cannot prove independent source accounting.");
                found.add(new Cell(handler, current, (ItemStack) cellStack, creative));
            }
            return found;
        }
        @Override public void validate() {
            requireContext(); List<Cell> current = resolveCells();
            if (current.size() != cells.size()) throw new BridgeException("stale_source", "The actual selected cell array changed.");
            for (int i = 0; i < cells.size(); i++) if (current.get(i).handler != cells.get(i).handler || current.get(i).inventory != cells.get(i).inventory
                    || current.get(i).stack != cells.get(i).stack || current.get(i).creative != cells.get(i).creative)
                throw new BridgeException("stale_source", "Actual cell/handler identity or source mode changed.");
        }
        @Override public int cells() { return cells.size(); }
        @Override public boolean creative(int cell) { return cells.get(cell).creative; }
        @Override public List<ItemStack> candidates() {
            validate(); List<ItemStack> result = new ArrayList<ItemStack>();
            for (Cell cell : cells) {
                Object list = api.call("appeng.api.storage.IMEInventory", "getAvailableItems", cell.inventory,
                        api.call("appeng.api.storage.IStorageHelper", "createItemList", helper), iteration());
                if (!(list instanceof Iterable<?>)) throw new BridgeException("unsupported", "Actual backing type list is unavailable.");
                for (Object stack : (Iterable<?>) list) {
                    if (result.size() >= MAX_TYPES) throw new BridgeException("result_too_large", "Complete predicate backing type discovery exceeds its bound.");
                    Extraction type = decoded(stack); if (type != null && type.amount > 0 && !contains(result, type.type)) result.add(type.type);
                }
            }
            return result;
        }
        @Override public long count(int cell, ItemStack type) {
            Object current = api.call("appeng.api.storage.IMEInventory", "getAvailableItem", cells.get(cell).inventory, nativeStack(type, 1), iteration());
            Extraction observed = decoded(current);
            if (observed == null) return 0;
            if (!same(type, observed.type)) throw new BridgeException("source_accounting_unverified", "Backing lookup returned a different actual Stack/NBT.");
            return checkedCount(observed.amount);
        }
        @Override public int energyLimit(int count) {
            validate(); double available = ((Number) api.call("appeng.api.networking.energy.IEnergySource", "extractAEPower", energy,
                    (double) count, api.enumeration("appeng.api.config.Actionable", "SIMULATE"), api.enumeration("appeng.api.config.PowerMultiplier", "CONFIG"))).doubleValue();
            if (!Double.isFinite(available) || available < 0) throw new BridgeException("source_accounting_unverified", "Native simulated available energy is invalid.");
            return (int) Math.min(count, available + 0.9);
        }
        @Override public Extraction extract(int cell, ItemStack type, int count, boolean simulate) {
            validate(); Object nativeRequest = nativeStack(type, count); Cell selected = cells.get(cell);
            Object extracted = simulate ? api.call("appeng.util.Platform", "poweredExtraction", null, energy, selected.handler, nativeRequest, actionSource,
                    api.enumeration("appeng.api.config.Actionable", "SIMULATE"))
                    : api.call("appeng.api.storage.IStorageHelper", "poweredExtraction", helper, energy, selected.handler, nativeRequest, actionSource);
            return decoded(extracted);
        }
        private Object nativeStack(ItemStack type, long count) {
            Object stack = api.call("appeng.api.storage.IStorageHelper", "createItemStack", helper, copyType(type));
            if (stack == null || ((Number) api.call("appeng.api.storage.data.IAEStack", "getAmountPerUnit", stack)).intValue() != 1)
                throw new BridgeException("unsupported", "Actual native item source does not expose the normal item-per-AE-unit contract.");
            return api.call("appeng.api.storage.data.IAEStack", "setStackSize", stack, count);
        }
        private Extraction decoded(Object stack) {
            if (stack == null) return null;
            Object type = api.call("appeng.api.storage.data.IAEItemStack", "getItemStack", stack);
            if (!(type instanceof ItemStack)) throw new BridgeException("source_accounting_unverified", "Actual native AE item template is unavailable.");
            return new Extraction((ItemStack) type, ((Number) api.call("appeng.api.storage.data.IAEStack", "getStackSize", stack)).longValue());
        }
        private int iteration() { return ((Number) api.call("appeng.util.IterationCounter", "fetchNewId", null)).intValue(); }
        @Override public JsonObject describe() {
            JsonObject result = new JsonObject(); result.addProperty("gridId", String.valueOf(api.call("appeng.api.networking.IGrid", "getId", grid))); result.addProperty("dimension", dimension);
            result.addProperty("actorUuid", actor.getUniqueID().toString()); result.addProperty("actorEntityId", actor.getEntityId());
            result.addProperty("actorBinding", "same_actual_authoritative_entity_and_world_objects"); result.addProperty("anchorClass", host.getClass().getName());
            result.addProperty("x", request.x); result.addProperty("y", request.y); result.addProperty("z", request.z); result.addProperty("side", request.side); result.addProperty("selector", request.selector);
            result.addProperty("bindingValidatedAtExtraction", true); result.addProperty("nativeExtractPermissionChecked", true);
            result.addProperty("nativeEnergyPath", "IStorageHelper.poweredExtraction on outer configured handler and actual IEnergyGrid; native SIMULATE preflight");
            result.addProperty("handlerRestrictionsPreserved", true); JsonArray values = new JsonArray();
            for (int i = 0; i < cells.size(); i++) { Cell cell = cells.get(i); JsonObject value = new JsonObject(); value.addProperty("cellIndex", i);
                value.addProperty("handlerClass", cell.handler.getClass().getName()); value.addProperty("backingClass", cell.inventory.getClass().getName());
                value.addProperty("creative", cell.creative); JsonObject physical = new JsonObject();
                physical.addProperty("registryName", String.valueOf(Item.itemRegistry.getNameForObject(cell.stack.getItem())));
                physical.addProperty("metadata", cell.stack.getMetadata()); physical.addProperty("hasTag", cell.stack.hasTagCompound());
                physical.addProperty("identityBinding", "same_actual_cell_stack_object; storage NBT not serialized");
                value.add("physicalCell", physical); values.add(value); }
            result.add("cells", values); return result;
        }
    }
    private static final class Cell {
        final Object handler, inventory;
        final ItemStack stack;
        final boolean creative;
        Cell(Object handler, Object inventory, ItemStack stack, boolean creative) { this.handler = handler; this.inventory = inventory; this.stack = stack; this.creative = creative; }
    }
    /** Fixed internal public APIs only. Cache methods per binding/classloader; never setAccessible. */
    private static final class PublicApi {
        final ClassLoader loader;
        final Map<String, Class<?>> types = new LinkedHashMap<String, Class<?>>();
        final Map<String, Method> methods = new LinkedHashMap<String, Method>();
        PublicApi(ClassLoader loader) { this.loader = loader; }
        Class<?> type(String name) {
            Class<?> existing = types.get(name); if (existing != null) return existing;
            try { Class<?> loaded = Class.forName(name, false, loader); types.put(name, loaded); return loaded; }
            catch (ClassNotFoundException | LinkageError error) { throw new BridgeException("unsupported", "Required public AE API is unavailable: " + name); }
        }
        boolean is(Object value, String name) { return value != null && type(name).isInstance(value); }
        @SuppressWarnings({"unchecked", "rawtypes"}) Object enumeration(String name, String value) { return Enum.valueOf((Class) type(name), value); }
        Object create(String name, Object... args) {
            for (Constructor<?> constructor : type(name).getConstructors()) if (matches(constructor.getParameterTypes(), args)) {
                try { return constructor.newInstance(args); }
                catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public AE action-source construction failed."); }
            }
            throw new BridgeException("unsupported", "Public actual PlayerSource constructor is unavailable.");
        }
        Object call(String owner, String name, Object target, Object... args) {
            Class<?> api = type(owner);
            if (target != null && !api.isInstance(target)) throw new BridgeException("unsupported", "Actual source lacks public capability: " + owner);
            StringBuilder key = new StringBuilder(owner).append('.').append(name);
            for (Object argument : args) key.append(':').append(argument == null ? "null" : argument.getClass().getName());
            Method selected = methods.get(key.toString());
            if (selected == null) {
                for (Method method : api.getMethods()) if (name.equals(method.getName()) && matches(method.getParameterTypes(), args)) { selected = method; break; }
                if (selected == null) throw new BridgeException("unsupported", "Fixed public AE method is unavailable: " + owner + "." + name);
                methods.put(key.toString(), selected);
            }
            try { return selected.invoke(target, args); }
            catch (InvocationTargetException error) { Throwable cause = error.getCause(); throw cause instanceof BridgeException ? (BridgeException) cause : new BridgeException("native_source_failed", "Public AE native call failed: " + name + "/" + (cause == null ? "unknown" : cause.getClass().getSimpleName())); }
            catch (ReflectiveOperationException | RuntimeException error) { throw new BridgeException("unsupported", "Public AE method could not be invoked: " + owner + "." + name); }
        }
        private static boolean matches(Class<?>[] parameters, Object[] args) {
            if (parameters.length != args.length) return false;
            for (int i = 0; i < args.length; i++) {
                Class<?> type = parameters[i]; if (type == int.class) type = Integer.class; else if (type == long.class) type = Long.class;
                else if (type == double.class) type = Double.class; else if (type == boolean.class) type = Boolean.class;
                if (args[i] == null ? parameters[i].isPrimitive() : !type.isInstance(args[i])) return false;
            }
            return true;
        }
    }
}
