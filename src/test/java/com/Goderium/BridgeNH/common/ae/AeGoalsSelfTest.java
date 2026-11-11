package com.Goderium.BridgeNH.common.ae;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.common.machine.MachineDataAdapter;
import com.Goderium.BridgeNH.common.structure.NativePartPlacement;
import com.Goderium.BridgeNH.common.structure.PlacementAttachment;
import com.Goderium.BridgeNH.util.BridgeException;

import appeng.client.gui.implementations.GuiPriority;
import appeng.container.implementations.ContainerCraftConfirm;
import appeng.container.implementations.ContainerMEMonitorable;
import appeng.container.implementations.ContainerPriority;
import appeng.container.implementations.CraftingCPUStatus;
import appeng.core.sync.packets.PacketValueConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;

/** Real receipt/state-machine tests around explicit native container fixtures, without a game process. */
public final class AeGoalsSelfTest {
    private static int checks;
    public static int run() {
        checks = 0; portable(); priority(); mounting(); publicNetworks(); dataAccess(); waitArguments(); order(false, "complete"); order(true, "complete");
        for (String outcome : new String[] {"cancel", "replace", "destroy", "grid", "missing", "mismatch", "linked"}) order(false, outcome);
        return checks;
    }
    private static void portable() {
        Player player = allocate(Player.class); player.worldObj = allocate(LoadedWorld.class);
        try { Field provider = World.class.getDeclaredField("provider"); provider.setAccessible(true); provider.set(player.worldObj, new WorldProviderSurface()); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        player.inventory = new InventoryPlayer(player);
        PoweredTerminal tool = new PoweredTerminal(); Item.itemRegistry.addObject(31998, "fixture:wireless", tool);
        ItemStack item = new ItemStack(tool, 1); item.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
        item.getTagCompound().setDouble("charge", 50); item.getTagCompound().setString("network", "A");
        player.inventory.setInventorySlotContents(3, item);
        PortableHost host = new PortableHost(item); appeng.container.AEBaseContainer container = new appeng.container.AEBaseContainer();
        container.owner = host; container.playerInv = player.inventory;
        JsonObject owner = AeConfiguration.owner(container);
        check(owner.get("kind").getAsString().equals("portable") && owner.get("playerSlot").getAsInt() == 3
                && owner.get("networkKey").getAsString().equals("A"), "Portable owner derives player, actual slot and native wireless binding.");
        ItemStack before = item.copy(); tool.extractAEPower(item, 7.5);
        check(owner.equals(AeConfiguration.owner(container)), "Ordinary power consumption does not invalidate a portable owner.");
        check(AePortableTerminals.energyDebit(before, item).get("debitedAE").getAsDouble() == 7.5,
                "Only exact full NBT reproduced by the native item energy API permits an energy debit.");
        item.getTagCompound().setString("network", "B");
        check(!owner.equals(AeConfiguration.owner(container)) && AePortableTerminals.energyDebit(before, item) == null, "Changed wireless network cannot hide behind a legitimate charge debit.");
        item.getTagCompound().setString("network", "A"); item.getTagCompound().setDouble("charge", 60);
        check(AePortableTerminals.energyDebit(before, item) == null, "Unexpected charge increases cannot pass delivery conservation.");
        player.inventory.setInventorySlotContents(3, item.copy());
        check(AeConfiguration.owner(container).get("playerSlot").getAsInt() == 3, "Native client slot synchronization may replace a stack with an exact full-NBT copy.");
        player.inventory.getStackInSlot(3).getTagCompound().setString("search", "actual native GUI setting");
        player.inventory.getStackInSlot(3).getTagCompound().setDouble("charge", 59);
        check(AeConfiguration.owner(container).get("networkKey").getAsString().equals("A"), "Native mutable GUI settings/charge do not change logical portable player-slot-network ownership.");
        player.inventory.getStackInSlot(3).getTagCompound().setString("network", "changed");
        expect("owner_changed", () -> AeConfiguration.owner(container));
    }
    public static class PortableHost implements appeng.api.implementations.guiobjects.IGuiItemObject, appeng.container.interfaces.IInventorySlotAware {
        final ItemStack item; PortableHost(ItemStack item) { this.item = item; }
        public ItemStack getItemStack() { return item; } public int getInventorySlot() { return 3; }
    }
    public static class PoweredTerminal extends Item implements appeng.api.features.IWirelessTermHandler, appeng.api.implementations.items.IAEItemPowerStorage {
        public String getEncryptionKey(ItemStack item) { return item.getTagCompound().getString("network"); }
        public double getAECurrentPower(ItemStack item) { return item.getTagCompound().getDouble("charge"); }
        public double extractAEPower(ItemStack item, double amount) { double debit = Math.min(getAECurrentPower(item), amount); item.getTagCompound().setDouble("charge", getAECurrentPower(item) - debit); return debit; }
    }
    private static void publicNetworks() {
        NetworkHost host = new NetworkHost();
        JsonObject part = AeWorldAdapter.network(host, 3);
        check(part.get("complete").getAsBoolean() && part.getAsJsonArray("nodes").size() == 1,
                "A part exposes one public node, without scanning or editing the network.");
        JsonObject node = part.getAsJsonArray("nodes").get(0).getAsJsonObject();
        check(node.get("side").getAsInt() == 6 && node.get("gridId").getAsString().equals("public-grid-A"),
                "Part node identity uses its public grid ID and explicit UNKNOWN side.");
        host.node.active = false;
        check(!AeWorldAdapter.network(host, 3).getAsJsonArray("nodes").get(0).getAsJsonObject().get("active").getAsBoolean(),
                "Offline activity is reported separately from membership.");
        JsonArray sides = AeWorldAdapter.network(host, -1).getAsJsonArray("nodes");
        check(sides.size() == 7 && !sides.get(0).getAsJsonObject().get("available").getAsBoolean()
                && sides.get(6).getAsJsonObject().get("available").getAsBoolean(),
                "A block exposes exact side nodes; a missing node never proves isolation.");
        host.node.grid = null;
        check(!AeWorldAdapter.network(host, 3).getAsJsonArray("nodes").get(0).getAsJsonObject().get("available").getAsBoolean(),
                "Absent live grid produces unavailable evidence.");
        check(!AeWorldAdapter.network(new Object(), 3).get("complete").getAsBoolean(),
                "Unsupported public node API remains explicitly incomplete.");
    }
    public static class NetworkHost {
        public final NetworkNode node = new NetworkNode();
        public Object getGridNode() { return node; }
        public Object getGridNode(net.minecraftforge.common.util.ForgeDirection side) {
            return side == net.minecraftforge.common.util.ForgeDirection.UNKNOWN ? node : null;
        }
    }
    public static class NetworkNode {
        public NetworkGrid grid = new NetworkGrid();
        public boolean active = true;
        public Object getGrid() { return grid; }
        public boolean isActive() { return active; }
    }
    public static class NetworkGrid { public String getId() { return "public-grid-A"; } }
    public static class DataAccessMulti extends gregtech.api.metatileentity.implementations.MTEMultiBlockBase {
        public final List<Object> mDataAccessHatches = new ArrayList<Object>();
    }
    private static void dataAccess() {
        gregtech.api.metatileentity.BaseMetaTileEntity tile = new gregtech.api.metatileentity.BaseMetaTileEntity();
        DataAccessMulti meta = new DataAccessMulti(); tile.meta = meta; meta.mDataAccessHatches.add(null);
        JsonObject components = MachineDataAdapter.read(null, tile, new JsonObject(), System.nanoTime() + 60000000000L)
                .getAsJsonObject("components");
        JsonObject entry = components.getAsJsonArray("entries").get(0).getAsJsonObject();
        check(entry.get("role").getAsString().equals("data_access") && entry.get("roleIndex").getAsInt() == 0,
                "The current public subclass data-access collection retains its native role index.");
        check(!entry.get("valid").getAsBoolean() && entry.get("reason").getAsString().equals("null_registered_entry"),
                "Data-access membership never upgrades an invalid component to accepted formation.");
    }
    private static void waitArguments() {
        JsonObject status = request("status");
        check(AeOrderAdapter.waitMillis(status) == 0, "Existing status reads remain immediate.");
        status.addProperty("waitMillis", 5000);
        check(AeOrderAdapter.waitMillis(status) == 5000, "Status has a bounded native wait window.");
        for (int invalid : new int[] {-1, 5001}) {
            status.addProperty("waitMillis", invalid); expect("invalid_args", () -> AeOrderAdapter.waitMillis(status));
        }
        for (String action : new String[] {"start", "take"}) {
            JsonObject mutation = request(action); mutation.addProperty("waitMillis", 0);
            expect("invalid_args", () -> new AeOrderAdapter().perform(null, mutation));
        }
    }
    private static void priority() {
        ContainerPriority container = new ContainerPriority(); container.priorityValue = 4;
        JsonObject args = new JsonObject(); args.addProperty("screenId", "s"); args.addProperty("action", "priority_set"); args.addProperty("priority", -123);
        List<Object> packets = new ArrayList<Object>();
        JsonObject receipt = AeControlAdapter.perform(new GuiPriority(), container, null, null, args, packets::add);
        PacketValueConfig packet = (PacketValueConfig) packets.get(0);
        check(packet.key.equals("PriorityHost.Priority") && packet.value.equals("-123") && packets.size() == 1, "Priority is one native server packet, including negative values.");
        check(container.priorityValue == 4 && !receipt.get("serverConfirmed").getAsBoolean(), "Client priority prediction never certifies persistence.");
        expect("unsupported", () -> AeControlAdapter.perform(new GuiPriority(), new ContainerMEMonitorable(), null, null, args, packets::add));
        check(packets.size() == 1, "Wrong owner container sends no packet.");
    }
    private static void mounting() {
        net.minecraft.util.Vec3 eye = net.minecraft.util.Vec3.createVectorHelper(1.5, 64.5, 2);
        net.minecraft.util.MovingObjectPosition face = new net.minecraft.util.MovingObjectPosition(1, 64, 0, 3,
                net.minecraft.util.Vec3.createVectorHelper(1.5, 64.5, 1));
        NativePartPlacement.requireRay(face, face, eye, 4.5);
        checks++;
        expect("part_view_changed", () -> NativePartPlacement.requireRay(face, null, eye, 4.5));
        expect("part_view_changed", () -> NativePartPlacement.requireRay(face, face, eye, .5));
        net.minecraft.util.MovingObjectPosition edge = new net.minecraft.util.MovingObjectPosition(1, 64, 0, 2, face.hitVec);
        expect("part_view_changed", () -> NativePartPlacement.requireRay(face, edge, eye, 4.5));
        net.minecraft.util.MovingObjectPosition other = new net.minecraft.util.MovingObjectPosition(2, 64, 0, 3, face.hitVec);
        expect("part_view_changed", () -> NativePartPlacement.requireRay(face, other, eye, 4.5));
        JsonObject part = new JsonObject(); part.addProperty("side", 5); part.addProperty("registryName", "ae:part"); part.addProperty("metadata", 1);
        JsonObject target = new JsonObject(); target.addProperty("air", false); JsonArray parts = new JsonArray(); target.add("parts", parts);
        NativePartPlacement.vacant(target, part);
        check(!NativePartPlacement.present(target, part), "Vacancy is not a successful part placement.");
        parts.add(part);
        expect("invalid_state", () -> NativePartPlacement.vacant(target, part));
        check(NativePartPlacement.present(target, part), "Only exact registry/meta/mounted side proves placement.");
        JsonObject different = new com.google.gson.JsonParser().parse(part.toString()).getAsJsonObject(); different.addProperty("metadata", 2);
        check(!NativePartPlacement.present(target, different), "Another variant in the same mount never satisfies expectedPart.");
        JsonObject binding = new JsonObject(); binding.addProperty("version", 1); binding.addProperty("x", 1); binding.addProperty("y", 64); binding.addProperty("z", 2);
        binding.addProperty("dimension", 0); binding.addProperty("side", 5); binding.addProperty("registryName", "ae:host"); binding.addProperty("metadata", 0);
        JsonObject args = new JsonObject(); args.add("attachmentBinding", binding);
        new PlacementAttachment(args, 2, 64, 2, 0);
        expect("invalid_args", () -> new PlacementAttachment(args, 1, 64, 2, 0));
        new PlacementAttachment(args, 1, 64, 2, 0, true);
        expect("invalid_args", () -> new PlacementAttachment(args, 2, 64, 2, 0, true));
    }
    private static void order(boolean partial, String outcome) {
        Player player = allocate(Player.class); player.worldObj = allocate(LoadedWorld.class);
        try { Field provider = World.class.getDeclaredField("provider"); provider.setAccessible(true); provider.set(player.worldObj, new WorldProviderSurface()); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        player.inventory = new InventoryPlayer(player);
        Host host = new Host(); host.setWorldObj(player.worldObj); host.xCoord = 1; host.yCoord = 64; host.zCoord = 2;
        ItemStack item = new ItemStack(new Item(), 3, 7);
        net.minecraft.nbt.NBTTagCompound tag = new net.minecraft.nbt.NBTTagCompound(); tag.setString("exact", "hidden-NBT"); item.setTagCompound(tag);
        Resource resource = new Resource(item, 3);
        ContainerCraftConfirm preview = new ContainerCraftConfirm(); preview.owner = host; preview.requested = resource;
        preview.windowId = 3; preview.serializedItemToCraft = "ready";
        Cpu cpu = new Cpu(); cpu.grid = host.node.grid; CraftingCPUStatus selected = new CraftingCPUStatus(); selected.serverCluster = cpu; preview.cpuTable.cpus.add(selected);
        int[] starts = {0}; preview.onStart = () -> { starts[0]++; cpu.link = new Link(); };
        player.openContainer = preview;
        AeOrderAdapter adapter = new AeOrderAdapter(); JsonObject start = request("start"); start.addProperty("windowId", 3);
        start.add("owner", AeConfiguration.owner(preview)); start.addProperty("amount", 3); start.addProperty("selectedCpuSerial", 7);
        start.addProperty("identityNbt", AeDataAdapter.identity(resource).toString());
        check(adapter.perform(player, start).get("state").getAsString().equals("running"), "Native start retains the actual new crafting link.");
        adapter.perform(player, start);
        check(starts[0] == 1, "Duplicate request IDs do not execute startJob twice.");
        Link original = cpu.link;
        check(adapter.perform(player, request("status")).get("state").getAsString().equals("running"), "An idle CPU with its original link is not completion.");
        AeOrderAdapter.StatusWait timed = adapter.statusWait(player, request("status"));
        check(!await(timed, 1), "An expired read window does not complete a running craft."); timed.close();
        AeOrderAdapter.StatusWait canceled = adapter.statusWait(player, request("status"));
        GameQueue.Control control = new GameQueue.Control(); control.onReadCancellation(canceled::cancel);
        check(control.cancelBeforeExecution(), "The existing pure-read cancellation contract applies to status waits.");
        waitFailure(canceled, "cancelled");
        check(!original.canceled && starts[0] == 1, "Cancelling a reader never cancels or resubmits its native order.");
        AeOrderAdapter.StatusWait invalidated = adapter.statusWait(player, request("status"));
        adapter.invalidateWaits(); waitFailure(invalidated, "ae_context_changed");
        check(!original.canceled, "World/profile invalidation only detaches observers.");
        AeOrderAdapter.StatusWait signal = adapter.statusWait(player, request("status"));
        AeOrderAdapter.StatusWait second = adapter.statusWait(player, request("status"));
        expect("busy", () -> adapter.statusWait(player, request("status"))); second.close();
        if (outcome.equals("linked")) {
            original.standalone = false; original.done = true;
            check(adapter.perform(player, request("status")).get("state").getAsString().equals("crafted"), "Native non-standalone link completion remains supported.");
            check(await(signal, 1), "A fresh native link terminal read wakes the registered observer."); signal.close();
            return;
        }
        if (outcome.equals("cancel")) original.canceled = true;
        cpu.link = outcome.equals("replace") ? new Link() : null;
        cpu.destroyed = outcome.equals("destroy");
        if (outcome.equals("grid")) cpu.grid = new Grid();
        if (outcome.equals("complete") || outcome.equals("mismatch")) {
            java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CompletableFuture<Boolean> awakened = new java.util.concurrent.CompletableFuture<Boolean>();
            Thread reader = new Thread(() -> {
                entered.countDown();
                try { awakened.complete(signal.await(1000)); }
                catch (Exception failure) { awakened.completeExceptionally(failure); }
            }, "ae-status-read-test");
            reader.start();
            try {
                check(entered.await(1, java.util.concurrent.TimeUnit.SECONDS), "HTTP observer enters without blocking the owner thread.");
                cpu.listener.apply(resource, outcome.equals("mismatch") ? 4 : 3, 20L);
                check(awakened.get(1, java.util.concurrent.TimeUnit.SECONDS), "Native completion wakes the reader without any status poll.");
                reader.join(1000);
            } catch (Exception failure) { throw new AssertionError(failure); }
            finally { signal.close(); }
        }
        if (!outcome.equals("complete")) {
            check(adapter.perform(player, request("status")).get("state").getAsString().equals(outcome.equals("cancel") ? "canceled" : "start_unknown"),
                "Canceled, replaced, destroyed, disconnected, mismatched or missing completion events cannot authorize delivery: " + outcome);
            check(await(signal, 1), "Final native refresh resolves uncertain/cancelled reads without claiming delivery."); signal.close();
            return;
        }
        check(!original.done, "Real GUI standalone links remain not-done after native completion.");
        check(adapter.perform(player, request("status")).get("completionEvidence").getAsString().equals("native_craft_complete_listener"), "The same public event as AE notifications proves completion.");
        AeOrderAdapter.StatusWait ready = adapter.statusWait(player, request("status"));
        check(await(ready, 1), "Already terminal orders return immediately without a lost wakeup."); ready.close();
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            new java.io.ObjectOutputStream(bytes).writeObject(cpu.listener);
            appeng.api.util.CraftCompleteListener restored = (appeng.api.util.CraftCompleteListener)
                new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())).readObject();
            restored.apply(resource, 3, 20L);
            check(bytes.size() < 2048, "AE listener persistence contains no runtime world, CPU, inventory or receipt.");
        } catch (Exception error) { throw new AssertionError(error); }
        // Replacing the CPU's last link must not change this order's retained link.
        cpu.link = new Link();
        check(adapter.perform(player, request("status")).get("state").getAsString().equals("crafted"), "Another CPU job cannot replace the retained order identity.");
        ContainerMEMonitorable terminal = new ContainerMEMonitorable(); terminal.owner = host; terminal.windowId = 4;
        terminal.append(new Slot(player.inventory, 2, 0, 0)); player.openContainer = terminal;
        JsonObject take = request("take"); take.addProperty("windowId", 4); take.add("owner", AeConfiguration.owner(terminal));
        player.inventory.setInventorySlotContents(2, item.copy());
        expect("inventory_full", () -> adapter.perform(player, take));
        check(terminal.pickups == 0, "Full destination refuses before native pickup.");
        player.inventory.setInventorySlotContents(2, null);
        if (partial) terminal.stopAfter = 1;
        if (partial) {
            expect("delivery_partial", () -> adapter.perform(player, take));
            check(adapter.perform(player, take).get("state").getAsString().equals("take_unknown") && terminal.pickups == 2,
                "Partial cursor pickup never repeats or reports successful delivery.");
            check(player.inventory.getItemStack().stackSize == 1, "Partial pickup remains on the real cursor for inspection.");
        } else {
            JsonObject result = adapter.perform(player, take);
            check(result.get("state").getAsString().equals("completed") && result.getAsJsonObject("delivery").get("fullNbtConservation").getAsBoolean(),
                "Native pickup and slotClick confirm exact inventory count and all full NBT.");
            check(ItemStack.areItemStacksEqual(item, player.inventory.getStackInSlot(2)) && player.inventory.getItemStack() == null,
                "Delivery preserves exact item metadata and tag.");
            check(player.cursorUpdates == 1 && player.announcedCursor == null
                    && result.getAsJsonObject("delivery").get("cursorSyncSubmitted").getAsBoolean(),
                "Direct server slotClick explicitly announces the empty cursor using vanilla updateHeldItem.");
            adapter.perform(player, take); check(terminal.pickups == 3, "Repeated delivery returns its receipt without taking again.");
            check(player.cursorUpdates == 1, "Duplicate delivery does not resubmit cursor or inventory actions.");
        }
        player.worldObj = allocate(LoadedWorld.class);
        expect("stale_order", () -> adapter.perform(player, request("status")));
        expect("stale_order", () -> adapter.statusWait(player, request("status")));
    }
    private static boolean await(AeOrderAdapter.StatusWait wait, long millis) {
        try { return wait.await(millis); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static void waitFailure(AeOrderAdapter.StatusWait wait, String code) {
        try { wait.await(1); throw new AssertionError("Expected " + code); }
        catch (java.util.concurrent.ExecutionException failure) {
            check(failure.getCause() instanceof BridgeException && ((BridgeException) failure.getCause()).code.equals(code), "Read wait fails with " + code);
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
        finally { wait.close(); }
    }
    private static JsonObject request(String action) {
        JsonObject args = new JsonObject(); args.addProperty("requestId", "bound-order"); args.addProperty("action", action); return args;
    }
    public static class Resource extends AeGuiFixtures.Stack {
        Resource(ItemStack item, int amount) { super(new AeGuiFixtures.Type("item"), item, "full-NBT", amount); }
        public ItemStack getItemStack() { return item.copy(); }
        @Override public Object copy() { return new Resource(item.copy(), (int) amount); }
    }
    public static class Host extends TileEntity {
        final Node node = new Node(); public Node getActionableNode() { return node; }
    }
    public static class Node {
        final Grid grid = new Grid(); public boolean isActive() { return true; } public Grid getGrid() { return grid; }
    }
    public static class Grid {
        public Grid getCache(Class<?> type) { return this; } public String getId() { return "actual-grid"; }
        public boolean hasPermission(EntityPlayerMP player, appeng.api.config.SecurityPermissions permission) { return true; }
    }
    public static class Cpu {
        Link link; Grid grid; boolean destroyed; appeng.api.util.CraftCompleteListener listener;
        public boolean isBusy() { return false; } public Link getLastCraftingLink() { return link; }
        public Grid getGrid() { return grid; } public boolean isDestroyed() { return destroyed; }
        public void addOnCompleteListener(appeng.api.util.CraftCompleteListener value) { listener = value; }
    }
    public static class Link {
        boolean done, canceled, standalone = true;
        public boolean isCanceled() { return canceled; } public boolean isDone() { return done; }
        public boolean isStandalone() { return standalone; } public String getCraftingID() { return "actual-job"; }
    }
    public static class Player extends EntityPlayerMP {
        int cursorUpdates; ItemStack announcedCursor;
        private Player() { super(null, null, null, null); }
        @Override public UUID getUniqueID() { return new UUID(0, 1); }
        @Override public void updateHeldItem() { cursorUpdates++; announcedCursor = ItemStack.copyItemStack(inventory.getItemStack()); }
    }
    private static class LoadedWorld extends World {
        private LoadedWorld() { super(null, "ae_goal_fixture", (WorldSettings) null, null, null); }
        @Override protected IChunkProvider createChunkProvider() { throw new AssertionError("No chunk creation"); }
        @Override protected int getRenderDistanceChunks() { throw new AssertionError("No rendering"); }
        @Override public Entity getEntityByID(int id) { throw new AssertionError("No entity lookup"); }
    }
    private static <T> T allocate(Class<T> type) {
        try {
            Class<?> unsafe = Class.forName("sun.misc.Unsafe"); Field field = unsafe.getDeclaredField("theUnsafe"); field.setAccessible(true);
            return type.cast(unsafe.getMethod("allocateInstance", Class.class).invoke(field.get(null), type));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void expect(String code, Runnable action) {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (BridgeException failure) { check(code.equals(failure.code), "Expected " + code + ", got " + failure.code); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
