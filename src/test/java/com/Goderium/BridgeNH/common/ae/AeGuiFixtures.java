package com.Goderium.BridgeNH.common.ae;

import appeng.api.config.Settings;
import appeng.api.storage.data.IAEStack;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Plain synchronized-state fixtures; no Minecraft client, GuiScreen or renderer initialization. */
public final class AeGuiFixtures {
    public static final java.util.List<String> events = new java.util.ArrayList<String>();
    public static class Type { public final String id; public Type(String id) { this.id = id; } public String getId() { return id; } }
    public static class Stack implements IAEStack {
        public final Type type; public final ItemStack item; public final String nbtIdentity; public long amount, requestable; public boolean craftable;
        public Stack(Type type, ItemStack item, String identity, long amount) { this.type = type; this.item = item; nbtIdentity = identity; this.amount = amount; }
        public long getStackSize() { return amount; } public Object setStackSize(long value) { amount = value; return this; }
        public long getCountRequestable() { return requestable; } public Object setCountRequestable(long value) { requestable = value; return this; }
        public Object setCountRequestableCrafts(long value) { return this; } public boolean isCraftable() { return craftable; } public Object setCraftable(boolean value) { craftable = value; return this; }
        public Object copy() { Stack copy = new Stack(type, item, nbtIdentity, amount); copy.requestable = requestable; copy.craftable = craftable; return copy; }
        public Object getStackType() { return type; } public String getDisplayName() { return "same icon name"; } public boolean isItem() { return type.id.equals("item"); }
        public ItemStack getItemStackForNEI() { return item; } public int getAmountPerUnit() { return type.id.equals("fluid") ? 1000 : 1; }
        public void writeToNBTGeneric(NBTTagCompound tag) { tag.setString("StackType", type.id); tag.setString("identity", nbtIdentity); tag.setLong("count", amount); tag.setLong("requestable", requestable); tag.setBoolean("craftable", craftable); }
    }
    public static class Config {
        public final Map<Settings, Object> values = new HashMap<Settings, Object>();
        public Object getSetting(Settings key) { return values.containsKey(key) ? values.get(key) : Settings.Option.NAME; }
        public void putSetting(Settings key, Object value) { values.put(key, value); }
    }
    public static class Inventory { public final Object[] slots; public Inventory(int size) { slots = new Object[size]; } public int getSizeInventory() { return slots.length; } public Object getAEStackInSlot(int slot) { return slots[slot]; } }
    public static class InventorySync { public final Inventory inv; public InventorySync(int size) { inv = new Inventory(size); } public Inventory get() { return inv; } }
    public static class BooleanSync { public boolean value; public int calls; public boolean get() { return value; } public void set(boolean value) { this.value = value; calls++; } }
    public static class IntSync { public int value; public int get() { return value; } public void set(int value) { this.value = value; } }
    public static class Action { public int calls; public Object payload; public void send() { calls++; } public void send(Object value) { payload = value; calls++; } }
    public static class Scroll { public int value; public void setCurrentScroll(int value) { this.value = value; } }
    public static class CpuTableHolder { public final appeng.container.implementations.ContainerCPUTable table; public CpuTableHolder(appeng.container.implementations.ContainerCPUTable table) { this.table = table; } public appeng.container.implementations.ContainerCPUTable getContainer() { return table; } }
    public static class JobEntry { public Object stack; public JobEntry(Object stack) { this.stack = stack; } public Object getStack() { return stack; } public long getStoredAmount() { return 9007199254740993L; } public long getActiveAmount() { return 9L; } public long getPendingAmount() { return Long.MAX_VALUE; } public Object getScheduledReason() { return "WAITING_FOR_INGREDIENTS"; } }
    public static class ItemInventory { public final ItemStack[] items; public ItemInventory(int size) { items = new ItemStack[size]; } public int getSizeInventory() { return items.length; } public ItemStack getStackInSlot(int slot) { return items[slot]; } }
}
