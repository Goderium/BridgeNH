package com.Goderium.BridgeNH.client.survival;

import com.Goderium.BridgeNH.client.control.OwnedKeyState;
import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.world.World;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/** One real hotbar meal or passive Healing Axe selection, observed through the normal player's packets. */
public final class SurvivalFood {
    public static final String OPERATION = "survival.food";
    public static final String AXE = "ExtraUtilities:defoliageAxe";
    private static volatile Job active;
    private SurvivalFood() {}

    public static String activeId() {
        Job job = active;
        return job == null ? null : job.evidence.taskId;
    }

    /** The input-task endpoint runs on the client thread. Never release a different owner's keys. */
    public static JsonObject cancel(String expectedId) {
        Job job = active;
        if (job == null || !job.evidence.owns(expectedId))
            throw new BridgeException("stale_task", "The specified food task no longer owns the hand.");
        job.cancelled = true;
        return job.finish("cancelled", false);
    }

    public static Job start(Minecraft client, JsonObject args, BooleanSupplier authorized) {
        ExpandedGuiApi.keys(args, "action", "slot", "registryName", "metadata", "timeout_ms");
        String action = JsonData.string(args, "action");
        boolean eat = "eat".equals(action);
        if (!eat && !"healing_axe".equals(action)) throw new BridgeException("invalid_args", "Expected eat or healing_axe.");
        int slot = JsonData.integer(args, "slot", 0, 8);
        String name = JsonData.string(args, "registryName");
        int meta = JsonData.integer(args, "metadata", 0, 32767);
        int wait = args.has("timeout_ms") ? JsonData.integer(args, "timeout_ms", 250, 6500) : 5000;
        if (active != null || client.thePlayer.isUsingItem())
            throw new BridgeException("busy", "An item use or food observation is already active.");
        ItemStack stack = client.thePlayer.inventory.getStackInSlot(slot);
        if (stack == null || stack.stackSize <= 0 || !name.equals(Item.itemRegistry.getNameForObject(stack.getItem()))
                || meta != stack.getMetadata())
            throw new BridgeException("food_item_changed", "Inspect the real hotbar slot and its current identity.");
        if (eat ? stack.getItemUseAction() != EnumAction.eat : !AXE.equals(name))
            throw new BridgeException("unsupported", "Expected an edible item with the native eat action or the registered Healing Axe.");
        if (client.getNetHandler() == null || !authorized.getAsBoolean() || client.thePlayer.isDead
                || client.thePlayer.getHealth() <= 0 || client.thePlayer.capabilities.isCreativeMode
                || client.thePlayer.capabilities.disableDamage)
            throw new BridgeException("invalid_state", "A living, damageable survival actor and the original control context are required.");
        Job job = new Job(client, slot, stack, eat, wait, authorized);
        if (eat && !job.player.getFoodStats().needFood()) {
            job.finish("already_full", false);
            return job;
        }
        if (eat && !job.player.canEat(false))
            throw new BridgeException("food_unavailable", "The current player cannot eat to restore hunger.");
        active = job;
        try {
            if (eat) {
                KeyBinding use = client.gameSettings.keyBindUseItem;
                if (use.getKeyCode() == 0) throw new BridgeException("unsupported", "The native use binding is unbound.");
                OwnedKeyState.begin(OPERATION, job.deadline, () -> job.inContext() && job.selected()
                        && client.gameSettings.keyBindUseItem == use && (job.starting || job.player.getItemInUse() == stack));
                OwnedKeyState.hold(OPERATION, use, use.getKeyCode());
            }
            job.player.inventory.currentItem = slot;
            if (!eat) {
                // A passive item never calls sendUseItem to synchronize its selected slot.
                client.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
                if (!job.player.getFoodStats().needFood()) job.finish("selected_at_full", false);
            } else if (ForgeEventFactory.onPlayerInteract(job.player, PlayerInteractEvent.Action.RIGHT_CLICK_AIR,
                    0, 0, 0, -1, job.world).isCanceled()) job.finish("use_cancelled", false);
            else if (!job.inContext() || !job.selected() || job.player.inventory.getCurrentItem() != stack
                    || !ItemStack.areItemStacksEqual(job.before, stack)) job.finish("hand_changed", false);
            else {
                job.submitted = true;
                // sendUseItem returns false for ordinary food: its start changes use duration, not the stack count.
                client.playerController.sendUseItem(job.player, job.world, stack);
                if (job.player.getItemInUse() != stack) job.finish("use_not_started", false);
            }
            job.starting = false;
            return job;
        } catch (RuntimeException | LinkageError error) {
            job.release();
            throw error;
        }
    }

    public static void health(Object handler, S06PacketUpdateHealth packet) {
        Job job = active;
        if (job != null && !job.starting && handler == job.handler && job.selected())
            job.evidence.serverFood = packet.getFoodLevel();
    }

    public static void beforeStatus(Object handler, S19PacketEntityStatus packet) {
        Job job = active;
        if (job != null && job.eat && job.submitted && handler == job.handler && packet.func_149160_c() == 9
                && packet.func_149161_a(job.world) == job.player && job.selected()
                && job.player.getItemInUse() == job.original) job.finishingPacket = packet;
    }

    public static void afterStatus(S19PacketEntityStatus packet) {
        Job job = active;
        if (job != null && job.finishingPacket == packet && job.selected() && !job.player.isUsingItem()) {
            ItemStack actual = job.player.inventory.getCurrentItem();
            job.finishedStack = actual == null ? null : actual.copy();
            job.evidence.serverFinished = true;
        }
    }

    public static void tick() {
        Job job = active;
        if (job == null) return;
        try {
            if (!job.inContext() || !job.selected()) { job.finish("context_changed", false); return; }
            if (System.nanoTime() >= job.deadline) { job.finish("observation_timeout", false); return; }
            ItemStack now = job.player.inventory.getCurrentItem();
            if (job.eat && job.player.isUsingItem() && job.player.getItemInUse() != job.original) {
                job.finish("hand_changed", false); return;
            }
            boolean identity = now != null && job.before.isItemEqual(now) && ItemStack.areItemStackTagsEqual(job.before, now);
            if (job.eat && job.evidence.serverFinished && !ItemStack.areItemStacksEqual(job.finishedStack, now)) {
                job.finish("hand_changed", false); return;
            }
            String status = job.evidence.observed(job.eat, job.player.isUsingItem(), job.player.getFoodStats().getFoodLevel(),
                    identity, now == null ? 0 : now.stackSize);
            if (status != null) job.finish(status, "eaten".equals(status) || "replenished".equals(status));
        } catch (RuntimeException | LinkageError error) {
            job.release();
            job.result.completeExceptionally(error);
        }
    }

    public static final class Job {
        public final CompletableFuture<JsonObject> result = new CompletableFuture<JsonObject>();
        public volatile boolean cancelled;
        final Minecraft client;
        final EntityPlayer player;
        final World world;
        final Object handler;
        final int dimension, slot;
        final FoodEvidence evidence;
        final ItemStack before, original;
        final boolean eat;
        final long deadline;
        final BooleanSupplier authorized;
        volatile boolean submitted;
        volatile S19PacketEntityStatus finishingPacket;
        ItemStack finishedStack;
        volatile boolean starting = true;

        Job(Minecraft client, int slot, ItemStack stack, boolean eat, int wait, BooleanSupplier authorized) {
            this.client = client; player = client.thePlayer; world = client.theWorld; handler = client.getNetHandler();
            this.slot = slot; dimension = player.dimension; original = stack; before = stack.copy();
            this.eat = eat; this.authorized = authorized;
            evidence = new FoodEvidence(player.getFoodStats().getFoodLevel(), stack.stackSize);
            deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(wait);
        }
        boolean inContext() {
            return !cancelled && client.thePlayer == player && client.theWorld == world && player.dimension == dimension
                    && client.getNetHandler() == handler && !player.isDead && player.getHealth() > 0
                    && !player.capabilities.isCreativeMode && !player.capabilities.disableDamage
                    && client.currentScreen == null && !client.isGamePaused() && authorized.getAsBoolean();
        }
        boolean selected() { return player.inventory.currentItem == slot; }
        void release() {
            if (active != this) return;
            active = null;
            if (!eat) return;
            OwnedKeyState.clear(OPERATION);
            if (submitted && client.thePlayer == player && client.theWorld == world && client.getNetHandler() == handler
                    && player.getItemInUse() == original && client.playerController != null)
                client.playerController.onStoppedUsingItem(player);
        }
        JsonObject finish(String status, boolean verified) {
            JsonObject out = new JsonObject();
            out.addProperty("taskId", evidence.taskId);
            out.addProperty("status", status); out.addProperty("foodGainVerified", verified);
            boolean complete = verified || "already_full".equals(status) || "selected_at_full".equals(status);
            out.addProperty("complete", complete); out.addProperty("partial", !complete);
            out.addProperty("replayAllowed", false);
            out.addProperty("action", eat ? "eat" : "healing_axe"); out.addProperty("useSubmitted", submitted);
            out.addProperty("serverUseFinished", evidence.serverFinished); out.addProperty("slot", slot);
            out.addProperty("foodBefore", evidence.foodBefore); out.add("itemBefore", JsonData.item(before));
            if (evidence.serverFood >= 0) out.addProperty("serverFoodAfter", evidence.serverFood);
            if (client.thePlayer == player && client.theWorld == world && client.getNetHandler() == handler) {
                out.addProperty("foodAfter", player.getFoodStats().getFoodLevel());
                out.addProperty("selectedSlotAfter", player.inventory.currentItem);
                ItemStack after = player.inventory.getStackInSlot(slot);
                out.add("itemAfter", JsonData.item(after));
                out.addProperty("passiveWhileSelected", !eat && selected() && ItemStack.areItemStacksEqual(before, after));
            }
            release();
            result.complete(out);
            return out;
        }
    }
}
