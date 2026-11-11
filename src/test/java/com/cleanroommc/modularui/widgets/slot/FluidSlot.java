package com.cleanroommc.modularui.widgets.slot;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.IFluidTank;

/** Public shared-widget fixture. Press queues native work without writing cursor or tank. */
public class FluidSlot extends com.cleanroommc.modularui.widget.Widget {
    public Handler handler = new Handler(); public int presses, releases, drags, lastButton;
    public net.minecraft.item.ItemStack dragged;
    public boolean accept = true;
    public Handler getSyncHandler() { if (handler == null) throw new IllegalStateException("Uninitialized public sync handler"); return handler; }
    public FluidStack getFluidStack() { return getSyncHandler().fluid; }
    public IFluidTank getFluidTank() { return getSyncHandler().tank; }
    public Object onMousePressed(int button) { if (button != 0 && !handler.phantom) throw new AssertionError("Only a neutral left click is supported"); presses++; lastButton = button; return null; }
    public boolean onMouseRelease(int button) { releases++; return false; }
    public boolean handleDragAndDrop(net.minecraft.item.ItemStack stack, int button) {
        if (!handler.phantom || !accept) return false;
        drags++; dragged = stack.copy(); handler.packets++; stack.stackSize = 0; return true;
    }
    public static class Handler extends PhantomItemSlot.Handler {
        public boolean fill = true, drain = true, phantom;
        public FluidStack fluid; public IFluidTank tank = new FluidTank(256000);
        public boolean canFillSlot() { return fill; } public boolean canDrainSlot() { return drain; } public boolean isPhantom() { return phantom; }
        public boolean controlsAmount() { return false; }
        public void setValue(FluidStack value) { throw new AssertionError("Direct fluid mutation forbidden"); }
    }
}
