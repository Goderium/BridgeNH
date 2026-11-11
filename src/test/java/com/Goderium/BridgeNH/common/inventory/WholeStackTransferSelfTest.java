package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Owning-queue transaction tests; actual native inventories/clicks/rays are supplied by the runtime adapter. */
public final class WholeStackTransferSelfTest {
    private static int checks;
    public static int run() throws Exception {
        checks = 0;
        for (boolean fromPlayer : new boolean[] {true, false}) {
            Fixture fixture = new Fixture(fromPlayer, "");
            WholeStackTransfer.Inventory before = fixture.inventory(0);
            for (int phase = 0; phase <= 2; phase++) {
                check(fixture.inventory(phase).matches(before, fixture.request, phase), "Both directions preserve exact complete native state at all three phases");
                for (int index = 0; index < 41; index++) {
                    WholeStackTransfer.Inventory changed = fixture.inventory(phase);
                    changed.player[index] = new ItemStack(fixture.item, 13, 4);
                    check(!changed.matches(before, fixture.request, phase), "Every player slot including armor is conserved");
                }
                for (int index = 0; index < 4; index++) {
                    WholeStackTransfer.Inventory changed = fixture.inventory(phase);
                    changed.machine[index] = new ItemStack(fixture.item, 13, 4);
                    check(!changed.matches(before, fixture.request, phase), "Every machine slot is conserved");
                }
                WholeStackTransfer.Inventory changed = fixture.inventory(phase);
                changed.player[40].getTagCompound().setString("unrelated", "drift");
                check(!changed.matches(before, fixture.request, phase), "Full unrelated player NBT is conserved without a display hash");
                changed = fixture.inventory(phase); changed.machine[3].getTagCompound().setString("unrelated", "drift");
                check(!changed.matches(before, fixture.request, phase), "Full unrelated machine NBT is conserved");
                check(!new WholeStackTransfer.Inventory(changed.player, changed.machine, changed.cursor, 6).matches(before, fixture.request, phase), "Actual selection is conserved");
            }
            GuardedContext.Result result = fixture.run();
            check(fixture.clicks == 2 && result.data.get("serverPickupConfirmed").getAsBoolean()
                    && result.data.get("serverPlaceConfirmed").getAsBoolean() && result.data.get("allPlayerSlots").getAsInt() == 41,
                    "Exactly two native clicks succeed only after both real server inventory transitions");
            check(result.context.get("phase").getAsString().equals("before") && result.data.get("clientCursor").isJsonNull()
                    && result.data.get("serverCursor").isJsonNull(), "Standard initial BEFORE and empty observed cursors are retained");
            for (String fault : new String[] {"server_loss", "server_drift", "server_binding", "client_binding", "prediction_refused", "place_refused", "context_after_pickup"}) {
                Fixture failed = new Fixture(fromPlayer, fault);
                try { failed.run(); throw new AssertionError("Expected native failure: " + fault); }
                catch (WholeStackTransfer.Failure failure) {
                    int expected = fault.equals("place_refused") ? 2 : 1;
                    check(failed.clicks == expected && failure.details.get("clicksDispatched").getAsInt() == expected,
                            "A refused, lost or changed transition never invokes a duplicate click or fallback");
                    check(failure.details.get("outcome_unknown").getAsBoolean() && !failure.details.get("automaticReplayAllowed").getAsBoolean()
                            && !failure.details.get("cursorCleared").getAsBoolean(), "Submitted failures preserve conservative uncertainty and do not clear the cursor");
                    JsonObject receipt = failure.details.getAsJsonObject("knownReceipt");
                    check(receipt.get("clientCursorObserved").getAsBoolean() && receipt.has("clientCursorStage")
                            && receipt.get("cursorObservationScope").getAsString().equals("last_observed_native"), "Failure cursor scope states that the retained observation may be earlier than the refusal");
                }
            }
            Fixture cancelled = new Fixture(fromPlayer, ""); cancelled.control.cancelBeforeExecution();
            try { cancelled.run(); throw new AssertionError("Pre-dispatch cancellation accepted"); }
            catch (WholeStackTransfer.Failure failure) {
                check(cancelled.clicks == 0 && !failure.details.get("outcome_unknown").getAsBoolean(), "Known pre-dispatch cancellation issues zero native clicks");
            }
        }
        for (String field : new String[] {"version", "windowId", "sourceSlot", "destinationSlot", "playerInventoryIndex", "machineInventoryIndex", "side"}) {
            JsonObject bad = args(true); bad.addProperty(field, true);
            try { new WholeStackTransfer.Request(bad); throw new AssertionError("Boolean integer accepted: " + field); }
            catch (BridgeException expected) { check(expected.code.equals("invalid_args"), "Native protocol integer types are exact"); }
        }
        for (String field : new String[] {"count", "metadata", "hasTag"}) {
            JsonObject bad = args(true); bad.getAsJsonObject("expectedItem").addProperty(field, field.equals("hasTag") ? "false" : "1");
            try { new WholeStackTransfer.Request(bad); throw new AssertionError("String field accepted: " + field); }
            catch (BridgeException expected) { check(expected.code.equals("invalid_args"), "The full source declaration requires exact native types"); }
        }
        return checks;
    }
    private static JsonObject args(boolean fromPlayer) {
        return new JsonParser().parse("{\"version\":1,\"expectedSessionId\":\"session\",\"expectedWorld\":\"world\",\"screenId\":\"screen\",\"windowId\":4,\"side\":2,\"direction\":\""
                + (fromPlayer ? "player_to_machine" : "machine_to_player") + "\",\"sourceSlot\":0,\"destinationSlot\":1,\"playerInventoryIndex\":9,\"machineInventoryIndex\":1,\"owner\":{\"x\":0,\"y\":64,\"z\":0,\"dimension\":0,\"registryName\":\"gregtech:gt.blockmachines\",\"metaTileId\":1,\"metaClass\":\"example.Machine\"},\"expectedItem\":{\"registryName\":\"example:item\",\"metadata\":0,\"count\":8,\"hasTag\":false}}").getAsJsonObject();
    }
    private static JsonObject contextRequest() {
        return new JsonParser().parse("{\"version\":1,\"expectedSessionId\":\"session\",\"expectedProfile\":\"developer\",\"expectedWorld\":\"world\",\"requireBackground\":true}").getAsJsonObject();
    }
    private static JsonObject snapshotValue() {
        return new JsonParser().parse("{\"sessionId\":\"session\",\"profile\":\"developer\",\"worldLoaded\":true,\"world\":{\"worldName\":\"world\"},\"background\":{\"enabled\":true,\"helperInstalled\":true,\"pauseOnLostFocus\":false}}").getAsJsonObject();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static final class Fixture implements WholeStackTransfer.Access {
        final WholeStackTransfer.Request request;
        final GameQueue client = new GameQueue(8), server = new GameQueue(8);
        final GameQueue.Control control = new GameQueue.Control();
        final Item item = new Item();
        final String fault;
        int clicks, clientPhase, serverReads;
        Fixture(boolean fromPlayer, String fault) { request = new WholeStackTransfer.Request(args(fromPlayer)); this.fault = fault; }
        WholeStackTransfer.Inventory inventory(int phase) {
            ItemStack[] player = new ItemStack[41], machine = new ItemStack[4];
            ItemStack moved = new ItemStack(item, 8, 0), cursor = phase == 1 ? moved : null;
            player[9] = request.fromPlayer() ? phase == 0 ? moved : null : phase == 2 ? moved : null;
            machine[1] = request.fromPlayer() ? phase == 2 ? moved : null : phase == 0 ? moved : null;
            player[40] = new ItemStack(item, 1, 2); machine[3] = new ItemStack(item, 4, 3);
            for (ItemStack unrelated : new ItemStack[] {player[40], machine[3]}) {
                NBTTagCompound tag = new NBTTagCompound(); tag.setString("unrelated", "complete immutable data"); unrelated.setTagCompound(tag);
            }
            return new WholeStackTransfer.Inventory(player, machine, cursor, 7);
        }
        public WholeStackTransfer.View client() {
            if (clicks == 1 && fault.equals("client_binding")) throw new BridgeException("whole_stack_context_changed", "Actual client screen/slot changed");
            return new View(inventory(clientPhase), "client");
        }
        public WholeStackTransfer.View server() {
            if (clicks > 0 && fault.equals("server_binding")) throw new BridgeException("whole_stack_context_changed", "Actual server owner/slot changed");
            int phase = clicks == 0 || fault.equals("server_loss") ? 0 : ++serverReads % 2 == 1 ? clicks - 1 : clicks;
            WholeStackTransfer.Inventory value = inventory(phase);
            if (clicks > 0 && fault.equals("server_drift")) value.machine[3].stackSize++;
            return new View(value, "server");
        }
        public JsonObject snapshot() {
            JsonObject result = snapshotValue(); if (clicks > 0 && fault.equals("context_after_pickup")) result.addProperty("sessionId", "changed"); return result;
        }
        public void click(int slot) {
            if (clicks == 1) check(serverReads >= 2, "Place cannot start before an actual intermediate server confirmation");
            check(slot == (clicks == 0 ? request.source : request.destination), "Native click order is pickup then place");
            clicks++;
            if (!fault.equals("prediction_refused") && !(clicks == 2 && fault.equals("place_refused"))) clientPhase = clicks;
        }
        GuardedContext.Result run() throws Exception {
            CompletableFuture<GuardedContext.Result> result = new CompletableFuture<GuardedContext.Result>();
            Thread worker = new Thread(() -> {
                try { result.complete(WholeStackTransfer.dispatch(client, server, control, request, this, GuardedContext.parse(WholeStackTransfer.OPERATION, contextRequest()))); }
                catch (Throwable error) { result.completeExceptionally(error); }
            }, "whole-stack-http-worker");
            worker.start(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!result.isDone() && System.nanoTime() < deadline) { client.drain(1); server.drain(1); Thread.yield(); }
            try { return result.get(1, TimeUnit.SECONDS); }
            catch (ExecutionException error) { if (error.getCause() instanceof WholeStackTransfer.Failure) throw (WholeStackTransfer.Failure) error.getCause(); throw error; }
            finally { worker.join(1000); }
        }
    }
    private static final class View implements WholeStackTransfer.View {
        final WholeStackTransfer.Inventory value;
        final String identity;
        View(WholeStackTransfer.Inventory value, String identity) { this.value = value; this.identity = identity; }
        public WholeStackTransfer.Inventory inventory() { return value; }
        public void same(WholeStackTransfer.View before) { if (!(before instanceof View) || !identity.equals(((View) before).identity)) throw new BridgeException("whole_stack_context_changed", "Observation identity changed"); }
        public void initial(WholeStackTransfer.Request request) { if (value.cursor != null) throw new BridgeException("whole_stack_context_changed", "Cursor occupied"); }
    }
}
