package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.client.gui.GuiDataAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;
import com.google.gson.JsonObject;
import com.Goderium.BridgeNH.common.inventory.PlayerSlotBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;

public final class WorldMouseInput {
    private static final String OWNER = "player.world_mouse";
    private WorldMouseInput() {}

    public static JsonObject perform(Minecraft client, JsonObject args) {
        EntityClientPlayerMP player = client.thePlayer;
        if (player.isDead || player.isUsingItem() || client.renderViewEntity != player)
            throw new BridgeException("invalid_state", "World mouse input requires the live player view and a free hand action.");
        String action = ExpandedGuiApi.option(args, "action", null, "wheel", "pick_block");
        JsonObject out = new JsonObject();
        out.addProperty("action", action);
        out.addProperty("slotBefore", player.inventory.currentItem);
        if ("wheel".equals(action)) {
            ExpandedGuiApi.keys(args, "direction", "sneaking");
            int direction = "up".equals(ExpandedGuiApi.option(args, "direction", null, "up", "down")) ? 1 : -1;
            boolean sneak = GuiDataAdapter.booleanArg(args, "sneaking", false);
            KeyBinding binding = client.gameSettings.keyBindSneak;
            boolean acquire = sneak && !binding.getIsKeyPressed();
            if (acquire && binding.getKeyCode() == 0)
                throw new BridgeException("invalid_state", "The sneak key is unbound.");
            MouseEvent event = new MouseEvent();
            if (!(event instanceof WheelEventAccess))
                throw new BridgeException("unsupported", "Forge wheel event initialization is unavailable.");
            ((WheelEventAccess) event).mcb$initializeWheel(client.displayWidth / 2, client.displayHeight / 2, direction * 120);
            if (acquire) OwnedKeyState.begin(OWNER, System.nanoTime() + 1000000000L,
                    () -> client.thePlayer == player && client.currentScreen == null);
            try {
                if (acquire) OwnedKeyState.hold(OWNER, binding, binding.getKeyCode());
                boolean active = binding.getIsKeyPressed();
                if (sneak && !active) throw new BridgeException("invalid_state", "The requested sneak modifier was not observed.");
                out.addProperty("sneakKeyDown", active);
                client.entityRenderer.getMouseOver(1.0F);
                boolean canceled = MinecraftForge.EVENT_BUS.post(event);
                out.addProperty("eventCanceled", canceled);
                if (!canceled && client.thePlayer == player && client.currentScreen == null && client.theWorld == player.worldObj) {
                    player.inventory.changeCurrentItem(direction);
                    if (client.gameSettings.noclip) client.gameSettings.noclipRate += direction * 0.25F;
                }
            } finally {
                if (acquire) OwnedKeyState.clear(OWNER);
            }
        } else {
            ExpandedGuiApi.keys(args);
            client.entityRenderer.getMouseOver(1.0F);
            MovingObjectPosition hit = client.objectMouseOver;
            if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK)
                throw new BridgeException("target_unverified", "The current player crosshair does not hit a block.");
            out.addProperty("x", hit.blockX); out.addProperty("y", hit.blockY); out.addProperty("z", hit.blockZ);
            int[] packetSlots = new int[InventoryPlayer.getHotbarSize()];
            ItemStack[] before = new ItemStack[packetSlots.length];
            if (player.capabilities.isCreativeMode) {
                for (int i = 0; i < packetSlots.length; i++) {
                    packetSlots[i] = PlayerSlotBinding.requireHotbar(player.inventoryContainer, player.inventory, i).slotNumber;
                    if (packetSlots[i] < 1 || packetSlots[i] >= 36 + packetSlots.length)
                        throw new BridgeException("unsupported", "The native hotbar slot is outside the creative packet range.");
                    before[i] = player.inventory.getStackInSlot(i);
                }
            }
            boolean accepted = ForgeHooks.onPickBlock(hit, player, client.theWorld);
            out.addProperty("accepted", accepted);
            if (accepted && player.capabilities.isCreativeMode) {
                // Forge selects an existing stack by reference, or installs a new one.
                // Additional equipment slots may follow the hotbar in the player container.
                int selected = player.inventory.currentItem;
                boolean granted = before[selected] != player.inventory.getCurrentItem();
                if (granted) client.playerController.sendSlotPacket(player.inventory.getCurrentItem(), packetSlots[selected]);
                out.addProperty("creativeSlotPacketSent", granted);
                out.addProperty("nativeContainerSlotId", packetSlots[selected]);
            }
        }
        out.addProperty("submitted", true);
        out.addProperty("serverConfirmed", false);
        out.addProperty("selectedSlot", player.inventory.currentItem);
        out.add("item", JsonData.item(player.inventory.getCurrentItem()));
        return out;
    }
}
