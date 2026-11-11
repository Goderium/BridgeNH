package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.util.AdapterAccess;

import com.Goderium.BridgeNH.common.machine.BasicMachineFluidAdapter;
import com.Goderium.BridgeNH.common.machine.MachineSlotBinding;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidTank;

/** Shared public MUI2 fluid slot, current synchronized display state and native click permissions only. */
@com.google.common.annotations.VisibleForTesting
public final class FluidGuiAdapter {
    private static final String WIDGET = "com.cleanroommc.modularui.widgets.slot.FluidSlot";
    static boolean supports(Object widget) { return ExpandedGuiApi.is(widget, WIDGET); }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object widget) {
        return read(widget, MachineSlotBinding.Context.unavailable("public_machine_owner_api_unavailable"));
    }
    @com.google.common.annotations.VisibleForTesting
    public static JsonObject read(Object widget, MachineSlotBinding.Context owner) {
        JsonObject result = new JsonObject(); Object handler = null;
        try { if (supports(widget)) handler = AdapterAccess.call(widget, "getSyncHandler"); }
        catch (RuntimeException | LinkageError error) { /* Unavailable permissions below. */ }
        JsonObject fill = permission(handler, "canFillSlot"), drain = permission(handler, "canDrainSlot"), phantom = permission(handler, "isPhantom");
        result.add("canFill", fill); result.add("canDrain", drain); result.add("phantom", phantom);
        result.addProperty("nativeClickAvailable", fill.get("available").getAsBoolean() && drain.get("available").getAsBoolean()
                && phantom.get("available").getAsBoolean() && (fill.get("value").getAsBoolean() || drain.get("value").getAsBoolean()));
        JsonObject fluid = new JsonObject(), capacity = new JsonObject(); Object actualTank = null;
        try {
            if (handler == null) throw new BridgeException("unsupported", "Public fluid sync handler is unavailable.");
            Object value = AdapterAccess.call(widget, "getFluidStack");
            if (value != null && !(value instanceof FluidStack)) throw new BridgeException("unsupported", "Public fluid stack is unavailable.");
            fluid.addProperty("available", true); fluid.addProperty("empty", value == null);
            if (value == null) fluid.add("value", JsonNull.INSTANCE);
            else {
                FluidStack stack = (FluidStack) value;
                if (stack.getFluid() == null) throw new BridgeException("unsupported", "Public fluid identity is unavailable.");
                JsonObject state = new JsonObject(); state.addProperty("fluid", JsonData.bounded(stack.getFluid().getName(), 128));
                state.addProperty("amount", stack.amount); state.addProperty("hasTag", stack.tag != null); fluid.add("value", state);
            }
        } catch (RuntimeException | LinkageError error) { fluid = unavailable("public_fluid_stack_unavailable"); }
        try {
            if (handler == null) throw new BridgeException("unsupported", "Public fluid sync handler is unavailable.");
            Object value = AdapterAccess.call(widget, "getFluidTank");
            if (!(value instanceof IFluidTank)) throw new BridgeException("unsupported", "Public fluid tank capacity is unavailable.");
            actualTank = value;
            int current = ((IFluidTank) value).getCapacity();
            if (current < 0) throw new BridgeException("unsupported", "Public fluid tank capacity is unavailable.");
            capacity.addProperty("available", true); capacity.addProperty("value", current);
        } catch (RuntimeException | LinkageError error) { capacity = unavailable("public_fluid_capacity_unavailable"); }
        result.add("fluid", fluid); result.add("capacity", capacity);
        result.add("machineTankBinding", binding(widget, handler, actualTank, owner));
        result.addProperty("available", fluid.get("available").getAsBoolean() && capacity.get("available").getAsBoolean()
                && fill.get("available").getAsBoolean() && drain.get("available").getAsBoolean() && phantom.get("available").getAsBoolean());
        result.addProperty("source", "Current public FluidSlot.getFluidStack/getFluidTank and sync-handler permissions; client display state, not server tank acceptance.");
        result.addProperty("nativeAction", "Normal public left press and no-op release; native cursor, fill/drain, phantom, filter and synchronization rules decide the effect. No direct tank fill or setter.");
        return result;
    }
    private static JsonObject binding(Object widget, Object handler, Object tank, MachineSlotBinding.Context owner) {
        try {
            if (tank != null && (AdapterAccess.call(widget, "getSyncHandler") != handler
                    || AdapterAccess.call(widget, "getFluidTank") != tank))
                return BasicMachineFluidAdapter.unavailable("client_display", "public_widget_tank_identity_changed");
            return owner.bindFluidTank(tank);
        } catch (RuntimeException | LinkageError error) {
            return BasicMachineFluidAdapter.unavailable("client_display", "public_widget_tank_identity_unavailable");
        }
    }
    private static JsonObject permission(Object handler, String api) {
        try {
            Object value = AdapterAccess.call(handler, api);
            if (!(value instanceof Boolean)) return unavailable("public_fluid_permission_unavailable");
            JsonObject result = new JsonObject(); result.addProperty("available", true); result.addProperty("value", (Boolean) value); return result;
        } catch (RuntimeException | LinkageError error) { return unavailable("public_fluid_permission_unavailable"); }
    }
    private static JsonObject unavailable(String reason) { JsonObject result = new JsonObject(); result.addProperty("available", false); result.addProperty("reason", reason); return result; }
}
