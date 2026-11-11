package com.cleanroommc.modularui.widgets.slot;

import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** Native GUI fixture: submissions record independent templates; only later sync changes display. */
public class PhantomItemSlot extends com.cleanroommc.modularui.widget.Widget {
    public Handler handler = new Handler();
    public ItemStack displayed, dragged;
    public boolean accept = true;
    public int drags, presses, releases, button;
    public final Slot slot = new Slot(null, 0, 0, 0) {
        @Override public ItemStack getStack() { return displayed; }
        @Override public void putStack(ItemStack stack) { throw new AssertionError("Direct phantom inventory mutation forbidden"); }
    };
    public Slot getSlot() { return slot; }
    public Handler getSyncHandler() { return handler; }
    public boolean handleDragAndDrop(ItemStack stack, int button) {
        if (!accept) return false;
        drags++; dragged = stack.copy(); handler.packets++; stack.stackSize = 0; return true;
    }
    public Object onMousePressed(int button) { presses++; this.button = button; handler.packets++; return null; }
    public boolean onMouseRelease(int button) { releases++; return true; }
    public static class Handler {
        public PanelSyncManager manager;
        public boolean valid = true, phantom = true;
        public int packets;
        public boolean isValid() { return valid; }
        public boolean isPhantom() { return phantom; }
        public PanelSyncManager getSyncManager() { return manager; }
        public boolean isRegistered(PanelSyncManager candidate) {
            return candidate == manager && manager.registered && manager.registeredHandler == this;
        }
    }
}
