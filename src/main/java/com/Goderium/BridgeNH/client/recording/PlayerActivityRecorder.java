package com.Goderium.BridgeNH.client.recording;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiCallbacks;
import com.Goderium.BridgeNH.client.gui.GuiControlAdapter;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.ForgeDirection;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Opt-in observation only. No key ownership, packets, world writes, or forced chunk loads. */
public final class PlayerActivityRecorder {
    private final String session;
    private final Function<GuiScreen, String> screenIdentity;
    final PlayerActivityLog log = new PlayerActivityLog();
    private volatile UUID actor;
    private World clientWorld;
    private long ticks;
    private GuiScreen screen;
    private volatile boolean guiVisible;
    private JsonObject lastPose, lastWidgets, lastScreen;
    private final Map<World, List<Watch>> watches = new IdentityHashMap<World, List<Watch>>();
    private final SlotObserver clientSlots = new SlotObserver(), serverSlots = new SlotObserver();

    public PlayerActivityRecorder(String session, Function<GuiScreen, String> screenIdentity) {
        this.session = session; this.screenIdentity = screenIdentity;
    }

    public JsonObject control(JsonObject args) {
        String action = JsonData.string(args, "action");
        if ("status".equals(action)) return status();
        if ("off".equals(action)) return log.stop("user_stopped");
        if (!"on".equals(action)) throw new BridgeException("invalid_args", "Expected record on, off or status.");
        if (!log.enabled()) {
            Minecraft client = Minecraft.getMinecraft();
            if (client.thePlayer == null || client.theWorld == null) throw new BridgeException("not_in_world", "Enter a world before starting a recording.");
            JsonObject context = pose(client.thePlayer); context.addProperty("worldName", client.theWorld.getWorldInfo().getWorldName());
            context.add("coverage", coverage());
            log.start(new java.io.File(client.mcDataDir, "mcb-recordings").toPath(), session, context);
            actor = client.thePlayer.getUniqueID(); clientWorld = client.theWorld;
            screen = null; lastPose = null; lastWidgets = null; lastScreen = null; guiVisible = client.currentScreen != null;
            clientSlots.reset(); serverSlots.reset();
            synchronized (watches) { watches.clear(); }
        }
        return status();
    }

    public JsonObject read(JsonObject args) { JsonObject result = log.read(args); result.add("coverage", coverage()); return result; }
    public JsonObject logStatus() { return log.status(); }
    public void shutdown() { log.shutdown(); }
    private JsonObject status() { JsonObject result = log.status(); result.add("coverage", coverage()); return result; }
    static JsonObject coverage() {
        JsonObject data = new JsonObject(); data.addProperty("movementSampleTicks", 4);
        data.addProperty("guiStateSampleTicks", 4); data.addProperty("maxObservedSlots", 128);
        data.addProperty("commands", "GuiChat submission plus native command dispatch; submission is not success; authentication arguments redacted");
        data.addProperty("blocks", "Forge player interaction/place/break events; bounded target and adjacent loaded-block/AE-part follow-ups at 1, 5, 20 ticks");
        data.addProperty("gui", "native screen input, Forge buttons, vanilla windowClick, open/close, bounded supported widget and container changes");
        data.addProperty("limitations", "Samples are not every-frame playback. Mods bypassing these callbacks may be absent. GUI client state is not server acceptance; server deltas are separate observations, not causal attribution. No arbitrary mod packet decoding, OS input, screenshots or automatic replay.");
        return data;
    }

    void safe(Runnable observer) {
        if (!log.enabled()) return;
        try { observer.run(); }
        catch (RuntimeException | LinkageError error) {
            JsonObject detail = new JsonObject(); detail.addProperty("errorClass", error.getClass().getSimpleName());
            log.add("observer_error", "recorder", detail); log.stop("observer_error");
        }
    }

    private boolean owns(EntityPlayer player) { return log.enabled() && player != null && player.getUniqueID().equals(actor); }
    private JsonObject gui(GuiScreen value) {
        JsonObject result = new JsonObject(); result.addProperty("screenClass", value == null ? "" : value.getClass().getName());
        result.addProperty("screenId", screenIdentity.apply(value));
        if (value instanceof GuiContainer) result.addProperty("windowId", ((GuiContainer) value).inventorySlots.windowId);
        return result;
    }

    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ticks++;
        if (!log.enabled()) {
            if (clientWorld != null) {
                actor = null; clientWorld = null; screen = null; guiVisible = false;
                lastPose = null; lastWidgets = null; lastScreen = null; clientSlots.reset(); serverSlots.reset();
                synchronized (watches) { watches.clear(); }
            }
            return;
        }
        safe(() -> {
            Minecraft client = Minecraft.getMinecraft();
            if (client.theWorld != clientWorld || !owns(client.thePlayer)) { log.stop("world_or_player_changed"); return; }
            guiVisible = client.currentScreen != null;
            if (screen != client.currentScreen) {
                // Never rebind GameBridge to the OLD screen merely to describe it.
                JsonObject after = gui(client.currentScreen);
                JsonObject detail = new JsonObject(); detail.add("before", lastScreen == null ? JsonNull.INSTANCE : lastScreen); detail.add("after", after);
                detail.addProperty("clientTicks", ticks); log.add("gui_changed", "client_state", detail);
                screen = client.currentScreen; lastScreen = after; lastWidgets = null; clientSlots.reset();
            }
            observeBlocks(client.theWorld);
            if (ticks % 4 != 0) return;
            JsonObject current = pose(client.thePlayer);
            if (!current.equals(lastPose)) {
                JsonObject detail = new JsonObject(); detail.add("pose", current); detail.addProperty("clientTicks", ticks);
                detail.addProperty("worldTick", client.theWorld.getTotalWorldTime());
                log.add("movement_sample", "client_state", detail); lastPose = current;
            }
            if (screen != null) {
                clientSlots.observe(client.thePlayer.openContainer, client.thePlayer, "client_container", gui(screen));
                // Reuse the existing bounded supported-widget observation with the SAME screen binding.
                if (!(screen instanceof GuiChat)) {
                    JsonObject widgets = GuiControlAdapter.observeInputState(client, screen, screenIdentity.apply(screen), GuiCallbacks.buttons(screen, null));
                    if (!widgets.equals(lastWidgets)) {
                        JsonObject detail = gui(screen); detail.add("state", widgets); detail.addProperty("serverConfirmed", false);
                        log.add("gui_widget_state", "client_widget_getters", detail); lastWidgets = widgets;
                    }
                }
            }
        });
    }

    private static JsonObject pose(EntityPlayer player) {
        JsonObject value = new JsonObject(); value.addProperty("playerId", player.getUniqueID().toString());
        value.addProperty("dimension", player.dimension); value.addProperty("x", player.posX);
        value.addProperty("y", player.boundingBox.minY); value.addProperty("z", player.posZ);
        value.addProperty("yaw", player.rotationYaw); value.addProperty("pitch", player.rotationPitch);
        value.addProperty("onGround", player.onGround); value.addProperty("flying", player.capabilities.isFlying);
        value.addProperty("creative", player.capabilities.isCreativeMode); value.addProperty("sneaking", player.isSneaking());
        value.addProperty("selectedHotbarSlot", player.inventory.currentItem); return value;
    }

    public void command(String text, String source) { safe(() -> {
        if (text == null || !text.trim().startsWith("/")) return;
        JsonObject detail = new JsonObject(); detail.addProperty("text", PlayerActivityLog.command(text));
        detail.addProperty("textTruncated", text.length() > 2048); detail.addProperty("serverConfirmed", false);
        log.add("command_submitted", source, detail);
    }); }

    public void mouse(GuiScreen value) { safe(() -> {
        int button = Mouse.getEventButton(), wheel = Mouse.getEventDWheel();
        if (button < 0 && wheel == 0) return;
        Minecraft client = Minecraft.getMinecraft();
        JsonObject detail = gui(value); detail.addProperty("button", button); detail.addProperty("pressed", Mouse.getEventButtonState());
        detail.addProperty("wheel", wheel); detail.addProperty("x", Mouse.getEventX() * value.width / Math.max(1, client.displayWidth));
        detail.addProperty("y", value.height - Mouse.getEventY() * value.height / Math.max(1, client.displayHeight) - 1);
        modifiers(detail); detail.addProperty("serverConfirmed", false); log.add("gui_mouse_input", "native_gui_input", detail);
    }); }

    public void keyboard(GuiScreen value) { safe(() -> {
        if (value instanceof GuiChat) return;
        if (!Keyboard.getEventKeyState()) return;
        JsonObject detail = gui(value); detail.addProperty("keyCode", Keyboard.getEventKey());
        // Chat is recorded at submission, not as a stream of secret/unfinished characters.
        detail.addProperty("character", String.valueOf(Keyboard.getEventCharacter()));
        modifiers(detail); detail.addProperty("serverConfirmed", false); log.add("gui_key_input", "native_gui_input", detail);
    }); }

    private static void modifiers(JsonObject detail) {
        detail.addProperty("shift", GuiScreen.isShiftKeyDown()); detail.addProperty("ctrl", GuiScreen.isCtrlKeyDown());
    }

    public void slot(int window, int slot, int button, int mode) { safe(() -> {
        Minecraft client = Minecraft.getMinecraft(); JsonObject detail = gui(client.currentScreen);
        detail.addProperty("windowId", window); detail.addProperty("slot", slot); detail.addProperty("button", button); detail.addProperty("mode", mode);
        detail.addProperty("serverConfirmed", false); detail.addProperty("phase", "before_native_windowClick");
        if (client.thePlayer != null) detail.add("cursorBefore", item(client.thePlayer.inventory.getItemStack()));
        log.add("gui_slot_click", "native_player_controller", detail);
    }); }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void opening(GuiOpenEvent event) { safe(() -> {
        JsonObject detail = new JsonObject(); detail.addProperty("proposedScreenClass", event.gui == null ? "" : event.gui.getClass().getName());
        detail.addProperty("cancelledAtObserver", event.isCanceled()); detail.addProperty("phase", "before_open");
        log.add("gui_open_requested", "forge_event", detail);
    }); }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void button(GuiScreenEvent.ActionPerformedEvent.Pre event) { safe(() -> {
        JsonObject detail = gui(event.gui); detail.addProperty("buttonId", event.button.id);
        detail.addProperty("label", JsonData.bounded(event.button.displayString, 256)); detail.addProperty("cancelledAtObserver", event.isCanceled());
        detail.addProperty("serverConfirmed", false); log.add("gui_button", "forge_pre_event", detail);
    }); }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void dispatched(CommandEvent event) {
        if (!(event.sender instanceof EntityPlayer) || !owns((EntityPlayer) event.sender)) return;
        safe(() -> {
            JsonObject detail = new JsonObject();
            detail.addProperty("text", PlayerActivityLog.command("/" + event.command.getCommandName() + " " + String.join(" ", event.parameters)));
            detail.addProperty("cancelledAtObserver", event.isCanceled()); detail.addProperty("serverConfirmed", false);
            detail.addProperty("side", ((EntityPlayer) event.sender).worldObj.isRemote ? "client" : "server");
            log.add("command_dispatch", "forge_pre_event", detail);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void feedback(ClientChatReceivedEvent event) { safe(() -> {
        if (event.message == null || event.isCanceled()) return;
        JsonObject detail = new JsonObject(); String text = event.message.getUnformattedText();
        detail.addProperty("text", JsonData.bounded(text, 2048)); detail.addProperty("textTruncated", text.length() > 2048);
        log.add("chat_feedback", "forge_client_chat", detail);
    }); }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void interacting(PlayerInteractEvent event) {
        if (!owns(event.entityPlayer)) return;
        safe(() -> {
            JsonObject detail = point(event.world, event.x, event.y, event.z);
            detail.addProperty("action", event.action.name()); detail.addProperty("face", event.face);
            detail.addProperty("cancelledAtObserver", event.isCanceled()); detail.add("held", item(event.entityPlayer.getHeldItem()));
            long parent = log.add("block_interaction", "forge_pre_event", detail);
            if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
                watch(event.world, event.x, event.y, event.z, parent, null);
                ForgeDirection face = ForgeDirection.getOrientation(event.face);
                watch(event.world, event.x + face.offsetX, event.y + face.offsetY, event.z + face.offsetZ, parent, null);
            }
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void breaking(BlockEvent.BreakEvent event) {
        if (!owns(event.getPlayer())) return;
        safe(() -> {
            JsonObject detail = point(event.world, event.x, event.y, event.z); detail.add("before", block(event.world, event.x, event.y, event.z));
            detail.addProperty("cancelledAtObserver", event.isCanceled()); detail.addProperty("confirmed", false);
            long parent = log.add("block_break_requested", "forge_pre_event", detail);
            watch(event.world, event.x, event.y, event.z, parent, null);
        });
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void placing(BlockEvent.PlaceEvent event) {
        if (!owns(event.player)) return;
        safe(() -> {
            List<BlockSnapshot> snapshots = new ArrayList<BlockSnapshot>();
            if (event instanceof BlockEvent.MultiPlaceEvent) snapshots.addAll(((BlockEvent.MultiPlaceEvent) event).getReplacedBlockSnapshots());
            else snapshots.add(event.blockSnapshot);
            int count = 0;
            for (BlockSnapshot snapshot : snapshots) {
                if (count++ >= 32) { JsonObject gap = new JsonObject(); gap.addProperty("total", snapshots.size()); log.add("block_coverage_gap", "recorder", gap); break; }
                JsonObject before = identity(snapshot.getReplacedBlock(), snapshot.meta);
                JsonObject detail = point(event.world, snapshot.x, snapshot.y, snapshot.z); detail.add("beforeBlock", before);
                detail.add("provisionalAfter", block(event.world, snapshot.x, snapshot.y, snapshot.z));
                detail.addProperty("cancelledAtObserver", event.isCanceled()); detail.addProperty("confirmed", false);
                long parent = log.add("block_place_requested", "forge_event_before_rollback", detail);
                watch(event.world, snapshot.x, snapshot.y, snapshot.z, parent, before);
            }
        });
    }

    private void watch(World world, int x, int y, int z, long parent, JsonObject before) {
        synchronized (watches) {
            List<Watch> list = watches.get(world);
            if (list == null) { list = new ArrayList<Watch>(); watches.put(world, list); }
            if (list.size() >= 64) {
                JsonObject gap = point(world, x, y, z); gap.addProperty("reason", "pending_target_budget"); log.add("block_coverage_gap", "recorder", gap); return;
            }
            list.add(new Watch(x, y, z, parent, world.getTotalWorldTime(), before == null ? block(world, x, y, z) : before));
        }
    }

    @SubscribeEvent public void worldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        safe(() -> {
            observeBlocks(event.world);
            if (!event.world.isRemote && event.world.getTotalWorldTime() % 4 == 0 && actor != null) {
                EntityPlayer player = event.world.getPlayerEntityByUUID(actor);
                if (player != null && (guiVisible || player.openContainer != player.inventoryContainer)) {
                    JsonObject context = new JsonObject(); context.addProperty("dimension", player.dimension); context.addProperty("worldTick", event.world.getTotalWorldTime());
                    serverSlots.observe(player.openContainer, player, "server_container", context);
                } else if (player != null) serverSlots.reset();
                // Another loaded dimension without this actor must not erase the owner's baseline.
            }
        });
    }

    private void observeBlocks(World world) {
            synchronized (watches) {
                List<Watch> list = watches.get(world);
                if (list != null) {
                    long start = System.nanoTime(); Iterator<Watch> iterator = list.iterator();
                    while (iterator.hasNext()) {
                        Watch watch = iterator.next(); long age = world.getTotalWorldTime() - watch.started;
                        if (age < watch.next) continue;
                        JsonObject after = block(world, watch.x, watch.y, watch.z);
                        JsonObject detail = point(world, watch.x, watch.y, watch.z); detail.addProperty("parentSeq", watch.parent);
                        detail.add("before", watch.before); detail.add("after", after); detail.addProperty("ageTicks", age);
                        boolean known = after.has("registryName") && watch.before.has("registryName");
                        detail.add("blockIdentityChanged", known ? new com.google.gson.JsonPrimitive(
                                !watch.before.get("registryName").equals(after.get("registryName")) || !watch.before.get("metadata").equals(after.get("metadata"))) : JsonNull.INSTANCE);
                        boolean partsKnown = watch.before.has("aeParts") && after.has("aeParts");
                        detail.add("aePartsChanged", partsKnown ? new com.google.gson.JsonPrimitive(!watch.before.get("aeParts").equals(after.get("aeParts"))) : JsonNull.INSTANCE);
                        detail.addProperty("causationProven", false);
                        log.add("block_state_observed", world.isRemote ? "client_state" : "server_world", detail);
                        if (age >= 20) iterator.remove(); else watch.next = age < 5 ? 5 : 20;
                        if (System.nanoTime() - start > 2_000_000) break;
                    }
                    if (list.isEmpty()) watches.remove(world);
                }
            }
    }

    private static JsonObject point(World world, int x, int y, int z) {
        JsonObject value = new JsonObject(); value.addProperty("dimension", world.provider.dimensionId);
        value.addProperty("x", x); value.addProperty("y", y); value.addProperty("z", z);
        value.addProperty("side", world.isRemote ? "client" : "server"); value.addProperty("worldTick", world.getTotalWorldTime()); return value;
    }
    private static JsonObject identity(Block block, int metadata) {
        JsonObject result = new JsonObject(); result.addProperty("registryName", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        result.addProperty("metadata", metadata); return result;
    }
    private static JsonObject block(World world, int x, int y, int z) {
        JsonObject result = new JsonObject();
        if (y < 0 || y >= world.getActualHeight() || !world.blockExists(x, y, z)) { result.addProperty("available", false); return result; }
        result = identity(world.getBlock(x, y, z), world.getBlockMetadata(x, y, z)); result.addProperty("available", true);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null) result.addProperty("tileClass", tile.getClass().getName());
        try {
            result.add("aeParts", AePartAdapter.parts(world, x, y, z));
            if (ExpandedGuiApi.is(tile, "gregtech.api.interfaces.tileentity.IGregTechTileEntity"))
                result.addProperty("gtMetaTileId", ((Number) ExpandedGuiApi.call(tile, "getMetaTileID")).intValue());
        } catch (RuntimeException error) { result.addProperty("partObservationUnavailable", error.getClass().getSimpleName()); }
        return result;
    }

    static JsonElement item(ItemStack stack) {
        if (stack == null) return JsonNull.INSTANCE;
        JsonObject value = JsonData.item(stack).getAsJsonObject();
        if (stack.hasTagCompound()) {
            String nbt = stack.getTagCompound().toString();
            value.addProperty("nbtSnbt", JsonData.bounded(nbt, 512)); value.addProperty("nbtTruncated", nbt.length() > 512);
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(nbt.getBytes(StandardCharsets.UTF_8));
                StringBuilder hash = new StringBuilder(); for (byte b : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                value.addProperty("nbtSnbtSha256", hash.toString());
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }
        return value;
    }

    private final class SlotObserver {
        Container container; ItemStack[] previous; ItemStack cursor;
        synchronized void reset() { container = null; previous = null; cursor = null; }
        synchronized void observe(Container next, EntityPlayer player, String source, JsonObject context) {
            if (next == null) return;
            int count = Math.min(128, next.inventorySlots.size()); boolean baseline = container != next || previous == null || previous.length != count;
            if (baseline) { container = next; previous = new ItemStack[count]; cursor = null; }
            JsonArray changes = new JsonArray();
            for (int i = 0; i < count; i++) {
                Slot slot = (Slot) next.inventorySlots.get(i); ItemStack current = slot.getStack();
                if (baseline && current != null || !ItemStack.areItemStacksEqual(previous[i], current)) {
                    JsonObject change = new JsonObject(); change.addProperty("slot", slot.slotNumber);
                    change.add("before", item(previous[i])); change.add("after", item(current)); changes.add(change);
                }
                if (!ItemStack.areItemStacksEqual(previous[i], current)) previous[i] = current == null ? null : current.copy();
                if (changes.size() == 8) { emit(changes, next, source, context, baseline); changes = new JsonArray(); }
            }
            ItemStack currentCursor = player.inventory.getItemStack();
            if (!ItemStack.areItemStacksEqual(cursor, currentCursor)) {
                JsonObject change = new JsonObject(); change.addProperty("slot", "cursor"); change.add("before", item(cursor)); change.add("after", item(currentCursor)); changes.add(change);
            }
            cursor = currentCursor == null ? null : currentCursor.copy();
            if (changes.size() > 0 || baseline) emit(changes, next, source, context, baseline);
        }
        void emit(JsonArray changes, Container next, String source, JsonObject context, boolean baseline) {
            JsonObject data = new JsonObject(); data.add("context", context); data.addProperty("windowId", next.windowId);
            data.addProperty("containerClass", next.getClass().getName()); data.addProperty("baseline", baseline);
            data.addProperty("observedSlots", Math.min(128, next.inventorySlots.size())); data.addProperty("totalSlots", next.inventorySlots.size());
            data.addProperty("partial", next.inventorySlots.size() > 128); data.addProperty("causationProven", false);
            data.add("changes", changes); log.add("gui_slots_observed", source, data);
        }
    }
    private static final class Watch {
        final int x, y, z; final long parent, started; final JsonObject before; long next = 1;
        Watch(int x, int y, int z, long parent, long started, JsonObject before) {
            this.x = x; this.y = y; this.z = z; this.parent = parent; this.started = started; this.before = before;
        }
    }
}
