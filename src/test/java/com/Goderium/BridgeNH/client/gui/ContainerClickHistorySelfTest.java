package com.Goderium.BridgeNH.client.gui;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public final class ContainerClickHistorySelfTest {
    public static int run() {
        GuiContainer gui = new GuiContainer(null) {
            protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {}
        };
        InventoryPlayer player = new InventoryPlayer(null);
        ItemStack carried = new ItemStack(new Item(), 7, 3);
        player.setItemStack(carried);
        Slot slot = new Slot(player, 0, 0, 0);
        ContainerClickHistory owner = new ContainerClickHistory();
        boolean[] doubled = {false};
        long[] time = {5000};
        Runnable click = () -> doubled[0] = gui.fixturePress(slot, 0, time[0] += 20, carried);
        owner.press(gui, false, click, () -> true);
        check(!doubled[0], "first manual click");
        owner.press(gui, true, click, () -> true);
        check(!doubled[0], "an agent cannot complete a manual first click");
        owner.press(gui, true, click, () -> true);
        check(doubled[0], "separate clicks during the same agent takeover may double-click");
        owner.handoff(gui);
        check(!gui.fixtureHasDoubleClickHistory(), "handoff must forget the completed virtual press and its shift filter");
        owner.press(gui, false, click, () -> true);
        check(!doubled[0], "the first same-slot manual click after handoff must remain single");
        owner.handoff(gui);
        owner.press(gui, false, click, () -> true);
        check(doubled[0], "manual double-clicks survive repeated handoff notifications");
        owner.press(gui, true, click, () -> false);
        check(!gui.fixtureHasDoubleClickHistory(), "a closed or inactive screen cannot carry an agent click on reuse");
        owner.press(gui, true, () -> {
            owner.handoff(gui);
            click.run();
        }, () -> true);
        check(!gui.fixtureHasDoubleClickHistory(), "late vanilla history writes after reentrant handoff must be cleared");
        check(player.getItemStack() == carried && carried.stackSize == 7 && carried.getMetadata() == 3,
                "the actual carried stack, count and metadata must survive every handoff");
        return 9;
    }

    private static void check(boolean value, String caseName) { if (!value) throw new AssertionError(caseName); }
}
