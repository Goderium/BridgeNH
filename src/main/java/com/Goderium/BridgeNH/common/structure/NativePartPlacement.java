package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.client.gui.ExpandedGuiApi;
import com.Goderium.BridgeNH.common.ae.AePartAdapter;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

/** Fixed native mouse callback; reuses normal Forge part placement and existing inventory barriers. */
public final class NativePartPlacement {
    private static final Method USE = useMethod();
    private static Method useMethod() {
        for (String name : new String[] {"rightClickMouse", "func_147121_ag"}) try {
            Method method = Minecraft.class.getDeclaredMethod(name); method.setAccessible(true); return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        return null;
    }
    static void source(ItemStack stack, JsonObject part) {
        if (USE == null) throw new BridgeException("unsupported", "The fixed native right-click callback is unavailable.");
        AePartAdapter.declaration(part);
        if (stack == null || stack.stackSize <= 0 || !ExpandedGuiApi.is(stack.getItem(), "appeng.api.parts.IPartItem")
                || !String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())).equals(JsonData.string(part, "registryName"))
                || stack.getMetadata() != JsonData.integer(part, "metadata", 0, 32767))
            throw new BridgeException("invalid_state", "The actual selected AE part stack differs from expectedPart.");
    }
    public static void vacant(JsonObject block, JsonObject part) {
        if (!block.get("air").getAsBoolean() && !block.has("parts"))
            throw new BridgeException("invalid_state", "Part placement requires air or an existing native AE part host.");
        if (block.has("parts")) for (JsonElement value : block.getAsJsonArray("parts"))
            if (value.getAsJsonObject().get("side").equals(part.get("side")))
                throw new BridgeException("invalid_state", "The requested mounting slot already contains a part; no use was submitted.");
    }
    public static boolean present(JsonObject block, JsonObject part) {
        if (block.has("parts")) for (JsonElement value : block.getAsJsonArray("parts"))
            if (AePartAdapter.matches(part, value.getAsJsonObject())) return true;
        return false;
    }
    public static void route(World world, NativePlacementAdapter.Plan plan) {
        Object host = world.getTileEntity(plan.attachment.blockX, plan.attachment.blockY, plan.attachment.blockZ);
        boolean sameHost = plan.x == plan.attachment.blockX && plan.y == plan.attachment.blockY && plan.z == plan.attachment.blockZ;
        boolean accepts = ExpandedGuiApi.is(host, AePartAdapter.HOST) && Boolean.TRUE.equals(ExpandedGuiApi.call(host,
            "canAddPart", plan.item, ForgeDirection.getOrientation(plan.attachment.sideHit)));
        if (accepts != sameHost) throw new BridgeException("part_route_changed",
            "Native AE tries the clicked host before its neighbor; the declared destination does not match that route.");
    }
    public static JsonObject use(Minecraft client, NativePlacementAdapter.Plan plan, Runnable starting) {
        source(client.thePlayer.inventory.getStackInSlot(plan.slot), plan.part);
        route(client.theWorld, plan);
        boolean before = client.thePlayer.movementInput.sneak;
        try {
            client.thePlayer.movementInput.sneak = true;
            Class<?> platform = ExpandedGuiApi.load(plan.item.getItem(), "appeng.util.Platform");
            float eyeY = ((Number) ExpandedGuiApi.call(platform, "getEyeOffset", client.thePlayer)).floatValue();
            Vec3 eye = Vec3.createVectorHelper(client.thePlayer.posX, eyeY, client.thePlayer.posZ);
            double dx = plan.attachment.hitVec.xCoord - eye.xCoord, dy = plan.attachment.hitVec.yCoord - eye.yCoord;
            double dz = plan.attachment.hitVec.zCoord - eye.zCoord;
            client.thePlayer.rotationYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90);
            client.thePlayer.rotationPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            Object ray = ExpandedGuiApi.call(platform, "getPlayerRay", client.thePlayer, eyeY);
            Vec3 from = (Vec3) ExpandedGuiApi.call(ray, "getA"), to = (Vec3) ExpandedGuiApi.call(ray, "getB");
            MovingObjectPosition actual = client.theWorld.rayTraceBlocks(from, to);
            requireRay(plan.attachment, actual, eye, client.playerController.getBlockReachDistance());
            client.objectMouseOver = actual;
            client.thePlayer.sendQueue.addToSendQueue(new C03PacketPlayer.C05PacketPlayerLook(
                    client.thePlayer.rotationYaw, client.thePlayer.rotationPitch, client.thePlayer.onGround));
            client.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(client.thePlayer, 1));
            starting.run();
            USE.invoke(client);
            JsonObject result = new JsonObject(); result.addProperty("nativeInvoked", true);
            // A void normal callback does not report acceptance. Server barriers decide success.
            result.add("accepted", com.google.gson.JsonNull.INSTANCE);
            return result;
        } catch (IllegalAccessException | InvocationTargetException error) {
            throw new BridgeException("native_part_failed", "Native part callback failed after dispatch; inspect the retained server state.");
        } finally {
            client.thePlayer.movementInput.sneak = before;
            client.thePlayer.sendQueue.addToSendQueue(new C0BPacketEntityAction(client.thePlayer, before ? 1 : 2));
        }
    }
    @com.google.common.annotations.VisibleForTesting
    public static void requireRay(MovingObjectPosition expected, MovingObjectPosition actual, Vec3 eye, double reach) {
        if (actual == null || actual.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || actual.blockX != expected.blockX || actual.blockY != expected.blockY || actual.blockZ != expected.blockZ
                || actual.sideHit != expected.sideHit || actual.hitVec == null
                || !Double.isFinite(eye.distanceTo(actual.hitVec)) || eye.distanceTo(actual.hitVec) > reach + .00001)
            throw new BridgeException("part_view_changed", "AE's native player ray does not hit the exact bound face within normal reach; no placement callback was submitted.");
    }
}
