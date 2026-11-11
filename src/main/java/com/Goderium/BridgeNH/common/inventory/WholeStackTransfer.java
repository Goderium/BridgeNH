package com.Goderium.BridgeNH.common.inventory;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.bridge.GuardedContext;
import com.Goderium.BridgeNH.common.machine.MachineSlotBinding;
import com.Goderium.BridgeNH.common.structure.NativePlacementAdapter;
import com.Goderium.BridgeNH.common.structure.SurvivalConstructionAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

/** Two ordinary native clicks, with real server confirmation between them. Never writes inventory directly. */
public final class WholeStackTransfer {
    public static final String OPERATION = "gui.transfer_whole_stack";
    static final String CONTAINER = "com.cleanroommc.modularui.screen.ModularContainer";
    static final int MAX_POLLS = 80;

    public static JsonObject capabilities() {
        JsonObject value = new JsonObject(); value.addProperty("version", 1); value.addProperty("operation", OPERATION);
        value.addProperty("hasTag", false); value.addProperty("emptyDestinationOnly", true);
        value.addProperty("nativeClicks", 2); value.addProperty("maxMillis", 8000);
        value.addProperty("serverIntermediateRequired", true); value.addProperty("allPlayerSlots", 41);
        value.addProperty("fullNbtConservation", true); value.addProperty("automaticReplayAllowed", false);
        value.addProperty("containerClass", CONTAINER); return value;
    }

    public static final class Request {
        final JsonObject owner;
        public final String screen, session, world, direction, registry;
        public final int window, dimension, source, destination, playerIndex, machineIndex, metadata, count, side;
        public Request(JsonObject args) {
            keys(args, "version", "screenId", "expectedSessionId", "expectedWorld", "windowId", "owner", "direction", "sourceSlot", "destinationSlot", "playerInventoryIndex", "machineInventoryIndex", "expectedItem", "side");
            if (JsonData.integer(args, "version", 1, 1) != 1) throw bad("Unsupported whole-stack protocol.");
            screen = text(args, "screenId"); session = text(args, "expectedSessionId"); world = text(args, "expectedWorld");
            window = JsonData.integer(args, "windowId", 1, 32767); side = JsonData.integer(args, "side", 0, 5);
            source = JsonData.integer(args, "sourceSlot", 0, 511); destination = JsonData.integer(args, "destinationSlot", 0, 511);
            if (source == destination) throw bad("Whole-stack source and destination must differ.");
            playerIndex = JsonData.integer(args, "playerInventoryIndex", 0, 35);
            machineIndex = JsonData.integer(args, "machineInventoryIndex", 0, 255);
            direction = text(args, "direction");
            if (!"player_to_machine".equals(direction) && !"machine_to_player".equals(direction)) throw bad("Unknown transfer direction.");
            owner = object(args, "owner"); keys(owner, "x", "y", "z", "dimension", "registryName", "metaTileId", "metaClass");
            JsonData.integer(owner, "x", -30000000, 30000000); JsonData.integer(owner, "y", 0, 255); JsonData.integer(owner, "z", -30000000, 30000000);
            dimension = JsonData.integer(owner, "dimension", Integer.MIN_VALUE, Integer.MAX_VALUE);
            text(owner, "registryName"); text(owner, "metaClass"); JsonData.integer(owner, "metaTileId", 0, 32767);
            JsonObject item = object(args, "expectedItem"); keys(item, "registryName", "metadata", "count", "hasTag");
            registry = text(item, "registryName"); metadata = JsonData.integer(item, "metadata", 0, 32766); count = JsonData.integer(item, "count", 1, Integer.MAX_VALUE);
            if (!booleanValue(item, "hasTag", false)) throw bad("Version 1 transfers only an explicitly untagged complete source stack.");
        }
        boolean fromPlayer() { return "player_to_machine".equals(direction); }
        void expected(ItemStack stack) {
            if (stack == null || stack.hasTagCompound() || stack.stackSize != count || stack.getMetadata() != metadata
                    || !registry.equals(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()))))
                throw changed("The actual whole source stack differs from the immutable declaration.");
        }
    }

    public interface Access {
        View client();
        View server();
        JsonObject snapshot();
        void click(int slot);
    }
    interface View {
        Inventory inventory();
        void initial(Request request);
        void same(View before);
    }

    /** All full native stacks are copied; unrelated tags are never reduced to hashes or truncated displays. */
    static final class Inventory {
        final ItemStack[] player, machine;
        final ItemStack cursor;
        final int selected;
        Inventory(EntityPlayer actor, IInventory owner) {
            this(copy(actor.inventory), owner == null ? null : copy(owner), actor.inventory.getItemStack(), actor.inventory.currentItem);
        }
        Inventory(ItemStack[] player, ItemStack[] machine, ItemStack cursor, int selected) {
            if (player == null || player.length != 41 || selected < 0 || selected > 8
                    || machine == null || machine.length < 1 || machine.length > 256)
                throw changed("Complete bounded native player/machine inventories are required.");
            this.selected = selected; this.player = copy(player); this.machine = copy(machine); this.cursor = cursor == null ? null : cursor.copy();
            int chars = 0;
            for (ItemStack[] slots : new ItemStack[][] {player, machine, new ItemStack[] {cursor}}) for (ItemStack stack : slots) {
                if (stack != null && (stack.stackSize < 0 || stack.getItem() == null)) throw changed("Invalid actual native stack.");
                if (stack != null && stack.hasTagCompound()) chars = Math.addExact(chars, stack.getTagCompound().toString().length());
                if (chars > 262144) throw new BridgeException("result_too_large", "Complete native NBT accounting exceeds its bounded copy policy.");
            }
        }
        private static ItemStack[] copy(IInventory inventory) {
            int size = inventory.getSizeInventory();
            if (size < 1 || size > 256) throw changed("Complete native inventory copy exceeds its bounded slot policy.");
            ItemStack[] result = new ItemStack[size];
            for (int i = 0; i < result.length; i++) result[i] = inventory.getStackInSlot(i);
            return result;
        }
        private static ItemStack[] copy(ItemStack[] inventory) {
            ItemStack[] result = new ItemStack[inventory.length];
            for (int i = 0; i < result.length; i++) { ItemStack stack = inventory[i]; result[i] = stack == null ? null : stack.copy(); }
            return result;
        }
        boolean matches(Inventory before, Request request, int phase) {
            if (selected != before.selected || player.length != before.player.length || machine.length != before.machine.length) return false;
            ItemStack moved = request.fromPlayer() ? before.player[request.playerIndex] : before.machine[request.machineIndex];
            for (int i = 0; i < player.length; i++) {
                ItemStack expected = i == request.playerIndex && phase > 0 ? (request.fromPlayer() ? null : phase == 2 ? moved : before.player[i]) : before.player[i];
                if (!ItemStack.areItemStacksEqual(expected, player[i])) return false;
            }
            for (int i = 0; i < machine.length; i++) {
                ItemStack expected = i == request.machineIndex && phase > 0 ? (!request.fromPlayer() ? null : phase == 2 ? moved : before.machine[i]) : before.machine[i];
                if (!ItemStack.areItemStacksEqual(expected, machine[i])) return false;
            }
            return ItemStack.areItemStacksEqual(phase == 1 ? moved : null, cursor);
        }
    }

    public static final class Side implements View {
        final World world;
        final EntityPlayer actor;
        final Container container;
        final Object meta, tile;
        final Slot source, destination;
        final Inventory inventory;
        final JsonObject owner;
        final double x, y, z;
        final float yaw, pitch;
        final boolean creative, flying, sneaking, grounded, noClip;
        public Side(Request request, World world, EntityPlayer actor, Container container, MachineSlotBinding.Context binding, double reach, boolean server) {
            this.world = world; this.actor = actor; this.container = container;
            if (world == null || actor == null || actor.isDead || !Float.isFinite(actor.getHealth()) || actor.getHealth() <= 0 || actor.ridingEntity != null || actor.riddenByEntity != null
                    || world.provider == null || actor.dimension != request.dimension || world.provider.dimensionId != request.dimension
                    || container == null || !CONTAINER.equals(container.getClass().getName()) || actor.openContainer != container
                    || container.windowId != request.window || container.inventorySlots.size() > 512 || binding.owner() == null)
                throw changed("The exact living actor, world and public normal machine window are unavailable.");
            owner = binding.owner(); if (!owner.equals(request.owner)) throw changed("The exact actual native machine owner changed.");
            meta = binding.meta(); tile = world.getTileEntity(owner.get("x").getAsInt(), owner.get("y").getAsInt(), owner.get("z").getAsInt());
            if (!(meta instanceof IInventory) || tile == null || ((TileEntity) tile).isInvalid()) throw changed("The actual native machine inventory is unavailable.");
            source = slot(request.source); destination = slot(request.destination);
            bind(request, binding, source, request.fromPlayer()); bind(request, binding, destination, !request.fromPlayer());
            if (!Boolean.FALSE.equals(ExpandedGuiApi.call(source, "isPhantom")) || !Boolean.FALSE.equals(ExpandedGuiApi.call(destination, "isPhantom"))
                    || !Boolean.TRUE.equals(ExpandedGuiApi.call(source, "isCanTake")) || !Boolean.TRUE.equals(ExpandedGuiApi.call(destination, "isCanPut")))
                throw changed("Both actual native slots must be non-phantom and expose the required native take/put permissions.");
            inventory = new Inventory(actor, (IInventory) meta); x = actor.posX; y = actor.posY; z = actor.posZ;
            yaw = actor.rotationYaw; pitch = actor.rotationPitch; creative = actor.capabilities.isCreativeMode; flying = actor.capabilities.isFlying; sneaking = actor.isSneaking();
            grounded = actor.onGround; noClip = actor.noClip;
            Vec3 eye = server ? SurvivalConstructionAdapter.serverEye(x, y, z, actor.getEyeHeight()) : actor.getPosition(1F);
            Vec3 look = actor.getLook(1F);
            int tx = owner.get("x").getAsInt(), ty = owner.get("y").getAsInt(), tz = owner.get("z").getAsInt();
            NativePlacementAdapter.Access access = NativePlacementAdapter.access(world, request.dimension);
            if (!Double.isFinite(reach) || reach <= 0 || reach > 8 || !access.loaded(tx, ty, tz)
                    || NativePlacementAdapter.distanceToBlock(eye, tx, ty, tz) > reach) throw changed("The actual owner must remain loaded and within normal native reach.");
            Vec3 end = Vec3.createVectorHelper(eye.xCoord + look.xCoord * reach, eye.yCoord + look.yCoord * reach, eye.zCoord + look.zCoord * reach);
            if (!NativePlacementAdapter.loadedRay(access, eye, end, ty)) throw changed("The current native owner ray crosses an unloaded neighbour halo.");
            MovingObjectPosition hit = access.trace(eye, end);
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK || hit.blockX != tx || hit.blockY != ty || hit.blockZ != tz || hit.sideHit != request.side)
                throw changed("The current normal native ray must hit the exact opened machine face.");
        }
        private Slot slot(int index) {
            if (index >= container.inventorySlots.size() || !(container.inventorySlots.get(index) instanceof Slot)) throw changed("Native slot coverage changed.");
            Slot slot = (Slot) container.inventorySlots.get(index);
            if (slot.slotNumber != index || ExpandedGuiApi.call(container, "getModularSlot", index) != slot) throw changed("Native slot identity differs from its current container index.");
            return slot;
        }
        private void bind(Request request, MachineSlotBinding.Context binding, Slot slot, boolean player) {
            JsonObject proof = new JsonObject(); PlayerSlotBinding.describe(proof, slot, actor.inventory); binding.describe(proof, slot);
            String index = player ? "playerInventoryIndex" : "machineInventoryIndex";
            if (!booleanValue(proof, player ? "playerInventoryBindingVerified" : "machineInventoryBindingVerified", true)
                    || !proof.has(index) || proof.get(index).getAsInt() != (player ? request.playerIndex : request.machineIndex)
                    || booleanValue(proof, player ? "machineInventoryBindingVerified" : "playerInventoryBindingVerified", true)) throw changed("The actual source/destination backing inventory binding changed.");
            if (player) {
                int matches = 0;
                for (Object entry : container.inventorySlots) {
                    if (!(entry instanceof Slot)) throw changed("Every native window member must be a real Slot.");
                    if (((Slot) entry).isHere(actor.inventory, request.playerIndex)) matches++;
                }
                if (matches != 1) throw changed("The actual player backing slot needs one unique public native binding.");
            }
        }
        public Inventory inventory() { return inventory; }
        public void same(View previous) {
            if (!(previous instanceof Side)) throw changed("The actual native observation domain changed.");
            Side before = (Side) previous;
            if (world != before.world || actor != before.actor || container != before.container || meta != before.meta || tile != before.tile
                    || source != before.source || destination != before.destination || !owner.equals(before.owner)
                    || x != before.x || y != before.y || z != before.z || yaw != before.yaw || pitch != before.pitch
                    || creative != before.creative || flying != before.flying || sneaking != before.sneaking || grounded != before.grounded || noClip != before.noClip)
                throw changed("The actual actor, pose, machine, container or native slot changed during transfer.");
        }
        public void initial(Request request) {
            if (inventory.cursor != null || destination.getStack() != null || !source.canTakeStack(actor)) throw changed("A whole-stack transfer requires an empty cursor/destination and an actually takeable source.");
            request.expected(source.getStack());
            if (request.count > source.getStack().getMaxStackSize() || request.count > destination.getSlotStackLimit()) throw changed("The destination cannot hold the entire source stack.");
            ItemStack actual = request.fromPlayer() ? inventory.player[request.playerIndex] : inventory.machine[request.machineIndex];
            if (!ItemStack.areItemStacksEqual(actual, source.getStack())) throw changed("The actual native source differs from its bound complete inventory.");
            if ((request.fromPlayer() ? inventory.machine[request.machineIndex] : inventory.player[request.playerIndex]) != null) throw changed("The actual bound destination inventory is not empty.");
        }
    }

    static final class State {
        final GameQueue.Control control;
        final LongSupplier clock;
        final long started, deadline;
        volatile int dispatched, completed;
        volatile String stage = "client_before";
        final JsonObject receipt = new JsonObject();
        State(GameQueue.Control control, LongSupplier clock) {
            this.control = control; this.clock = clock; started = clock.getAsLong(); deadline = started + TimeUnit.MILLISECONDS.toNanos(8000);
            receipt.addProperty("clientCursorObserved", false); receipt.addProperty("serverCursorObserved", false);
            receipt.addProperty("cursorObservationScope", "last_observed_native");
        }
        void observed(String domain, Inventory inventory) {
            receipt.addProperty(domain + "CursorObserved", true); receipt.addProperty(domain + "CursorStage", stage);
            receipt.add(domain + "Cursor", SurvivalConstructionAdapter.item(inventory.cursor));
        }
        long remaining() {
            if (Thread.currentThread().isInterrupted() || control.isCancelled()) throw new BridgeException("cancelled", "Whole-stack transfer was cancelled; no later native click will start.");
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong());
            if (left < 1) throw new BridgeException("timeout", "The bounded native whole-stack transfer expired; never replay a submitted click."); return left;
        }
    }
    public static final class Failure extends BridgeException {
        public final JsonObject details;
        Failure(State state, Throwable error) {
            super(error instanceof BridgeException ? ((BridgeException) error).code : error instanceof InterruptedException ? "cancelled" : "internal_error", error instanceof BridgeException ? error.getMessage() : "Native whole-stack transfer failed.");
            details = new JsonObject(); details.addProperty("stage", state.stage); details.addProperty("clicksDispatched", state.dispatched);
            details.addProperty("clicksCompleted", state.completed); details.addProperty("outcome_unknown", state.dispatched > 0);
            details.addProperty("automaticReplayAllowed", false); details.addProperty("cursorCleared", false);
            details.add("knownReceipt", new JsonParser().parse(state.receipt.toString()));
        }
    }
    public static GuardedContext.Result dispatch(GameQueue client, GameQueue server, GameQueue.Control control, Request request, Access access, GuardedContext guard) {
        State state = new State(control, System::nanoTime);
        try {
            final JsonObject before = queued(client, state, access::snapshot); guard.check(before);
            final View clientBefore = queued(client, state, access::client); state.observed("client", clientBefore.inventory()); clientBefore.initial(request);
            state.stage = "server_before"; final View serverBefore = queued(server, state, access::server); state.observed("server", serverBefore.inventory()); serverBefore.initial(request);
            if (clientBefore.inventory().selected != serverBefore.inventory().selected) throw changed("Actual client/server selected slots differ before transfer.");
            for (int phase = 1; phase <= 2; phase++) {
                final int targetPhase = phase;
                state.stage = phase == 1 ? "pickup" : "place";
                queued(client, state, () -> {
                    View current = access.client(); current.same(clientBefore);
                    if (!current.inventory().matches(clientBefore.inventory(), request, targetPhase - 1)) throw changed("Actual client inventory/cursor changed before the next native click.");
                    JsonObject currentBefore = access.snapshot(); guard.wholeStackMutationStarting(currentBefore);
                    state.dispatched++; access.click(targetPhase == 1 ? request.source : request.destination); state.completed++;
                    View predicted = access.client(); predicted.same(clientBefore);
                    state.observed("client", predicted.inventory());
                    if (!predicted.inventory().matches(clientBefore.inventory(), request, targetPhase)) throw changed("The native click did not predict the exact full-stack transition; stop without replay.");
                    return null;
                });
                boolean confirmed = false;
                for (int poll = 0; poll < MAX_POLLS && !confirmed; poll++) {
                    state.stage = targetPhase == 1 ? "server_pickup_confirmation" : "server_place_confirmation";
                    confirmed = queued(server, state, () -> {
                        View current = access.server(); current.same(serverBefore);
                        state.observed("server", current.inventory());
                        if (current.inventory().matches(serverBefore.inventory(), request, targetPhase)) return true;
                        if (!current.inventory().matches(serverBefore.inventory(), request, targetPhase - 1)) throw changed("Actual server inventory/cursor differs from both exact native transition states.");
                        return false;
                    });
                    queued(client, state, () -> { View current = access.client(); current.same(clientBefore); state.observed("client", current.inventory());
                        if (!current.inventory().matches(clientBefore.inventory(), request, targetPhase)) throw changed("Current native client state changed while awaiting real server acceptance."); return null; });
                }
                if (!confirmed) throw new BridgeException("timeout", "The native transaction did not receive real server inventory confirmation within bounded normal ticks.");
                state.receipt.addProperty(targetPhase == 1 ? "serverPickupConfirmed" : "serverPlaceConfirmed", true);
            }
            state.stage = "final_client";
            return queued(client, state, () -> {
                View current = access.client(); current.same(clientBefore);
                if (!current.inventory().matches(clientBefore.inventory(), request, 2)) throw changed("Final real client inventory/cursor changed before completion.");
                guard.check(access.snapshot()); state.remaining(); JsonObject result = state.receipt;
                result.addProperty("version", 1); result.addProperty("operation", OPERATION); result.addProperty("status", "completed");
                result.addProperty("sessionId", request.session); result.addProperty("screenId", request.screen); result.addProperty("windowId", request.window);
                result.addProperty("direction", request.direction); result.add("owner", new JsonParser().parse(request.owner.toString()));
                result.addProperty("sourceSlot", request.source); result.addProperty("destinationSlot", request.destination); result.addProperty("playerInventoryIndex", request.playerIndex); result.addProperty("machineInventoryIndex", request.machineIndex);
                result.addProperty("count", request.count); result.addProperty("clicksDispatched", state.dispatched); result.addProperty("clicksCompleted", state.completed);
                result.addProperty("serverConfirmed", true); result.addProperty("allPlayerSlots", 41); result.addProperty("allMachineSlots", serverBefore.inventory().machine.length);
                result.addProperty("fullNbtConservation", true); result.addProperty("unrelatedSlotsPreserved", true); result.addProperty("selectedHotbarSlot", current.inventory().selected);
                result.addProperty("cursorEmpty", true); result.addProperty("outcomeUnknown", false); result.addProperty("automaticReplayAllowed", false);
                result.addProperty("elapsedMillis", Math.max(0, state.clock.getAsLong() - state.started) / 1000000.0);
                return guard.result(result, before);
            });
        } catch (ExecutionException error) { throw new Failure(state, error.getCause() == null ? error : error.getCause()); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Failure(state, error); }
        catch (RuntimeException error) { throw error instanceof Failure ? error : new Failure(state, error); }
    }
    private static <T> T queued(GameQueue queue, State state, Callable<T> action) throws ExecutionException, InterruptedException {
        long remaining = state.remaining(); return queue.await(queue.submit(() -> { state.remaining(); return action.call(); }, remaining, state.control), remaining);
    }
    private static JsonObject object(JsonObject value, String key) { JsonElement entry = value.get(key); if (entry == null || !entry.isJsonObject()) throw bad("Expected object: " + key); return entry.getAsJsonObject(); }
    private static String text(JsonObject value, String key) { String text = JsonData.string(value, key); if (text.isEmpty() || text.length() > 256) throw bad("Invalid bounded identity: " + key); return text; }
    private static boolean booleanValue(JsonObject value, String key, boolean expected) { JsonElement entry = value.get(key); return entry != null && entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isBoolean() && entry.getAsBoolean() == expected; }
    private static void keys(JsonObject value, String... names) { Set<String> allowed = new HashSet<String>(Arrays.asList(names)); for (java.util.Map.Entry<String, JsonElement> entry : value.entrySet()) if (!allowed.contains(entry.getKey())) throw bad("Unknown whole-stack field: " + entry.getKey()); }
    private static BridgeException bad(String message) { return new BridgeException("invalid_args", message); }
    private static BridgeException changed(String message) { return new BridgeException("whole_stack_context_changed", message); }
}
