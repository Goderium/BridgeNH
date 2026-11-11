package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.common.bridge.GameBridge;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GameQueue.Control;
import com.Goderium.BridgeNH.common.bridge.GameQueue.Ticket;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.bridge.RequestRegistry;
import com.Goderium.BridgeNH.common.inventory.InspectManyPlayerInventory;
import com.Goderium.BridgeNH.network.BridgeHttpServer;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Real distinct owner queues and real copied ItemStacks/NBT; native world/controller operations are mocked. */
public final class OwnedBlockReplacementSelfTest {
    private static int checks;
    private static Item fixtureItem;
    private static JsonObject evidence;
    public static int run() throws Exception {
        fixtureItem = new ItemBlock(new FixtureBlock()) { @Override public String getItemStackDisplayName(ItemStack stack) { return "Native copied stack fixture"; } };
        Item.itemRegistry.addObject(32240, "owned_replacement_fixture:item", fixtureItem);
        checks = 0; evidence = new JsonObject(); evidence.addProperty("gameOperationsMocked", true);
        requestsAndGuards(); sourcePreflight(); inventoryCopies(); epochBindings(); queuedSequence(); stopBoundaries(); registry(); http();
        evidence.addProperty("assertions", checks); evidence.add("capabilityContract", OwnedBlockReplacement.capabilities());
        String destination = System.getenv("OWNED_REPLACEMENT_SELFTEST_EVIDENCE");
        if (destination != null) Files.write(Paths.get(destination), evidence.toString().getBytes(StandardCharsets.UTF_8));
        return checks;
    }

    private static JsonObject args() {
        JsonObject value = new JsonObject(); value.addProperty("x", -44); value.addProperty("y", 2); value.addProperty("z", 125);
        value.addProperty("dimension", 0); value.addProperty("expectedWorld", "world"); value.addProperty("emptyHotbarSlot", 8); value.addProperty("hotbarSlot", 1);
        JsonObject old = new JsonObject(); old.addProperty("registryName", "minecraft:stone"); old.addProperty("metadata", 0); value.add("expectedOldIdentity", old);
        JsonObject source = new JsonObject(); source.addProperty("registryName", "gregtech:gt.blockmachines"); source.addProperty("metadata", 74);
        source.addProperty("count", 32); source.addProperty("hasTag", false); value.add("expectedSourceItem", source);
        JsonObject placed = new JsonObject(); placed.addProperty("registryName", "gregtech:gt.blockmachines"); placed.addProperty("metaTileId", 74); value.add("expectedPlacedIdentity", placed);
        value.addProperty("requireFlying", true); value.addProperty("allowMove", true);
        value.addProperty("playerInventoryByteBudget", 131072); value.addProperty("observationByteBudget", 1048576); return value;
    }
    private static JsonObject context() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("expectedSessionId", "session");
        value.addProperty("expectedProfile", "developer"); value.addProperty("expectedWorld", "world"); value.addProperty("requireBackground", true); return value;
    }
    private static JsonObject snapshot(long tick, int slot) {
        JsonObject value = new JsonObject(); value.addProperty("sessionId", "session"); value.addProperty("profile", "developer");
        value.addProperty("worldLoaded", true); value.addProperty("clientTicks", tick);
        JsonObject world = new JsonObject(); world.addProperty("worldName", "world"); world.addProperty("dimension", 0); world.addProperty("time", 6000); value.add("world", world);
        JsonObject background = new JsonObject(); background.addProperty("enabled", true); background.addProperty("helperInstalled", true); background.addProperty("pauseOnLostFocus", false); value.add("background", background);
        JsonObject player = new JsonObject(); player.addProperty("playerId", "uuid"); player.addProperty("x", -44.5); player.addProperty("feetY", 2); player.addProperty("z", 123.5);
        player.addProperty("selectedHotbarSlot", slot); player.addProperty("creative", true); player.addProperty("flying", true); player.addProperty("allowFlying", true); value.add("player", player); return value;
    }
    private static void requestsAndGuards() throws Exception {
        OwnedBlockReplacement.Request request = new OwnedBlockReplacement.Request(args());
        check(request.sourceMetadata == 74 && request.placed.metadata == -1 && request.placed.metaTileId == 74 && "surround".equals(request.pattern),
                "Item metadata 74 stays separate from optional actual world metadata and default 33-candidate surround");
        check(request.approachArgs(true).getAsJsonObject("targetIdentity").equals(OwnedBlockReplacement.airIdentity()), "Placement planning is tied to explicit actual air metadata 0");
        for (int meta : new int[] {0, 1}) {
            JsonObject actual = new JsonObject(); actual.addProperty("registryName", "gregtech:gt.blockmachines"); actual.addProperty("metadata", meta);
            JsonObject gt = new JsonObject(); gt.addProperty("metaTileId", 74); actual.add("gregtech", gt); request.placed.require(actual);
            check(true, "Separate GT metaTile identity permits its actual world block metadata " + meta);
            gt.addProperty("metaTileId", 75); expect("target_unverified", () -> request.placed.require(actual));
        }
        for (String key : new String[] {"expectedOldIdentity", "expectedSourceItem", "expectedPlacedIdentity", "playerInventoryByteBudget", "observationByteBudget", "emptyHotbarSlot", "hotbarSlot", "expectedWorld"}) {
            JsonObject bad = copy(args()); bad.remove(key); expect("invalid_args", () -> new OwnedBlockReplacement.Request(bad));
        }
        JsonObject bad = args(); bad.addProperty("arbitraryBatch", true); expect("invalid_args", () -> new OwnedBlockReplacement.Request(bad));
        JsonObject same = args(); same.addProperty("emptyHotbarSlot", 1); expect("invalid_args", () -> new OwnedBlockReplacement.Request(same));
        JsonObject oldAir = args(); oldAir.add("expectedOldIdentity", OwnedBlockReplacement.airIdentity()); expect("invalid_args", () -> new OwnedBlockReplacement.Request(oldAir));
        JsonObject guessed = args(); guessed.getAsJsonObject("expectedPlacedIdentity").addProperty("metadata", 74); expect("invalid_args", () -> new OwnedBlockReplacement.Request(guessed));
        JsonObject sourceExtra = args(); sourceExtra.getAsJsonObject("expectedSourceItem").addProperty("nbtHash", "guessed"); expect("invalid_args", () -> new OwnedBlockReplacement.Request(sourceExtra));
        JsonObject sourceCount = copy(args().getAsJsonObject("expectedSourceItem")); sourceCount.addProperty("count", 31); expect("target_unverified", () -> request.requireSource(sourceCount));
        request.requireSource(args().get("expectedSourceItem"));
        for (String key : new String[] {"requireBackground", "expectedWorld", "expectedProfile"}) {
            JsonObject invalid = context();
            if ("requireBackground".equals(key)) invalid.addProperty(key, false); else if ("expectedProfile".equals(key)) invalid.addProperty(key, "player"); else invalid.remove(key);
            expect("invalid_request", () -> GuardedContext.parse(OwnedBlockReplacement.OPERATION, invalid).requireOwnedReplacement());
        }
        GuardedContext guard = guard(); guard.requireOwnedReplacement(); check(!guard.outcomeUnknown(), "Replacement parsing does not mark mutation started");
        JsonObject lost = snapshot(1, 1); lost.getAsJsonObject("background").addProperty("enabled", false);
        expect("TAKEOVER_LOST", () -> guard.replacementMutationStarting(lost)); check(!guard.outcomeUnknown(), "Pre-mutation guard refusal is a known outcome");
        guard.replacementMutationStarting(snapshot(1, 1)); check(guard.outcomeUnknown(), "Actual selection boundary marks mutation outcome unknown");
        for (String op : new String[] {"world.inspect_many", "player.plan_approach"}) {
            GuardedContext child = guard.replacementRead(op); child.check(snapshot(2, 8));
            check(!child.action && "after".equals(child.result(new JsonObject(), snapshot(2, 8)).context.get("phase").getAsString()), "Fixed child reads retain their own AFTER context");
        }
        expect("invalid_request", () -> guard.replacementRead("inventory.inspect"));
        GuardedContext legacy = GuardedContext.parse("player.interact", context());
        try { legacy.execute(() -> snapshot(1, 1), () -> { throw new BridgeException("native_refused", "fixture"); }); throw new AssertionError("expected native failure"); }
        catch (BridgeException expected) { check(legacy.outcomeUnknown(), "Old guard.execute retains its original conservative action-start timing"); }
    }

    private static InventoryBasic inventory(int size) {
        InventoryBasic result = new InventoryBasic("full fixture", false, size);
        ItemStack source = new ItemStack(fixtureItem, 32, 74); result.setInventorySlotContents(1, source);
        ItemStack tagged = new ItemStack(fixtureItem, 7, 0); tagged.stackTagCompound = new NBTTagCompound();
        tagged.stackTagCompound.setString("unselected-proof", "initial"); result.setInventorySlotContents(size - 1, tagged); return result;
    }
    private static OwnedBlockReplacement.Request requestFor(ItemStack stack) {
        JsonObject actual = JsonData.item(stack).getAsJsonObject(), requested = args(), source = new JsonObject();
        for (String key : new String[] {"registryName", "metadata", "count", "hasTag"}) source.add(key, actual.get(key));
        requested.add("expectedSourceItem", source); return new OwnedBlockReplacement.Request(requested);
    }
    private static void sourcePreflight() throws Exception {
        Item nonBlock = new Item() { @Override public String getItemStackDisplayName(ItemStack stack) { return "Non-placeable fluid-cell-like fixture"; } };
        Item.itemRegistry.addObject(32241, "owned_replacement_fixture:non_block", nonBlock);
        ItemStack matching = new ItemStack(nonBlock, 32, 74); OwnedBlockReplacement.Request request = requestFor(matching);
        request.requireSource(JsonData.item(matching));
        check(true, "An exact matching registry/meta/count/hasTag DTO alone cannot prove native ItemBlock placeability");
        expect("unsupported", () -> request.requireSource(matching));
        ItemStack block = new ItemStack(fixtureItem, 32, 74); requestFor(block).requireSource(block);
        check(true, "The actual public ItemBlock gate accepts a correctly identified native block source");
        expect("unsupported", () -> request.requireSource((ItemStack) null));
        ItemStack empty = new ItemStack(fixtureItem, 0, 74); expect("unsupported", () -> requestFor(block).requireSource(empty));
        ItemStack oversized = block.copy(); oversized.stackTagCompound = new NBTTagCompound(); char[] tag = new char[65537]; Arrays.fill(tag, 'x');
        oversized.stackTagCompound.setString("source-preflight", new String(tag));
        requestFor(oversized).requireSource(JsonData.item(oversized));
        expect("unsupported", () -> requestFor(oversized).requireSource(oversized));
        for (ItemStack invalid : new ItemStack[] {matching, oversized}) try (Harness harness = new Harness()) {
            harness.fixture.clientInventory.setInventorySlotContents(1, invalid); harness.fixture.request = requestFor(invalid);
            OwnedBlockReplacement.Failure failure = harness.failure(); evidence.add(invalid == matching ? "nonItemBlockSourcePreflight" : "oversizedTagSourcePreflight", failure.details);
            check("unsupported".equals(failure.code) && "prepare".equals(failure.details.get("stage").getAsString()),
                    "Matching unsupported source type/tag is refused in prepare, before selection and digging");
            check(!failure.details.get("outcome_unknown").getAsBoolean() && !failure.details.get("nativeDigSubmitted").getAsBoolean()
                    && !failure.details.get("nativePlaceSubmitted").getAsBoolean() && !harness.fixture.guard.outcomeUnknown(),
                    "Invalid native source has a known no-mutation outcome with neither invocation attempted");
            check(harness.fixture.digs == 0 && harness.fixture.places == 0 && harness.fixture.slot == 1
                    && !harness.fixture.events.contains("C09-empty") && harness.server.tickets == 0,
                    "An unplaceable source never selects the empty slot, sends the selection packet, queues server work or enters native dig/place");
        }
    }
    private static final class FixtureBlock extends Block { FixtureBlock() { super(Material.rock); } }
    private static void inventoryCopies() {
        for (int size : new int[] {41, 64, 256}) {
            InventoryBasic actual = inventory(size); OwnedBlockReplacement.InventorySnapshot saved = new OwnedBlockReplacement.InventorySnapshot(actual, null);
            saved.require(actual, null); check(saved.slots.length == size && saved.slots[size - 1] != actual.getStackInSlot(size - 1)
                    && saved.slots[size - 1].stackTagCompound != actual.getStackInSlot(size - 1).stackTagCompound, "Every actual slot and tag is independently copied, size " + size);
            JsonObject before = InspectManyPlayerInventory.read(actual, 8, null);
            actual.getStackInSlot(size - 1).stackTagCompound.setString("unselected-proof", "changed");
            JsonObject after = InspectManyPlayerInventory.read(actual, 1, null);
            OwnedBlockReplacement.requireConserved(before, after);
            check(before.getAsJsonArray("slots").equals(after.getAsJsonArray("slots")), "Same registry/meta/count/hasTag DTO cannot detect NBT-only mutation");
            expect("inventory_changed", () -> saved.require(actual, null));
            check("initial".equals(saved.slots[size - 1].stackTagCompound.getString("unselected-proof")), "Live NBT mutation never changes the saved independent snapshot");
            actual.getStackInSlot(size - 1).stackTagCompound.setString("unselected-proof", "initial"); saved.require(actual, null);
            actual.getStackInSlot(1).stackSize--; expect("inventory_changed", () -> saved.require(actual, null));
            expect("inventory_changed", () -> OwnedBlockReplacement.requireConserved(before, InspectManyPlayerInventory.read(actual, 1, null)));
            actual.getStackInSlot(1).stackSize++;
            expect("inventory_changed", () -> saved.require(actual, new ItemStack(fixtureItem)));
            expect("inventory_observation_unavailable", () -> new OwnedBlockReplacement.InventorySnapshot(actual, new ItemStack(fixtureItem)));
            JsonObject incomplete = copy(before); incomplete.getAsJsonArray("slots").get(size - 1).getAsJsonObject().addProperty("index", 0);
            expect("inventory_observation_unavailable", () -> OwnedBlockReplacement.requireFullInventory(incomplete));
        }
        expect("inventory_observation_unavailable", () -> new OwnedBlockReplacement.InventorySnapshot(new InventoryBasic("too many", false, 257), null));
    }

    private static JsonObject serverPlayer(InventoryBasic inventory, long tick, int slot) {
        JsonObject result = new JsonObject(); result.addProperty("source", "integrated_server"); result.addProperty("dimension", 0); result.addProperty("worldTick", tick);
        result.addProperty("playerId", "uuid"); result.add("inventory", InspectManyPlayerInventory.read(inventory, slot, null));
        result.addProperty("selectedHotbarSlot", slot); result.addProperty("creative", true); result.addProperty("flying", true); result.addProperty("allowFlying", true);
        JsonObject feet = new JsonObject(); feet.addProperty("x", -44.5); feet.addProperty("y", 2); feet.addProperty("z", 123.5); result.add("feet", feet); return result;
    }
    private static JsonObject owner(String registry, int metadata, long tick) {
        JsonObject point = new JsonObject(); point.addProperty("x", -44); point.addProperty("y", 2); point.addProperty("z", 125);
        point.addProperty("registryName", registry); point.addProperty("metadata", metadata); point.addProperty("worldTick", tick);
        point.addProperty("source", "integrated_server"); point.addProperty("dimension", 0);
        JsonObject gt = new JsonObject(); gt.addProperty("metaTileId", 74); if (registry.startsWith("gregtech:")) point.add("gregtech", gt);
        JsonObject row = new JsonObject(); row.addProperty("ok", true); row.add("data", point); JsonArray rows = new JsonArray(); rows.add(row);
        JsonObject result = new JsonObject(); result.addProperty("source", "integrated_server"); result.addProperty("dimension", 0); result.add("results", rows); return result;
    }
    private static JsonObject observation(InventoryBasic inventory, JsonObject owner, long tick, int slot) {
        JsonObject dto = InspectManyPlayerInventory.read(inventory, slot, null); dto.addProperty("source", "client"); dto.addProperty("inventorySource", "current_player_inventory");
        dto.addProperty("playerInventoryBindingVerified", true); dto.addProperty("playerId", "uuid"); dto.addProperty("sessionId", "session");
        dto.addProperty("clientTicks", tick); dto.addProperty("dimension", 0); dto.addProperty("worldTick", 88000 + tick);
        return OwnedBlockReplacement.pair(InspectManyPlayerInventory.after(new InspectManyPlayerInventory.Request(131072, 1048576), owner,
                () -> dto, () -> snapshot(tick, slot), guard().replacementRead("world.inspect_many"), () -> 0));
    }
    private static void epochBindings() {
        InventoryBasic actual = inventory(41); JsonObject server = serverPlayer(actual, 88001, 8), after = observation(actual, owner("minecraft:stone", 0, 88001), 1, 8);
        OwnedBlockReplacement.requireEpochBinding(server, after); check(true, "Fresh server and AFTER pose/selection bindings are accepted without confusing total worldTick with day time");
        for (String key : new String[] {"playerId", "dimension", "selectedHotbarSlot", "creative", "flying", "allowFlying"}) {
            JsonObject changed = copy(server);
            if ("playerId".equals(key)) changed.addProperty(key, "other"); else if ("dimension".equals(key) || "selectedHotbarSlot".equals(key)) changed.addProperty(key, 1); else changed.addProperty(key, false);
            expect("stale_world", () -> OwnedBlockReplacement.requireEpochBinding(changed, after));
        }
        for (String axis : new String[] {"x", "y", "z"}) {
            JsonObject changed = copy(server); changed.getAsJsonObject("feet").addProperty(axis, 99);
            expect("stale_world", () -> OwnedBlockReplacement.requireEpochBinding(changed, after));
        }
        JsonObject nested = copy(server); nested.getAsJsonObject("inventory").addProperty("selectedHotbarSlot", 1);
        expect("inventory_changed", () -> OwnedBlockReplacement.requireEpochBinding(nested, after));
    }

    private static void queuedSequence() throws Exception {
        try (Harness harness = new Harness()) {
            GuardedContext.Result result = harness.run(); evidence.add("settledNoMove", OwnedBlockReplacement.pair(result));
            check(harness.client.tickets == 4 && harness.server.tickets == 3, "Settled no-move replacement uses exactly four client and three server tickets");
            System.out.println("Owned replacement scheduler: settled no-move = 4 client + 3 server tickets; native dig/place = 1/1.");
        }
        try (Harness harness = new Harness()) {
            Fixture stages = harness.fixture; stages.syncPolls = 2; stages.finalPolls = 2;
            GuardedContext.Result result = harness.run(); JsonObject receipt = result.data;
            check(stages.digs == 1 && stages.places == 1 && stages.serverAirChecks >= 3 && stages.serverPlaceChecks >= 5,
                    "Fresh pending server and client confirmations do not replay either native operation");
            check(stages.events.indexOf("server-empty-confirmed") < stages.events.indexOf("dig-native")
                    && stages.events.indexOf("server-air-confirmed") < stages.events.indexOf("place-native")
                    && stages.events.indexOf("server-place-confirmed") < stages.events.lastIndexOf("client-final"),
                    "C09 empty-hand confirmation precedes attack; fresh air precedes placement; final success follows fresh placement");
            check(receipt.get("digConfirmed").getAsBoolean() && receipt.get("placeConfirmed").getAsBoolean()
                    && receipt.get("replacementValidated").getAsBoolean() && "before".equals(result.context.get("phase").getAsString()),
                    "Completed non-atomic receipt retains both confirmations and the original action BEFORE context");
            JsonObject observations = receipt.getAsJsonObject("observations");
            for (String epoch : new String[] {"beforeDig", "afterDig", "afterPlace"}) {
                JsonObject pair = observations.getAsJsonObject(epoch); OwnedBlockReplacement.requireEpochBinding(receipt.getAsJsonObject("serverPlayers").getAsJsonObject(epoch), pair);
                check("world.inspect_many".equals(pair.getAsJsonObject("context").get("operation").getAsString())
                        && pair.getAsJsonObject("data").getAsJsonObject("playerInventory").get("totalSlots").getAsInt() == 41,
                        "Each epoch carries its own full existing 41-slot inventory and AFTER context");
            }
            check(receipt.getAsJsonObject("attackPlan").getAsJsonObject("context").getAsJsonObject("snapshot").get("clientTicks").getAsLong()
                    < observations.getAsJsonObject("beforeDig").getAsJsonObject("context").getAsJsonObject("snapshot").get("clientTicks").getAsLong(),
                    "Attack plan keeps its own earlier read context instead of borrowing the later observation");
            check(receipt.getAsJsonObject("placePlan").getAsJsonObject("context").getAsJsonObject("snapshot").get("clientTicks").getAsLong()
                    < observations.getAsJsonObject("afterDig").getAsJsonObject("context").getAsJsonObject("snapshot").get("clientTicks").getAsLong(),
                    "Placement plan keeps its own earlier read context");
            check(stages.clientSnapshot.slots[1].stackSize == 32 && stages.serverSnapshot.slots[1].stackSize == 32,
                    "Creative source stack count is unchanged in both independent native snapshots");
            check(harness.client.tickets == 12 && harness.server.tickets == 11, "Two pending polls in each server phase plus two final-client polls add only read tickets");
            evidence.add("synchronizationFixture", receipt);
            System.out.println("Owned replacement scheduler: synchronization fixture = 12 client + 11 server tickets; native dig/place remain 1/1.");
        }
        try (Harness harness = new Harness()) {
            harness.fixture.moveAttack = true; harness.fixture.movePlace = true;
            GuardedContext.Result result = harness.run();
            check(result.data.getAsJsonArray("moves").size() == 2 && harness.fixture.recaptures == 2 && harness.fixture.digs == 1 && harness.fixture.places == 1,
                    "Optional authoritative relocations wait for actual client feet and freshly recapture plans without repeating native stages");
            for (int i = 0; i < 2; i++) check(result.data.getAsJsonArray("moves").get(i).getAsJsonObject().has("plan")
                    && result.data.getAsJsonArray("moves").get(i).getAsJsonObject().get("confirmed").getAsBoolean(), "Each move records its own authorizing plan/context");
            check(harness.client.tickets == 8 && harness.server.tickets == 7, "One optional relocation per phase adds two client and two server tickets each");
            System.out.println("Owned replacement scheduler: one move before each action = 8 client + 7 server tickets; native dig/place remain 1/1.");
        }
        try (Harness harness = new Harness()) {
            harness.fixture.moveAttack = true; harness.fixture.moveNeverSettles = true;
            OwnedBlockReplacement.Failure failure = harness.failure();
            check("stale_world".equals(failure.code) && harness.fixture.recaptures == 1 && harness.fixture.digs == 0 && harness.fixture.places == 0,
                    "A second relocation for the same phase is refused before a second move or any native action");
        }
        try (Harness harness = new Harness()) {
            harness.fixture.syncPolls = 129;
            OwnedBlockReplacement.Failure failure = harness.failure();
            check("timeout".equals(failure.code) && harness.fixture.emptyChecks == 128 && harness.fixture.digs == 0 && harness.fixture.places == 0,
                    "Unsettled empty-hand acknowledgement ends at the finite poll bound before native digging");
        }
        for (String boundary : new String[] {"native_dig_before", "native_dig_after", "native_place_before", "native_place_after"}) {
            try (Harness harness = new Harness()) {
                harness.fixture.boundary = boundary; harness.fixture.stop = "refuse";
                OwnedBlockReplacement.Failure error = harness.failure(); check("native_refused".equals(error.code), "Native refusal preserves its actual code");
                boolean dig = !"native_dig_before".equals(boundary), place = "native_place_after".equals(boundary);
                check(error.details.get("nativeDigSubmitted").getAsBoolean() == dig && error.details.get("nativePlaceSubmitted").getAsBoolean() == place,
                        "A local false/exception retains exact invocation submission rather than inferring no side effect");
                check(harness.fixture.digs <= 1 && harness.fixture.places <= 1, "Refusal is terminal and never retried");
            }
        }
    }

    private static void stopBoundaries() throws Exception {
        String[] boundaries = {"prepare_begin", "prepare", "before_dig", "attack_plan", "native_dig_before", "native_dig_after", "after_dig", "place_plan", "native_place_before", "native_place_after", "after_place", "final_observation"};
        for (String stop : new String[] {"cancel", "deadline", "interrupt"}) for (String boundary : boundaries) {
            try (Harness harness = new Harness()) {
                harness.fixture.stop = stop; harness.fixture.boundary = boundary;
                OwnedBlockReplacement.Failure error = harness.failure();
                check(("deadline".equals(stop) ? "timeout" : "cancelled").equals(error.code), "Endpoint stop code is exact at " + stop + "/" + boundary);
                check(OwnedBlockReplacement.STAGES.contains(error.details.get("stage").getAsString()), "Every partial stop has a finite protocol stage");
                boolean dig = Arrays.asList("native_dig_after", "after_dig", "place_plan", "native_place_before", "native_place_after", "after_place", "final_observation").contains(boundary);
                boolean place = Arrays.asList("native_place_after", "after_place", "final_observation").contains(boundary);
                boolean digConfirmed = dig && !"native_dig_after".equals(boundary), placeConfirmed = Arrays.asList("after_place", "final_observation").contains(boundary);
                check(harness.fixture.digs == (dig ? 1 : 0) && harness.fixture.places == (place ? 1 : 0), "No later native executes at " + stop + "/" + boundary);
                check(error.details.get("nativeDigSubmitted").getAsBoolean() == dig && error.details.get("nativePlaceSubmitted").getAsBoolean() == place
                        && error.details.get("digConfirmed").getAsBoolean() == digConfirmed && error.details.get("placeConfirmed").getAsBoolean() == placeConfirmed,
                        "Partial native/confirmation flags stay accurate at " + stop + "/" + boundary);
                check(error.details.get("outcome_unknown").getAsBoolean() == !"prepare_begin".equals(boundary), "Actual earlier selection/mutation determines outcome uncertainty");
                if ("interrupt".equals(stop)) check(harness.workerInterrupted, "Interrupted HTTP worker keeps its interrupt flag after terminal dispatch");
                evidence.add(stop + "-" + boundary, error.details);
            }
        }
        try (Harness harness = new Harness()) {
            harness.fixture.boundary = "move_before_dig"; harness.fixture.stop = "cancel"; harness.fixture.moveAttack = true;
            OwnedBlockReplacement.Failure error = harness.failure(); check("move_before_dig".equals(error.details.get("stage").getAsString()) && harness.fixture.digs == 0 && harness.fixture.places == 0,
                    "Cancellation after optional relocation still prevents the first native controller invocation");
        }
    }

    private static void registry() throws Exception {
        RequestRegistry registry = new RequestRegistry(4, 300000);
        GameQueue.Control old = registry.register("old", "player.look"), replacement = registry.register("new", OwnedBlockReplacement.OPERATION);
        check(old.getClass() == GameQueue.Control.class && replacement instanceof OwnedBlockReplacement.Control,
                "Unrelated legacy operations retain their ordinary control while replacement supports voluntary stage stops");
        GameQueue.Control placement = registry.register("place", "player.place_block"), survival = registry.register("survival", SurvivalConstructionAdapter.OPERATION);
        check(placement instanceof OwnedBlockReplacement.Control && survival instanceof OwnedBlockReplacement.Control,
                "Native placement and survival construction support voluntary stops between committed stages");
        GameQueue queue = new GameQueue(2); GameQueue.Ticket<Integer> oldTicket = queue.submit(() -> 1, 1000, old); queue.drain(1); check(queue.await(oldTicket, 1000) == 1, "Existing request can claim its original control");
        check("already_running".equals(registry.cancel("old")) && !old.isCancelled(), "Old cancellation semantics remain already_running after claim");
        GameQueue.Ticket<Integer> newTicket = queue.submit(() -> 2, 1000, replacement); queue.drain(1); check(queue.await(newTicket, 1000) == 2, "Replacement can claim its endpoint-specific control");
        check("already_running".equals(registry.cancel("new")) && !replacement.isCancelled() && ((OwnedBlockReplacement.Control) replacement).stopRequested(),
                "Replacement records a voluntary next-stage stop while preserving the public cancellation return contract");
        GameQueue.Control[] staged = {placement, survival}; String[] stagedIds = {"place", "survival"};
        for (int i = 0; i < staged.length; i++) {
            GameQueue.Ticket<Integer> stagedTicket = queue.submit(() -> 3, 1000, staged[i]); queue.drain(1);
            check(queue.await(stagedTicket, 1000) == 3, "Staged native operation can claim its control");
            check("already_running".equals(registry.cancel(stagedIds[i])) && !staged[i].isCancelled() && ((OwnedBlockReplacement.Control) staged[i]).stopRequested(),
                    "Staged native cancellation retains the public return contract and requests a stop before the next stage");
        }
        expect("duplicate_request", () -> registry.register("new", OwnedBlockReplacement.OPERATION));
    }

    private static void http() throws Exception {
        HttpFixture bridge = new HttpFixture(); BridgeHttpServer server = new BridgeHttpServer(bridge, "owned-replacement-self-test"); server.start(0);
        try {
            JsonObject before = post(server.port(), OwnedBlockReplacement.OPERATION, null, "pre-validation");
            check(!before.get("ok").getAsBoolean() && "prepare".equals(before.getAsJsonObject("error").get("stage").getAsString())
                    && !before.getAsJsonObject("error").get("nativeDigSubmitted").getAsBoolean(), "New endpoint pre-validation errors include typed known-no-submission state");
            JsonObject partial = post(server.port(), OwnedBlockReplacement.OPERATION, context(), "partial");
            evidence.add("actualLoopbackPartialError", partial);
            check(!partial.get("ok").getAsBoolean() && "after_dig".equals(partial.getAsJsonObject("error").get("stage").getAsString())
                    && partial.getAsJsonObject("error").get("nativeDigSubmitted").getAsBoolean() && partial.getAsJsonObject("error").get("digConfirmed").getAsBoolean()
                    && !partial.getAsJsonObject("error").get("nativePlaceSubmitted").getAsBoolean() && partial.getAsJsonObject("error").get("outcome_unknown").getAsBoolean(),
                    "Real authenticated HTTP preserves endpoint-only partial flags through ExecutionException");
            JsonObject old = post(server.port(), "player.interact", context(), "ordinary");
            check(old.getAsJsonObject("error").entrySet().size() == 3 && !old.getAsJsonObject("error").has("stage")
                    && old.getAsJsonObject("error").get("outcome_unknown").getAsBoolean(), "Existing guarded error envelope remains code/message/outcome_unknown only");
            bridge.large = true; JsonObject oversized = post(server.port(), OwnedBlockReplacement.OPERATION, context(), "oversize");
            evidence.add("actualLoopbackOversizedError", oversized);
            check("result_too_large".equals(oversized.getAsJsonObject("error").get("code").getAsString())
                    && "completed".equals(oversized.getAsJsonObject("error").get("stage").getAsString())
                    && oversized.getAsJsonObject("error").get("nativePlaceSubmitted").getAsBoolean() && oversized.getAsJsonObject("error").get("placeConfirmed").getAsBoolean(),
                    "Ordinary complete HTTP byte limit rejects without truncation or misreporting completed native effects");
        } finally { server.stop(); }
    }
    private static JsonObject post(int port, String operation, JsonObject includeContext, String id) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/v1/call").openConnection();
        connection.setConnectTimeout(1000); connection.setReadTimeout(4000); connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Authorization", "Bearer owned-replacement-self-test");
        JsonObject request = new JsonObject(); request.addProperty("requestId", id + UUID.randomUUID()); request.addProperty("operation", operation); request.add("args", new JsonObject());
        if (includeContext != null) request.add("includeContext", includeContext); byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length); try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new JsonParser().parse(new String(output.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
        } finally { connection.disconnect(); }
    }
    private static final class HttpFixture extends GameBridge {
        @Override public JsonObject prepareOperation(String op, JsonObject args, GameQueue.Control control) { return args; }
        boolean large;
        @Override public JsonObject dispatch(String operation, JsonObject args, GameQueue.Control control) { throw new BridgeException("invalid_request", "Guard required"); }
        @Override public GuardedContext.Result dispatchGuarded(String operation, JsonObject args, GameQueue.Control control, GuardedContext guard) throws ExecutionException {
            if (!OwnedBlockReplacement.OPERATION.equals(operation)) {
                try { return guard.execute(() -> snapshot(1, 1), () -> { throw new BridgeException("native_refused", "ordinary"); }); }
                catch (Exception error) { throw new ExecutionException(error); }
            }
            guard.replacementMutationStarting(snapshot(1, 1));
            if (large) { JsonObject data = new JsonObject(); char[] padding = new char[1048576]; Arrays.fill(padding, 'x'); data.addProperty("padding", new String(padding)); return guard.result(data, snapshot(1, 1)); }
            OwnedBlockReplacement.State state = new OwnedBlockReplacement.State(control, () -> 0, 8000); state.markDig(); state.digConfirmed = true; state.stage = "after_dig";
            throw new ExecutionException(new OwnedBlockReplacement.Failure(state, new BridgeException("target_unverified", "partial fixture")));
        }
    }

    private static final class Harness implements AutoCloseable {
        final CountingQueue client = new CountingQueue(), server = new CountingQueue();
        final AtomicLong clock = new AtomicLong(); final Fixture fixture = new Fixture(this);
        final Thread clientOwner, serverOwner; volatile Thread worker; volatile boolean running = true, workerInterrupted;
        volatile GuardedContext.Result result; volatile Throwable failure;
        Harness() {
            clientOwner = owner(client, "replacement-client-owner"); serverOwner = owner(server, "replacement-server-owner");
        }
        private Thread owner(GameQueue queue, String name) {
            Thread owner = new Thread(() -> { while (running) { queue.drain(1); LockSupport.parkNanos(100000L); } }, name); owner.setDaemon(true); owner.start(); return owner;
        }
        GuardedContext.Result run() throws Exception {
            worker = new Thread(() -> {
                try { result = OwnedBlockReplacement.dispatch(client, server, fixture.control, fixture, clock::get, 8000); }
                catch (Throwable error) { failure = error; }
                finally { workerInterrupted = Thread.currentThread().isInterrupted(); }
            }, "replacement-http-worker"); worker.start(); worker.join(4000);
            check(!worker.isAlive(), "Worker completes while two distinct owner pumps never await each other's queue");
            if (worker.isAlive()) { worker.interrupt(); throw new AssertionError("Worker blocked"); }
            if (failure != null) { if (failure instanceof Exception) throw (Exception) failure; throw (Error) failure; } return result;
        }
        OwnedBlockReplacement.Failure failure() throws Exception {
            try { run(); throw new AssertionError("Expected terminal stop"); }
            catch (OwnedBlockReplacement.Failure expected) { return expected; }
        }
        @Override public void close() throws InterruptedException {
            running = false; clientOwner.interrupt(); serverOwner.interrupt(); clientOwner.join(1000); serverOwner.join(1000);
            check(!clientOwner.isAlive() && !serverOwner.isAlive(), "Both test owner pumps shut down");
        }
    }
    private static final class CountingQueue extends GameQueue {
        int tickets;
        CountingQueue() { super(16); }
        @Override public <T> Ticket<T> submit(Callable<T> action, long timeout, Control control) {
            tickets++; return super.submit(action, timeout, control);
        }
    }
    private static final class Fixture implements OwnedBlockReplacement.Stages {
        final Harness harness; final OwnedBlockReplacement.Control control = new OwnedBlockReplacement.Control(); final GuardedContext guard = guard();
        final InventoryBasic clientInventory = inventory(41), serverInventory = inventory(41); final List<String> events = new ArrayList<String>();
        OwnedBlockReplacement.Request request = requestFor(clientInventory.getStackInSlot(1));
        OwnedBlockReplacement.InventorySnapshot clientSnapshot, serverSnapshot;
        int digs, places, syncPolls, finalPolls, emptyChecks, serverAirChecks, serverPlaceChecks, finalChecks, recaptures;
        int slot = 1; long tick; boolean moveAttack, movePlace, movedAttack, movedPlace, moveNeverSettles; String stop = "", boundary = "";
        Fixture(Harness harness) { this.harness = harness; }
        private void owner(boolean server) { check(Thread.currentThread().getName().equals(server ? "replacement-server-owner" : "replacement-client-owner"), "Callback executes only on its actual owning queue"); }
        private void checkpoint(OwnedBlockReplacement.State state, String boundary) {
            if (!this.boundary.equals(boundary)) return;
            if ("cancel".equals(stop)) control.cancelBeforeExecution();
            else if ("deadline".equals(stop)) harness.clock.set(8000000000L);
            else if ("interrupt".equals(stop)) { harness.worker.interrupt(); control.cancelBeforeExecution(); }
            else if ("refuse".equals(stop)) throw new BridgeException("native_refused", "Mocked native refusal at " + boundary);
            state.checkRunning();
        }
        public void prepare(OwnedBlockReplacement.State state) {
            owner(false); checkpoint(state, "prepare_begin"); request.requireSource(clientInventory.getStackInSlot(1));
            clientSnapshot = new OwnedBlockReplacement.InventorySnapshot(clientInventory, null);
            state.before = snapshot(++tick, slot); guard.replacementMutationStarting(state.before); state.markMutation(); slot = 8; events.add("C09-empty"); checkpoint(state, "prepare");
        }
        public OwnedBlockReplacement.Decision beforeDig(OwnedBlockReplacement.State state) {
            owner(true); checkpoint(state, "before_dig");
            if (emptyChecks++ < syncPolls) return OwnedBlockReplacement.Decision.PENDING;
            if (serverSnapshot == null) serverSnapshot = new OwnedBlockReplacement.InventorySnapshot(serverInventory, null);
            serverSnapshot.require(serverInventory, null);
            check(events.contains("C09-empty") && slot == 8 && serverInventory.getStackInSlot(8) == null,
                    "Fresh server empty-slot confirmation is required before mocked creative clickBlock");
            events.add("server-empty-confirmed"); state.serverPlayers.add("beforeDig", serverPlayer(serverInventory, 88000 + tick, 8));
            state.stage = "attack_plan"; checkpoint(state, "attack_plan"); return OwnedBlockReplacement.Decision.READY;
        }
        private JsonObject plan() {
            JsonObject data = new JsonObject(); data.addProperty("status", "ready"); return OwnedBlockReplacement.pair(guard.replacementRead("player.plan_approach").result(data, snapshot(++tick, slot)));
        }
        public OwnedBlockReplacement.Decision dig(OwnedBlockReplacement.State state) {
            owner(false); checkpoint(state, "native_dig_before"); state.attackPlan = plan();
            if (moveAttack && !movedAttack) return OwnedBlockReplacement.Decision.MOVE;
            state.observations.add("beforeDig", observation(clientInventory, OwnedBlockReplacementSelfTest.owner("minecraft:stone", 0, 88000 + tick), ++tick, 8));
            state.markDig(); digs++; events.add("dig-native"); JsonObject nativeResult = new JsonObject(); nativeResult.addProperty("accepted", true); state.nativeDig = nativeResult;
            checkpoint(state, "native_dig_after"); return OwnedBlockReplacement.Decision.READY;
        }
        public OwnedBlockReplacement.Decision afterDig(OwnedBlockReplacement.State state) {
            owner(true); serverSnapshot.require(serverInventory, null); if (serverAirChecks++ < syncPolls) return OwnedBlockReplacement.Decision.PENDING;
            check(digs == 1, "Fresh server air confirmation follows exactly one actual submitted attack"); events.add("server-air-confirmed"); state.digConfirmed = true;
            state.serverPlayers.add("afterDig", serverPlayer(serverInventory, 88000 + tick, 8)); checkpoint(state, "after_dig"); state.stage = "place_plan"; checkpoint(state, "place_plan");
            return OwnedBlockReplacement.Decision.READY;
        }
        public OwnedBlockReplacement.Decision place(OwnedBlockReplacement.State state) {
            owner(false); checkpoint(state, "native_place_before"); state.placePlan = plan();
            if (movePlace && !movedPlace) return OwnedBlockReplacement.Decision.MOVE;
            state.observations.add("afterDig", observation(clientInventory, OwnedBlockReplacementSelfTest.owner("minecraft:air", 0, 88000 + tick), ++tick, 8));
            check(state.digConfirmed && events.contains("server-air-confirmed"), "Placement cannot submit before fresh authoritative air confirmation");
            slot = 1; events.add("C09-source"); state.markPlace(); places++; events.add("place-native"); JsonObject result = new JsonObject(); result.addProperty("accepted", true); state.nativePlace = result;
            checkpoint(state, "native_place_after"); return OwnedBlockReplacement.Decision.READY;
        }
        public OwnedBlockReplacement.Decision afterPlace(OwnedBlockReplacement.State state) {
            owner(true); serverSnapshot.require(serverInventory, null); if (serverPlaceChecks++ < syncPolls) return OwnedBlockReplacement.Decision.PENDING;
            check(places == 1 && events.indexOf("C09-source") < events.indexOf("place-native"), "Explicit public source selection packet precedes native use even if controller private cache already held this source");
            state.placeConfirmed = true; events.add("server-place-confirmed"); state.serverPlayers.add("afterPlace", serverPlayer(serverInventory, 88000 + tick, 1));
            checkpoint(state, "after_place"); return OwnedBlockReplacement.Decision.READY;
        }
        public GuardedContext.Result finish(OwnedBlockReplacement.State state) {
            owner(false); checkpoint(state, "final_observation"); events.add("client-final"); if (finalChecks++ < finalPolls) return null;
            clientSnapshot.require(clientInventory, null);
            state.observations.add("afterPlace", observation(clientInventory, OwnedBlockReplacementSelfTest.owner("gregtech:gt.blockmachines", 1, 88000 + tick), ++tick, 1));
            JsonObject conserved = new JsonObject(); conserved.addProperty("scope", "all_actual_player_slots"); conserved.addProperty("actualClientSlots", 41); conserved.addProperty("actualServerSlots", 41);
            for (String field : new String[] {"clientItemsPreserved", "serverItemsPreserved", "nbtPreserved", "cursorEmpty", "creativeSourceCountUnchanged"}) conserved.addProperty(field, true);
            state.conservation = conserved; return guard.result(state.receipt(), state.before);
        }
        public void move(OwnedBlockReplacement.State state, boolean place) { owner(true); state.markMutation(); checkpoint(state, place ? "move_before_place" : "move_before_dig"); }
        public boolean awaitMove(OwnedBlockReplacement.State state, boolean place) {
            owner(false); if (place) movedPlace = !moveNeverSettles; else movedAttack = !moveNeverSettles; recaptures++;
            JsonObject move = new JsonObject(); move.addProperty("phase", place ? "before_place" : "before_dig"); move.addProperty("confirmed", true);
            move.add("plan", place ? state.placePlan : state.attackPlan); move.add("afterSnapshot", snapshot(++tick, slot)); state.moves.add(move); return true;
        }
        public void refresh(OwnedBlockReplacement.State state, boolean place) { owner(false); tick++; }
    }
    private static GuardedContext guard() { return GuardedContext.parse(OwnedBlockReplacement.OPERATION, context()); }
    private static JsonObject copy(JsonObject value) { return new JsonParser().parse(value.toString()).getAsJsonObject(); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), "Expected " + code + ", actual " + failure.code); }
    }
}
