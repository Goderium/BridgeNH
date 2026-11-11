package com.Goderium.BridgeNH.client.gui;

import com.Goderium.BridgeNH.common.structure.BogoCreativeInput;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/** Fixed native callbacks only; no caller chooses a method or creative click mode. */
public class GuiCallbacks {
    public static final int[] NAVIGATION_KEYS = {1, 14, 15, 28, 156, 199, 200, 201, 203, 205, 207, 208, 209, 211};
    private static final Method PRESS = find(new String[] {"mouseClicked", "func_73864_a"}, int.class, int.class, int.class);
    private static final Method RELEASE = find(new String[] {"mouseReleased", "mouseMovedOrUp", "func_146286_b"}, int.class, int.class, int.class);
    private static final Method KEY = find(new String[] {"keyTyped", "func_73869_a"}, char.class, int.class);
    private static final Field BUTTONS = buttonsField();

    private static Field buttonsField() {
        for (String name : new String[] {"buttonList", "field_146292_n"}) {
            try {
                Field field = GuiScreen.class.getDeclaredField(name);
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true); return field;
            } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        }
        return null;
    }

    /** The screen's current actual button list, even when its init bypassed Forge's event. */
    public static List<?> buttons(GuiScreen screen, List<?> captured) {
        if (screen == null) return Collections.emptyList();
        if (BUTTONS != null) {
            try { Object value = BUTTONS.get(screen); if (value instanceof List<?>) return (List<?>) value; }
            catch (ReflectiveOperationException | RuntimeException unavailable) { }
        }
        return captured == null ? Collections.emptyList() : captured;
    }

    private static Method find(String[] names, Class<?>... parameters) {
        return find(GuiScreen.class, names, parameters);
    }

    private static Method find(Class<?> owner, String[] names, Class<?>... parameters) {
        for (String name : names) {
            try {
                Method method = owner.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException ignored) { }
        }
        return null;
    }

    public static boolean supportsClick() { return PRESS != null && RELEASE != null; }

    public static boolean supportsKey() { return KEY != null; }

    public static boolean supportsCreativePickup() { return true; }

    /** A void key/click callback returning is submission evidence only, never proof of the intended effect. */
    public static JsonObject inputReceipt(JsonObject before, JsonObject after, boolean currentScreen) {
        JsonObject result = new JsonObject(); result.addProperty("nativeCallbackCompleted", true);
        result.add("nativeAccepted", JsonNull.INSTANCE); result.addProperty("serverConfirmed", false);
        result.addProperty("currentScreen", currentScreen); result.addProperty("confirmation", "local_native_callback_only");
        if (before != null) result.add("beforeInput", before);
        if (after != null) result.add("afterInput", after);
        boolean comparable = currentScreen && before != null && after != null && before.has("screenId") && after.has("screenId")
                && before.get("screenId").equals(after.get("screenId")) && before.has("values") && after.has("values");
        if (comparable) {
            result.addProperty("localEffectObserved", !before.get("values").equals(after.get("values")));
            result.addProperty("observationComplete", before.has("complete") && after.has("complete")
                    && before.get("complete").getAsBoolean() && after.get("complete").getAsBoolean());
            result.addProperty("confirmation", "local_widget_observation_only");
        } else {
            result.add("localEffectObserved", JsonNull.INSTANCE); result.addProperty("observationComplete", false);
        }
        result.addProperty("completion", "Native callback returned. Only reported client widget changes were observed; no changed widget, acceptance or server effect is inferred from callback completion.");
        return result;
    }

    /** Resolves the real InventoryPlayer index and container slot without assuming wrapper numbering. */
    static Slot creativeBacking(GuiScreen screen, Slot wrapper, InventoryPlayer inventory) {
        if (!(screen instanceof GuiContainerCreative) || screen.mc == null || screen.mc.thePlayer == null
                || screen.mc.thePlayer.inventory != inventory || wrapper.inventory != inventory)
            throw new BridgeException("stale_widget", "The creative slot's current player binding is unavailable.");
        EntityPlayer player = screen.mc.thePlayer;
        Container container = player.inventoryContainer;
        if (container == null || container.inventorySlots.size() > 4096)
            throw new BridgeException("target_unverified", "The bounded player inventory container is unavailable.");
        Slot backing = null;
        for (Object value : container.inventorySlots) {
            if (!(value instanceof Slot)) continue;
            Slot candidate = (Slot) value;
            // CreativeSlot.isHere delegates to its actual backing Slot; wrapper.getSlotIndex is a different index.
            if (candidate.inventory == inventory && wrapper.isHere(inventory, candidate.getSlotIndex())) {
                if (backing != null) throw new BridgeException("target_unverified", "The public creative slot binding is ambiguous.");
                backing = candidate;
            }
        }
        if (backing == null || backing.slotNumber < 0 || backing.slotNumber >= container.inventorySlots.size()
                || container.getSlot(backing.slotNumber) != backing || backing.getStack() != wrapper.getStack())
            throw new BridgeException("target_unverified", "The exact public creative backing slot could not be verified.");
        return backing;
    }

    /** Same public container path as vanilla's creative inventory-tab override, with no private wrapper access. */
    static JsonObject creativePickup(GuiScreen screen, Slot wrapper, InventoryPlayer inventory) {
        Slot backing = creativeBacking(screen, wrapper, inventory);
        EntityPlayer player = screen.mc.thePlayer;
        Container container = player.inventoryContainer;
        JsonObject observed = new JsonObject();
        observed.addProperty("nativeContainerSlotId", backing.slotNumber);
        observed.addProperty("nativeCallBinding", "typed_compiled");
        observed.addProperty("actualContainerClass", JsonData.bounded(container.getClass().getName(), 256));
        observed.addProperty("actualSlotClass", JsonData.bounded(backing.getClass().getName(), 256));
        observed.addProperty("actualWrapperClass", JsonData.bounded(wrapper.getClass().getName(), 256));
        observed.addProperty("containerWindowId", container.windowId);
        observed.addProperty("backingInventoryIndex", backing.getSlotIndex());
        observePickupMethod(observed, container);
        try { observed.addProperty("canTakeStackBeforeInput", backing.canTakeStack(player)); }
        catch (RuntimeException | LinkageError error) { observed.addProperty("canTakeStackBeforeInputError", error.getClass().getName()); }
        observed.add("inputPreparation", BogoCreativeInput.prepare((GuiContainerCreative) screen, backing, player));
        if (creativeBacking(screen, wrapper, inventory) != backing || player.inventoryContainer != container)
            throw new BridgeException("stale_widget", "The creative backing binding changed during input preparation; no native pickup was sent.");
        try { observed.addProperty("canTakeStack", backing.canTakeStack(player)); }
        catch (RuntimeException | LinkageError error) { observed.addProperty("canTakeStackError", error.getClass().getName()); }
        observed.add("beforeNative", pickupSnapshot(backing, inventory));
        ItemStack returned = container.slotClick(backing.slotNumber, 0, 0, player);
        observed.add("returnedStack", pickupItem(returned));
        observed.add("afterNative", pickupSnapshot(backing, inventory));
        container.detectAndSendChanges();
        observed.add("afterSync", pickupSnapshot(backing, inventory));
        observed.addProperty("nativeStateChanged", !observed.get("beforeNative").equals(observed.get("afterNative")));
        observed.addProperty("synchronizationChangedState", !observed.get("afterNative").equals(observed.get("afterSync")));
        observed.addProperty("coverage", "Fixed read-only public method metadata and actual native container slot/carried snapshots. No retry, inventory write, NBT dump or runtime bytecode inspection. Method metadata does not prove the transformed implementation body.");
        return observed;
    }

    private static void observePickupMethod(JsonObject observed, Container container) {
        for (String name : new String[] {"func_75144_a", "slotClick"}) {
            try {
                Method method = container.getClass().getMethod(name, int.class, int.class, int.class, EntityPlayer.class);
                observed.addProperty("actualDeclaredContainerClass", JsonData.bounded(method.getDeclaringClass().getName(), 256));
                observed.addProperty("actualPublicMethodName", method.getName());
                observed.addProperty("nativeCallback", method.getDeclaringClass().getName() + "." + method.getName());
                observed.addProperty("methodResolution", "read_only_fixed_public_signature");
                return;
            } catch (NoSuchMethodException absent) { /* Development/production metadata only; no reflective invocation. */ }
            catch (RuntimeException | LinkageError error) {
                observed.addProperty("methodResolution", "unavailable");
                observed.addProperty("methodResolutionError", error.getClass().getName());
                return;
            }
        }
        observed.addProperty("methodResolution", "unavailable");
    }

    private static JsonObject pickupSnapshot(Slot backing, InventoryPlayer inventory) {
        JsonObject state = new JsonObject();
        try {
            state.add("slotItem", pickupItem(backing.getStack()));
            state.add("carriedItem", pickupItem(inventory.getItemStack()));
        } catch (RuntimeException | LinkageError error) { state.addProperty("snapshotError", error.getClass().getName()); }
        return state;
    }

    private static com.google.gson.JsonElement pickupItem(ItemStack stack) {
        if (stack == null) return JsonNull.INSTANCE;
        JsonObject item = new JsonObject();
        try {
            item.addProperty("registryName", JsonData.bounded(String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem())), 256));
            item.addProperty("metadata", stack.getMetadata()); item.addProperty("count", stack.stackSize);
            item.addProperty("hasTag", stack.hasTagCompound());
        } catch (RuntimeException | LinkageError error) { item.addProperty("snapshotError", error.getClass().getName()); }
        return item;
    }

    public static JsonObject click(GuiScreen screen, int x, int y, int button, BooleanSupplier stillCurrent) {
        requireCoordinateClick(screen.getClass());
        if (PRESS == null || RELEASE == null) throw new BridgeException("unsupported", "GuiScreen mouse callbacks are unavailable in this environment.");
        int width = screen.width, height = screen.height;
        BooleanSupplier current = () -> screen.width == width && screen.height == height && stillCurrent.getAsBoolean();
        return LogicalGuiPointer.input(screen, x, y, current, () -> {
            invoke(PRESS, screen, x, y, button);
            if (current.getAsBoolean()) invoke(RELEASE, screen, x, y, button);
        });
    }

    @com.google.common.annotations.VisibleForTesting
    public static void requireCoordinateClick(Class<?> screenType) {
        if (implementsMuiScreen(screenType))
            throw new BridgeException("unsupported", "ModularUI2 coordinate clicks are unsupported. Use gui.slot_click for ordinary item slots and native mouse input for widgets.");
        for (Class<?> type = screenType; type != null; type = type.getSuperclass()) {
            if ("appeng.client.gui.AEBaseGui".equals(type.getName()))
                throw new BridgeException("unsupported", "AEBaseGui coordinate clicks require cached physical hover. Use ae.action for resources, patterns and cell_config_set(source ghost template), gui.widget_action for advertised controls, or gui.slot_click for real inventory slots. Verify actual synchronized config, never a drag icon.");
        }
    }

    private static boolean implementsMuiScreen(Class<?> type) {
        if (type == null) return false;
        if ("com.cleanroommc.modularui.api.IMuiScreen".equals(type.getName())) return true;
        for (Class<?> implemented : type.getInterfaces()) {
            if (implementsMuiScreen(implemented)) return true;
        }
        return implementsMuiScreen(type.getSuperclass());
    }

    public static void key(GuiScreen screen, int key, String character) {
        if (KEY == null) throw new BridgeException("unsupported", "GuiScreen key callback is unavailable in this environment.");
        if (key == 0) {
            if (character.length() != 1 || Character.isISOControl(character.charAt(0)) || Character.isSurrogate(character.charAt(0)))
                throw new BridgeException("invalid_args", "Character input requires keyCode 0 and one printable character.");
        } else {
            boolean supported = false;
            for (int allowed : NAVIGATION_KEYS) if (allowed == key) supported = true;
            if (!supported || !character.isEmpty()) throw new BridgeException("unsupported", "Use a supported navigation key without character, or keyCode 0 with one printable character.");
        }
        invoke(KEY, screen, character.isEmpty() ? '\0' : character.charAt(0), key);
    }

    private static void invoke(Method method, GuiScreen screen, Object... args) {
        try { method.invoke(screen, args); }
        catch (IllegalAccessException e) { throw new BridgeException("unsupported", "GuiScreen callback is inaccessible."); }
        catch (InvocationTargetException e) {
            throw new BridgeException("gui_error", "Game GUI callback failed: " + e.getCause().getClass().getSimpleName());
        }
    }
}
