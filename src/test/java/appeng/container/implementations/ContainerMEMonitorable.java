package appeng.container.implementations;

public class ContainerMEMonitorable extends appeng.container.AEBaseContainer {
    public int targetChanges; public int pickups, stopAfter = 64;
    public ContainerMEMonitorable() { windowId = 17; }
    public void doMonitorableAction(appeng.helpers.MonitorableAction action, int custom, net.minecraft.entity.player.EntityPlayerMP player) {
        pickups++; if (pickups > stopAfter) return;
        net.minecraft.item.ItemStack held = player.inventory.getItemStack();
        if (held == null) { held = ((com.Goderium.BridgeNH.common.ae.AeGoalsSelfTest.Resource) target).getItemStack(); held.stackSize = 0; }
        held.stackSize++; player.inventory.setItemStack(held);
    }
    @Override public void detectAndSendChanges() {}
     public Object target;
    public net.minecraft.nbt.NBTTagCompound targetPayload;
    public void setTargetStack(Object value) {
        target = value; targetChanges++;
        targetPayload = new net.minecraft.nbt.NBTTagCompound();
        if (value instanceof appeng.api.storage.data.IAEStack) ((appeng.api.storage.data.IAEStack) value).writeToNBTGeneric(targetPayload);
        com.Goderium.BridgeNH.common.ae.AeGuiFixtures.events.add("target_serialized");
    }
}
