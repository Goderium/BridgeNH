package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.client.nei.NeiGhostDragAdapter;
import com.Goderium.BridgeNH.common.ae.AePhantomSlots;
import com.Goderium.BridgeNH.common.inventory.GhostItemSource;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/** Recipe-viewer ghost ingredients, through the public widget's normal drag/click and sync path. */
final class PhantomGuiAdapter {
    private static final String ITEM = "com.cleanroommc.modularui.widgets.slot.PhantomItemSlot";
    private static final String MANAGER = "com.cleanroommc.modularui.value.sync.PanelSyncManager";

    static boolean item(Object widget) { return ExpandedGuiApi.is(widget, ITEM); }
    static boolean supports(Object widget) {
        if (AePhantomSlots.supports(widget)) return true;
        if (item(widget)) return true;
        if (!FluidGuiAdapter.supports(widget)) return false;
        try { return Boolean.TRUE.equals(ExpandedGuiApi.call(ExpandedGuiApi.call(widget, "getSyncHandler"), "isPhantom")); }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    static boolean available(Object widget, Object screen) {
        try { binding(widget, screen); return true; }
        catch (RuntimeException | LinkageError error) { return false; }
    }

    static JsonObject read(Object widget, Object screen) {
        JsonObject result = new JsonObject(); result.addProperty("available", false);
        result.addProperty("configurationOnly", true);
        result.addProperty("serverConfirmed",false);
        result.addProperty("verification","Client phantom display alone is not authoritative. gui.inspect waitFor compares registered phantom contents/NBT on both owner threads in an integrated world.");
        result.addProperty("source", "Public phantom widget and registered current-screen sync handler; ghost templates are not owned consumable stock.");
        result.addProperty("semantics", "ghost_drag submits one normal NEI drag. Native filters and limits apply; dragging the same type can decrement/clear it. Read first, then inspect again after sync; never blindly replay.");
        result.addProperty("debugSupply", "Debug inputs supply configured types without consuming the templates. A debug bus finite-mode amount is virtual availability, not finite material conservation.");
        try {
            if (!supports(widget)) return result;
            Object handler = ExpandedGuiApi.call(widget, "getSyncHandler");
            if (item(widget)) {
                Object raw = ExpandedGuiApi.call(widget, "getSlot");
                if (!(raw instanceof Slot)) return result;
                Slot slot = (Slot) raw;
                result.add("item", JsonData.item(slot.getStack()));
                result.addProperty("nativeContainerSlot", false);
                result.addProperty("kind", "item");
            } else {
                result.addProperty("kind", "fluid");
                result.addProperty("controlsAmount", (Boolean) ExpandedGuiApi.call(handler, "controlsAmount"));
            }
            result.addProperty("available", available(widget, screen));
            result.addProperty("dragAvailable", result.get("available").getAsBoolean()
                    && (item(widget) || Boolean.TRUE.equals(ExpandedGuiApi.call(handler, "canFillSlot"))));
            if (!result.get("available").getAsBoolean()) result.addProperty("reason", "current_public_phantom_sync_binding_unavailable");
        } catch (RuntimeException | LinkageError error) { result.addProperty("reason", "public_phantom_configuration_unavailable"); }
        return result;
    }

    static JsonObject drag(Object widget, Object screen, JsonObject args, InventoryPlayer inventory, BooleanSupplier stillCurrent) {
        if (!args.has("source") || !args.get("source").isJsonObject()) throw new BridgeException("invalid_args", "ghost_drag requires exactly one source object.");
        if (AePhantomSlots.supports(widget)) return AePhantomSlots.invoke(widget,screen,args,inventory,stillCurrent,true);
        Object handler = binding(widget, screen);
        if (!item(widget) && !Boolean.TRUE.equals(ExpandedGuiApi.call(handler, "canFillSlot")))
            throw new BridgeException("unsupported", "This phantom fluid slot does not allow filling.");
        JsonObject source = args.getAsJsonObject("source");
        if (source.has("fluidName") && item(widget)) throw new BridgeException("invalid_args", "fluidName is only valid for a phantom fluid slot.");
        ItemStack template = GhostItemSource.resolve(screen, source, inventory == null ? null : inventory.mainInventory);
        FluidStack fluid = null;
        if (!item(widget)) {
            Object raw = ExpandedGuiApi.call(ExpandedGuiApi.load(widget, "com.cleanroommc.modularui.utils.fluid.FluidInteractions"), "getFluidForItem", template.copy());
            if (!(raw instanceof FluidStack) || ((FluidStack) raw).getFluid() == null || ((FluidStack) raw).amount <= 0)
                throw new BridgeException("invalid_args", "Fluid ghost slots require a filled container or an actual NEI fluid display item.");
            fluid = (FluidStack) raw;
            if (source.has("fluidName") && !JsonData.string(source, "fluidName").equals(fluid.getFluid().getName()))
                throw new BridgeException("target_unverified", "The native NEI fluid template did not retain the requested registered identity.");
        }
        JsonObject result = new JsonObject(); result.add("template", JsonData.item(template));
        if (fluid != null) { result.addProperty("fluidName", fluid.getFluid().getName()); result.addProperty("fluidAmount", fluid.amount); }
        GuiControlAdapter.neutralInput();
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The current screen/container changed before ghost drag.");
        if (binding(widget, screen) != handler) throw new BridgeException("stale_widget", "The native phantom handler changed before ghost drag.");
        if (source.has("playerSlot")) {
            ItemStack current = inventory.getStackInSlot(JsonData.integer(source, "playerSlot", 0, 35));
            if (current == null) throw new BridgeException("value_changed", "The actual player source slot became empty.");
            ItemStack copy = current.copy(); copy.stackSize = template.stackSize;
            if (!ItemStack.areItemStacksEqual(copy, template)) throw new BridgeException("value_changed", "The actual player source identity/NBT changed before ghost drag.");
        }
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The current screen/container changed before ghost drag.");
        Object accepted = ExpandedGuiApi.call(widget, "handleDragAndDrop", template, 0);
        result.addProperty("accepted", Boolean.TRUE.equals(accepted));
        result.addProperty("nativeAction", "mui2_nei_ghost_drag");
        result.addProperty("serverConfirmed", false);
        result.addProperty("configurationOnly", true);
        result.addProperty("inventoryConsumed", false);
        if (Boolean.TRUE.equals(accepted) && stillCurrent.getAsBoolean())
            result.add("ghostRelease", NeiGhostDragAdapter.afterMark(screen));
        return result;
    }

    static JsonObject click(Object widget, Object screen, JsonObject args, InventoryPlayer inventory, BooleanSupplier stillCurrent) {
        if (AePhantomSlots.supports(widget)) return AePhantomSlots.invoke(widget,screen,args,inventory,stillCurrent,false);
        int button = JsonData.integer(args, "button", 0, item(widget) ? 1 : 2);
        Object handler = binding(widget, screen);
        if (inventory == null) throw new BridgeException("target_unverified", "The actual player cursor is required for phantom clicks.");
        GuiControlAdapter.neutralInput();
        if (!stillCurrent.getAsBoolean()) throw new BridgeException("stale_screen", "The current screen/container changed before phantom click.");
        if (binding(widget, screen) != handler) throw new BridgeException("stale_widget", "The native phantom handler changed before phantom click.");
        JsonObject result = new JsonObject(); result.addProperty("button", button);
        ExpandedGuiApi.call(widget, "onMousePressed", button);
        if (stillCurrent.getAsBoolean()) ExpandedGuiApi.call(widget, "onMouseRelease", button);
        else result.addProperty("remainingCallbacksSkipped", "native_screen_or_container_changed");
        result.addProperty("nativeAction", "mui2_phantom_native_click"); return result;
    }

    private static Object binding(Object widget, Object screen) {
        if (!supports(widget) || !ExpandedGuiApi.is(screen, "com.cleanroommc.modularui.api.IMuiScreen"))
            throw new BridgeException("unsupported", "This current screen has no supported public phantom slot.");
        Object handler = ExpandedGuiApi.call(widget, "getSyncHandler");
        if (!Boolean.TRUE.equals(ExpandedGuiApi.call(handler, "isPhantom")) || !Boolean.TRUE.equals(ExpandedGuiApi.call(handler, "isValid")))
            throw new BridgeException("unsupported", "The public phantom handler is not initialized.");
        Object manager = ExpandedGuiApi.call(handler, "getSyncManager"); Class<?> managerType = ExpandedGuiApi.named(manager == null ? null : manager.getClass(), MANAGER);
        if (managerType == null || !Boolean.TRUE.equals(ExpandedGuiApi.call(manager, "isInitialised"))
                || !Boolean.TRUE.equals(ExpandedGuiApi.call(manager, "isClient")) || !Boolean.TRUE.equals(ExpandedGuiApi.call(handler, "isRegistered", manager)))
            throw new BridgeException("unsupported", "The phantom handler is not registered in an initialized client sync manager.");
        Object modular = ExpandedGuiApi.call(screen, "getScreen"), sync = ExpandedGuiApi.call(modular, "getSyncManager");
        Object container = ExpandedGuiApi.call(modular, "getContainer"), panel = ExpandedGuiApi.call(manager, "getPanelName");
        if (container == null || container != ExpandedGuiApi.call(manager, "getContainer") || !(panel instanceof String)
                || ExpandedGuiApi.call(sync, "getPanelSyncManager", panel) != manager)
            throw new BridgeException("unsupported", "The phantom handler does not belong to the actual current screen/container.");
        try {
            if (widget.getClass().getMethod("handleDragAndDrop", ItemStack.class, int.class).getReturnType() != boolean.class)
                throw new NoSuchMethodException();
        } catch (NoSuchMethodException error) { throw new BridgeException("unsupported", "Public native NEI ghost drag API is unavailable."); }
        return handler;
    }
}
