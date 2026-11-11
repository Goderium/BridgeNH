package com.Goderium.BridgeNH.common.structure;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import cpw.mods.fml.common.Loader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Optional normal BogoSorter GUI input preparation; never writes its guard or inventory. */
public final class BogoCreativeInput {
    private static final String PREFIX = "com.cleanroommc.bogosorter.";
    interface Api {
        KeyBinding[] bindings() throws ReflectiveOperationException;
        void refreshKeys() throws ReflectiveOperationException;
        boolean shortcutActive() throws ReflectiveOperationException;
        boolean handleInput(GuiContainer screen) throws ReflectiveOperationException;
    }
    interface Input {
        void requireNeutral();
        boolean eventPressed(KeyBinding binding);
    }

    public static JsonObject prepare(GuiContainer screen, Slot backing, EntityPlayer player) {
        return prepareOptional(screen, backing, player, true);
    }

    /** Ordinary clicks may insert into a slot that deliberately disallows extraction. */
    public static JsonObject prepareSlotClick(GuiContainer screen, Slot backing, EntityPlayer player) {
        return prepareOptional(screen, backing, player, false);
    }

    private static JsonObject prepareOptional(GuiContainer screen, Slot backing, EntityPlayer player, boolean requireTakeable) {
        ClassLoader loader = screen.getClass().getClassLoader();
        Class<?> handler;
        try { handler = Class.forName(PREFIX + "ClientEventHandler", false, loader); }
        catch (ClassNotFoundException absent) { return absent(); }
        catch (LinkageError error) { throw unavailable(error); }
        if (!Loader.isModLoaded("bogosorter")) return absent();
        try { return prepare(screen, backing, player, new PublicApi(loader, handler), new NativeInput(), requireTakeable); }
        catch (ReflectiveOperationException | LinkageError error) { throw unavailable(error); }
    }

    static JsonObject prepare(GuiContainer screen, Slot backing, EntityPlayer player, Api api, Input input) {
        return prepare(screen, backing, player, api, input, true);
    }

    static JsonObject prepareSlotClick(GuiContainer screen, Slot backing, EntityPlayer player, Api api, Input input) {
        return prepare(screen, backing, player, api, input, false);
    }

    private static JsonObject prepare(GuiContainer screen, Slot backing, EntityPlayer player, Api api, Input input, boolean requireTakeable) {
        try {
            input.requireNeutral();
            KeyBinding[] bindings = api.bindings();
            if (bindings == null || bindings.length > 16) throw new BridgeException("unsupported", "BogoSorter input bindings exceed the fixed preparation bound; no pickup was sent.");
            for (KeyBinding binding : bindings) {
                if (binding == null) throw new BridgeException("unsupported", "A BogoSorter input binding is unavailable; no pickup was sent.");
                if (binding.getKeyCode() != 0 && (binding.getIsKeyPressed() || pendingPresses(binding) > 0 || input.eventPressed(binding)))
                    throw new BridgeException("input_busy", "A BogoSorter shortcut is held, pending or present in the last input event; no shortcut or pickup was sent.");
            }
            api.refreshKeys();
            if (api.shortcutActive()) throw new BridgeException("input_busy", "An active BogoSorter key combination prevents neutral pickup; no shortcut or pickup was sent.");
            if (api.handleInput(screen)) throw new BridgeException("input_busy", "The normal BogoSorter input preparation consumed the input; no native pickup was sent.");
            boolean takeable = backing != null && backing.canTakeStack(player);
            if (requireTakeable && !takeable) throw new BridgeException("input_busy", "Normal BogoSorter input preparation still refuses this slot, possibly during shortcut cooldown; no native pickup was sent.");
            JsonObject result = new JsonObject(); result.addProperty("adapter", "bogosorter_public_gui_input");
            result.addProperty("prepared", true); result.addProperty("consumed", false);
            result.addProperty("targetSlotPresent", backing != null); result.addProperty("takeabilityRequired", requireTakeable);
            if (backing != null) result.addProperty("canTakeStackAfterInput", takeable);
            result.addProperty("publicPath", "KeyBind.checkKeys(ClientEventHandler.getTicks()); ClientEventHandler.handleInput(actualGuiContainer, true)");
            result.addProperty("guardWrites", false); result.addProperty("hoverWrites", false); result.addProperty("inventoryWrites", false);
            return result;
        } catch (BridgeException error) { throw error; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) { throw unavailable(error); }
    }

    /** isPressed consumes the vanilla pending counter; this fixed mapping only reads it. */
    static int pendingPresses(KeyBinding binding) throws ReflectiveOperationException {
        for (String name : new String[] {"pressTime", "field_151474_i"}) {
            try { Field field = KeyBinding.class.getDeclaredField(name); field.setAccessible(true); return field.getInt(binding); }
            catch (NoSuchFieldException absent) { /* Fixed development/production name only. */ }
        }
        throw new NoSuchFieldException("KeyBinding pending press counter");
    }

    private static JsonObject absent() {
        JsonObject result = new JsonObject(); result.addProperty("adapter", "not_loaded"); result.addProperty("prepared", false); return result;
    }
    private static BridgeException unavailable(Throwable error) {
        return new BridgeException("unsupported", "The fixed public BogoSorter neutral input preparation is unavailable (" + error.getClass().getSimpleName() + "); no native pickup was sent.");
    }

    private static final class NativeInput implements Input {
        public void requireNeutral() {
            if (!Keyboard.isCreated() || !Mouse.isCreated()) throw new BridgeException("unsupported", "Physical input state is unavailable for BogoSorter preparation; no pickup was sent.");
            int keyCount = Keyboard.getKeyCount(), buttonCount = Mouse.getButtonCount();
            if (keyCount < 0 || keyCount > 1024 || buttonCount < 0 || buttonCount > 32)
                throw new BridgeException("unsupported", "Physical input state exceeds the bounded BogoSorter guard; no pickup was sent.");
            for (int key = 0; key < keyCount; key++) if (Keyboard.isKeyDown(key))
                throw new BridgeException("input_busy", "Release physical keys before BogoSorter native inventory pickup; no pickup was sent.");
            for (int button = 0; button < buttonCount; button++) if (Mouse.isButtonDown(button))
                throw new BridgeException("input_busy", "Release physical mouse buttons before BogoSorter native inventory pickup; no pickup was sent.");
        }
        public boolean eventPressed(KeyBinding binding) {
            int code = binding.getKeyCode();
            return code > 0 ? Keyboard.getEventKeyState() && Keyboard.getEventKey() == code
                    : code < 0 && Mouse.getEventButtonState() && Mouse.getEventButton() == code + 100;
        }
    }

    private static final class PublicApi implements Api {
        final Class<?> bindingsClass;
        final Method ticks, handle, refresh, definitions, active, pressed, held;
        final Field[] keys = new Field[5];
        PublicApi(ClassLoader loader, Class<?> handler) throws ReflectiveOperationException {
            Class<?> key = Class.forName(PREFIX + "client.keybinds.KeyBind", false, loader);
            bindingsClass = Class.forName(PREFIX + "client.keybinds.control.BSKeybinds", false, loader);
            Class<?> definition = Class.forName(PREFIX + "client.keybinds.control.BSKeybinds$KeybindDefinition", false, loader);
            ticks = handler.getMethod("getTicks"); handle = handler.getMethod("handleInput", GuiContainer.class, boolean.class);
            refresh = key.getMethod("checkKeys", long.class); definitions = bindingsClass.getMethod("getAllKeybinds");
            active = bindingsClass.getMethod("getActiveKeyBind", definition); pressed = key.getMethod("isPressed"); held = key.getMethod("areKeysHeld");
            String[] names = {"sortKeyInGUI", "sortKeyOutsideGUI", "configGuiKey", "dropoffKey", "pinSlotKey"};
            for (int i = 0; i < names.length; i++) keys[i] = bindingsClass.getField(names[i]);
        }
        public KeyBinding[] bindings() throws ReflectiveOperationException {
            KeyBinding[] result = new KeyBinding[keys.length];
            for (int i = 0; i < keys.length; i++) result[i] = (KeyBinding) keys[i].get(null);
            return result;
        }
        public void refreshKeys() throws ReflectiveOperationException { refresh.invoke(null, ((Number) ticks.invoke(null)).longValue()); }
        public boolean shortcutActive() throws ReflectiveOperationException {
            Object[] values = (Object[]) definitions.invoke(null);
            if (values == null || values.length > 16) throw new BridgeException("unsupported", "BogoSorter public shortcut definitions exceed the preparation bound; no pickup was sent.");
            for (Object definition : values) {
                Object key = active.invoke(null, definition);
                if (key != null && (Boolean.TRUE.equals(pressed.invoke(key)) || Boolean.TRUE.equals(held.invoke(key)))) return true;
            }
            return false;
        }
        public boolean handleInput(GuiContainer screen) throws ReflectiveOperationException { return Boolean.TRUE.equals(handle.invoke(null, screen, true)); }
    }
}
