package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/** Server-thread receipts for native AE container actions. No synthetic jobs or inventory writes. */
public final class AeOrderAdapter {
    public static final String OPERATION = "ae.order";
    static final int MAX_WAIT_MILLIS = 5000;
    private final Map<String, Order> orders = new LinkedHashMap<String, Order>();
    private final Map<StatusWait, Boolean> waits = new ConcurrentHashMap<StatusWait, Boolean>();

    public static int waitMillis(JsonObject args) {
        if (!args.has("waitMillis")) return 0;
        check("status".equals(JsonData.string(args, "action")), "invalid_args", "Only a read-only order status may wait.");
        return JsonData.integer(args, "waitMillis", 0, MAX_WAIT_MILLIS);
    }

    /** Server owner registers once; the HTTP worker awaits only a pure signal. */
    public StatusWait statusWait(EntityPlayerMP player, JsonObject args) {
        check("status".equals(JsonData.string(args, "action")), "invalid_args", "Only status may register a wait.");
        perform(player, args);
        check(waits.size() < 2, "busy", "At most two AE status readers may wait at once.");
        Order order = orders.get(JsonData.string(args, "requestId"));
        StatusWait wait = new StatusWait(order);
        order.waits.put(wait, true);
        waits.put(wait, true);
        if (!order.state.equals("running")) order.signal();
        return wait;
    }

    /** May run on the client thread; never reads a world, CPU or mutable order state. */
    public void invalidateWaits() {
        for (StatusWait wait : waits.keySet()) wait.cancel("ae_context_changed", "The world, profile, pause or living actor changed during AE status waiting.");
    }

    public final class StatusWait {
        private final Order order;
        private final CompletableFuture<Void> signal = new CompletableFuture<Void>();
        StatusWait(Order order) { this.order = order; }
        public boolean await(long millis) throws InterruptedException, ExecutionException {
            try { signal.get(Math.max(1, millis), TimeUnit.MILLISECONDS); return true; }
            catch (TimeoutException timeout) { return false; }
        }
        public void cancel() { cancel("cancelled", "Only the AE status reader was cancelled; the native order is unchanged."); }
        private void cancel(String code, String message) { signal.completeExceptionally(new BridgeException(code, message)); close(); }
        public void close() { waits.remove(this); order.waits.remove(this); }
    }

    /** AE persists its listeners. Never serialize a world, player, CPU or runtime receipt. */
    private static final class CompletionListener implements InvocationHandler, Serializable {
        private static final long serialVersionUID = 1L;
        private final transient Order order;
        CompletionListener(Order order) { this.order = order; }
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                if (method.getName().equals("equals")) return proxy == args[0];
                if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                return "MCB native AE completion observer";
            }
            if (order == null || !order.state.equals("running") || !method.getName().equals("apply")) return null;
            try {
                if (Boolean.TRUE.equals(ExpandedGuiApi.call(order.link, "isCanceled"))) order.state = "canceled";
                else if (!Boolean.TRUE.equals(ExpandedGuiApi.call(order.cpu, "isDestroyed"))
                        && ExpandedGuiApi.call(order.cpu, "getGrid") == order.grid
                        && ExpandedGuiApi.call(order.cpu, "getLastCraftingLink") == null
                        && ((Number) args[1]).longValue() == order.amount
                        && AeDataAdapter.identity(args[0]).equals(AeDataAdapter.identity(order.resource))) {
                    order.state = "crafted"; order.completionEvidence = "native_craft_complete_listener";
                } else { order.state = "start_unknown"; order.uncertainty = "completion_event_mismatch"; }
            } catch (RuntimeException unavailable) {
                // An observer failure must never interrupt AE's own completion/notification cleanup.
                order.state = "start_unknown"; order.uncertainty = "completion_event_unverified";
            } finally {
                order.signal();
            }
            return null;
        }
    }

    private static final class Order {
        final World world;
        final String player;
        final JsonObject owner, declaration;
        final Object resource, grid;
        final int amount;
        Object link, cpu;
        String completionEvidence, uncertainty;
        String state = "start_unknown";
        JsonObject delivery;
        final Map<StatusWait, Boolean> waits = new ConcurrentHashMap<StatusWait, Boolean>();
        void signal() { for (StatusWait wait : waits.keySet()) { wait.signal.complete(null); wait.close(); } }
        Order(EntityPlayerMP player, JsonObject args, Object resource, Object grid, int amount) {
            this.world = player.worldObj; this.player = player.getUniqueID().toString();
            this.declaration = new com.google.gson.JsonParser().parse(args.toString()).getAsJsonObject();
            this.owner = declaration.getAsJsonObject("owner");
            this.resource = ExpandedGuiApi.call(resource, "copy"); this.grid = grid; this.amount = amount;
        }
    }

    public JsonObject perform(EntityPlayerMP player, JsonObject args) {
        waitMillis(args); // Reject waiting mutations before reserving or executing anything.
        String id = ExpandedGuiApi.string(args, "requestId", 128);
        String action = ExpandedGuiApi.option(args, "action", null, "start", "status", "take");
        check(!id.isEmpty(), "invalid_args", "requestId must not be empty.");
        ExpandedGuiApi.keys(args, "expectedSessionId", "expectedWorld", "dimension", "requestId",
            "windowId", "owner", "amount", "identityNbt", "selectedCpuSerial", "waitMillis");
        Order order = orders.get(id);
        if (order != null) {
            check(order.world == player.worldObj && order.player.equals(player.getUniqueID().toString()),
                "stale_order", "Order belongs to another loaded world or player; it will not be replayed.");
            if (action.equals("start")) check(order.declaration.equals(args), "order_conflict", "Request ID is already bound to another declaration.");
        } else if (!action.equals("start")) throw new BridgeException("unknown_order", "No native order receipt exists for this request ID.");
        if (action.equals("start") && order == null) {
            check(orders.size() < 64, "order_capacity", "This bridge runtime retains at most 64 orders; no receipt is evicted or replayed.");
            Container container = current(player, args, "ContainerCraftConfirm");
            check(AeDataAdapter.readyPreview(container), "preview_not_ready", "The authoritative preview is not ready.");
            Object resource = ExpandedGuiApi.call(container, "getItemToCraft");
            int amount = JsonData.integer(args, "amount", 1, 64);
            check(Boolean.TRUE.equals(ExpandedGuiApi.call(resource, "isItem"))
                && ((Number) ExpandedGuiApi.call(resource, "getStackSize")).longValue() == amount
                && AeDataAdapter.identity(resource).toString().equals(ExpandedGuiApi.string(args, "identityNbt", 4096)),
                "preview_changed", "Authoritative preview resource or quantity changed.");
            ItemStack item = (ItemStack) ExpandedGuiApi.call(resource, "getItemStack");
            check(amount <= item.getMaxStackSize(), "order_too_large", "Exact pickup currently supports one native item stack per order.");
            Object grid = grid(container, player, "CRAFT");
            Object table = ExpandedGuiApi.field(container, AeDataAdapter.CONTAINER + "ContainerCraftConfirm", "cpuTable");
            check(((Number) ExpandedGuiApi.call(container, "getSelectedCpu")).intValue() == JsonData.integer(args, "selectedCpuSerial", 0, Integer.MAX_VALUE),
                "cpu_changed", "The selected preview CPU changed.");
            Object cpu = ExpandedGuiApi.call(ExpandedGuiApi.call(table, "getSelectedCPU"), "getServerCluster");
            check(!Boolean.TRUE.equals(ExpandedGuiApi.call(cpu, "isBusy")), "cpu_busy", "Order receipts require an idle CPU; merging into another job is not supported.");
            Object previous = ExpandedGuiApi.call(cpu, "getLastCraftingLink");
            order = new Order(player, args, resource, grid, amount);
            orders.put(id, order); // Reserve before the once-only native callback, including exceptional outcomes.
            try {
                ExpandedGuiApi.call(container, "startJob"); // Exact callback used by PacketValueConfig Terminal.Start.
            } finally {
                Object link = ExpandedGuiApi.call(cpu, "getLastCraftingLink");
                if (link != null && link != previous) {
                    order.link = link; order.cpu = cpu; order.state = "running";
                    Class<?> listener = ExpandedGuiApi.load(cpu, "appeng.api.util.CraftCompleteListener");
                    ExpandedGuiApi.call(cpu, "addOnCompleteListener", Proxy.newProxyInstance(
                        listener.getClassLoader(), new Class<?>[] {listener}, new CompletionListener(order)));
                }
            }
        }
        refresh(order);
        if (!order.state.equals("running")) order.signal();
        if (action.equals("take") && order.state.equals("crafted")) take(player, args, order);
        return receipt(id, order);
    }

    private static Container current(EntityPlayerMP player, JsonObject args, String type) {
        Container container = player.openContainer;
        AeDataAdapter.require(container, AeDataAdapter.CONTAINER + type);
        check(container.windowId == JsonData.integer(args, "windowId", 0, 255) && container.canInteractWith(player),
            "stale_container", "The expected native server window is no longer usable.");
        check(AeConfiguration.owner(container).equals(args.getAsJsonObject("owner")), "owner_changed", "Native AE owner changed.");
        return container;
    }

    private static Object grid(Object container, EntityPlayerMP player, String permission) {
        Object target = ExpandedGuiApi.call(container, "getTarget");
        Object node = ExpandedGuiApi.call(target, "getActionableNode");
        check(node != null && Boolean.TRUE.equals(ExpandedGuiApi.call(node, "isActive")), "ae_offline", "AE owner node is not active.");
        Object grid = ExpandedGuiApi.call(node, "getGrid");
        Object security = ExpandedGuiApi.call(grid, "getCache", ExpandedGuiApi.load(container, "appeng.api.networking.security.ISecurityGrid"));
        check(Boolean.TRUE.equals(ExpandedGuiApi.call(security, "hasPermission", player,
            ExpandedGuiApi.enumeration(container, "appeng.api.config.SecurityPermissions", permission))), "permission_denied", "Native AE security denied this operation.");
        return grid;
    }

    private static void refresh(Order order) {
        if (!order.state.equals("running")) return;
        if (Boolean.TRUE.equals(ExpandedGuiApi.call(order.link, "isCanceled"))) order.state = "canceled";
        else if (Boolean.TRUE.equals(ExpandedGuiApi.call(order.link, "isDone"))) {
            order.state = "crafted"; order.completionEvidence = "native_link_done";
        } else if (Boolean.TRUE.equals(ExpandedGuiApi.call(order.link, "isStandalone"))) {
            // AE GUI jobs have no requester/nexus. completeJob clears myLastLink, but markDone
            // does not update the retained standalone link. Bind the lifecycle to this exact CPU.
            Object current = ExpandedGuiApi.call(order.cpu, "getLastCraftingLink");
            if (Boolean.TRUE.equals(ExpandedGuiApi.call(order.cpu, "isDestroyed"))
                    || ExpandedGuiApi.call(order.cpu, "getGrid") != order.grid) {
                order.state = "start_unknown"; order.uncertainty = "bound_cpu_unavailable";
            } else if (current != null && current != order.link) {
                order.state = "start_unknown"; order.uncertainty = "bound_cpu_job_replaced";
            } else if (current == null && Boolean.FALSE.equals(ExpandedGuiApi.call(order.cpu, "isBusy"))) {
                // A vanished job without the native completion event is not enough to certify output.
                order.state = "start_unknown"; order.uncertainty = "completion_event_missing";
            }
        }
    }

    private static void take(EntityPlayerMP player, JsonObject args, Order order) {
        Container container = current(player, args, "ContainerMEMonitorable");
        check(order.owner.equals(AeConfiguration.owner(container)) && grid(container, player, "EXTRACT") == order.grid,
            "grid_changed", "Delivery terminal no longer belongs to the bound AE grid and owner.");
        check(player.inventory.getItemStack() == null, "cursor_occupied", "Exact delivery requires an empty cursor.");
        ItemStack type = (ItemStack) ExpandedGuiApi.call(order.resource, "getItemStack");
        type.stackSize = order.amount;
        Slot destination = null;
        for (Object value : container.inventorySlots) {
            Slot slot = (Slot) value;
            if (slot.inventory == player.inventory && slot.getSlotIndex() >= 0 && slot.getSlotIndex() < 36
                    && slot.getStack() == null && slot.isItemValid(type) && slot.getSlotStackLimit() >= order.amount) {
                destination = slot; break;
            }
        }
        check(destination != null, "inventory_full", "One empty usable player slot is required for exact delivery; nothing was taken.");
        ItemStack[] before = new ItemStack[player.inventory.getSizeInventory()];
        for (int i = 0; i < before.length; i++) before[i] = ItemStack.copyItemStack(player.inventory.getStackInSlot(i));
        order.state = "take_unknown"; // No retry after a partial native pickup or lost response.
        ExpandedGuiApi.call(container, "setTargetStack", order.resource);
        Object single = ExpandedGuiApi.enumeration(container, "appeng.helpers.MonitorableAction", "PICKUP_SINGLE");
        for (int i = 1; i <= order.amount; i++) {
            ExpandedGuiApi.call(container, "doMonitorableAction", single, -1, player);
            ItemStack expected = type.copy(); expected.stackSize = i;
            check(ItemStack.areItemStacksEqual(expected, player.inventory.getItemStack()), "delivery_partial", "Native pickup was partial; cursor and inventory were preserved for inspection, never replayed.");
        }
        container.slotClick(destination.slotNumber, 0, 0, player);
        check(player.inventory.getItemStack() == null, "delivery_partial", "Native slot click did not empty the cursor; no retry.");
        JsonObject energyDebit = null;
        int portableSlot = order.owner.has("playerSlot") ? order.owner.get("playerSlot").getAsInt() : -1;
        for (int i = 0; i < before.length; i++) {
            ItemStack expected = i == destination.getSlotIndex() ? type : before[i];
            ItemStack actual = player.inventory.getStackInSlot(i);
            boolean equal = ItemStack.areItemStacksEqual(expected, actual);
            if (!equal && i == portableSlot) energyDebit = AePortableTerminals.energyDebit(expected, actual);
            check(equal || i == portableSlot && energyDebit != null, "delivery_changed", "Full player inventory conservation could not be proven; no retry.");
        }
        container.detectAndSendChanges();
        // Direct server slotClick has no client click prediction. Native AE pickup syncs the
        // cursor separately; announce its final empty stack through the same vanilla API.
        player.updateHeldItem();
        order.delivery = new JsonObject(); order.delivery.addProperty("slot", destination.getSlotIndex());
        order.delivery.addProperty("amount", order.amount); order.delivery.addProperty("serverConfirmed", true);
        order.delivery.addProperty("fullNbtConservation", true); order.delivery.addProperty("allPlayerSlots", before.length + 1);
        order.delivery.addProperty("cursorEmpty", true);
        order.delivery.addProperty("cursorSyncSubmitted", true);
        if (energyDebit != null) { energyDebit.addProperty("playerSlot", portableSlot); order.delivery.add("portableEnergyDebit", energyDebit); }
        order.delivery.add("item", AeDataAdapter.actualItem(type)); order.state = "completed";
    }

    private static JsonObject receipt(String id, Order order) {
        JsonObject result = new JsonObject(); result.addProperty("requestId", id); result.addProperty("state", order.state);
        result.addProperty("source", "integrated_server"); result.addProperty("replayAllowed", false);
        result.addProperty("amount", order.amount); result.add("owner", order.owner);
        result.addProperty("gridId", String.valueOf(ExpandedGuiApi.call(order.grid, "getId")));
        if (order.link != null) result.addProperty("craftingId", String.valueOf(ExpandedGuiApi.call(order.link, "getCraftingID")));
        if (order.delivery != null) result.add("delivery", order.delivery);
        if (order.completionEvidence != null) result.addProperty("completionEvidence", order.completionEvidence);
        if (order.uncertainty != null) result.addProperty("uncertainty", order.uncertainty);
        return result;
    }

    private static void check(boolean condition, String code, String message) {
        if (!condition) throw new BridgeException(code, message);
    }
}
