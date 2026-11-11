package net.minecraft.client.gui.inventory;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Headless native callback fixture. No Minecraft client, player construction or rendering. */
public class GuiContainerCreative extends GuiContainer {
    private static int tabPage;
    private int selected = CreativeTabs.tabBlock.getTabIndex();
    public int presses, releases, pickups, lastX, lastY, lastButton, lastType;
    public Slot lastSlot;
    public CreativeTabs releaseTab = CreativeTabs.tabInventory;
    public GuiContainerCreative() { super(new FixtureContainer()); width = 427; height = 240; guiLeft = 116; guiTop = 52; xSize = 195; ySize = 136; }
    public GuiContainerCreative(EntityPlayer ignored) { this(); }
    public int func_147056_g() { return selected; }
    public void select(CreativeTabs tab) { selected = tab.getTabIndex(); }
    public void page(int value) { tabPage = value; }
    public void moveOrigin(int x, int y) { guiLeft = x; guiTop = y; }
    public void addSlot(Slot slot, int number) { if (!(slot instanceof CreativeSlot)) slot.slotNumber = number; inventorySlots.inventorySlots.add(slot); }
    @Override protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {}
    @Override protected void mouseClicked(int x, int y, int button) { presses++; lastX = x; lastY = y; lastButton = button; }
    @Override protected void mouseReleased(int x, int y, int button) { releases++; lastX = x; lastY = y; lastButton = button; selected = releaseTab.getTabIndex(); }
    @Override protected void func_146984_a(Slot slot, int number, int button, int type) {
        throw new AssertionError("Semantic pickup must use the verified public native container path");
    }
    public static class CreativeSlot extends Slot {
        private final Slot field_148332_b;
        public CreativeSlot(IInventory inventory, int index, int x, int y) {
            this(new Slot(inventory, index >= 36 && index <= 44 ? index - 36 : index, x, y), index, x, y);
        }
        public CreativeSlot(Slot backing, int wrapperIndex, int x, int y) {
            super(backing.inventory, wrapperIndex, x, y);
            field_148332_b = backing;
        }
        @Override public ItemStack getStack() { return field_148332_b.getStack(); }
        @Override public void putStack(ItemStack stack) { field_148332_b.putStack(stack); }
        @Override public boolean isHere(IInventory inventory, int index) { return field_148332_b.isHere(inventory, index); }
    }
    private static class FixtureContainer extends Container { @Override public boolean canInteractWith(EntityPlayer ignored) { return true; } }
}
