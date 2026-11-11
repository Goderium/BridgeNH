package com.Goderium.BridgeNH.client.control;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;

/** Independent, opt-in controls. No native mouse setter or persistent options write. */
public class BackgroundControl {
    private MouseHelper originalMouse;
    private PassiveMouseHelper passive;
    private boolean mouseProtected;
    private boolean originalPause;
    private boolean unfocusedEnabled;

    public JsonObject change(Minecraft client, String control, boolean enabled, boolean pending, Runnable releaseInput) {
        return change(access(client), control, enabled, pending, releaseInput);
    }

    @com.google.common.annotations.VisibleForTesting
    public JsonObject change(Access client, String control, boolean enabled, boolean pending, Runnable releaseInput) {
        boolean nextMouse = mouseProtected;
        boolean nextUnfocused = unfocusedEnabled;
        if ("mouse".equals(control)) nextMouse = !enabled;
        else if ("unfocused".equals(control)) nextUnfocused = enabled;
        else if ("background".equals(control)) { nextMouse = enabled; nextUnfocused = enabled; }
        else throw new BridgeException("invalid_args", "Control must be mouse, unfocused or background.");
        if (pending && (!nextMouse || !nextUnfocused))
            throw new BridgeException("busy", "A lifecycle ticket is pending; mouse protection and unfocused running must remain enabled until it finishes.");
        boolean touchesMouse = !"unfocused".equals(control);
        MouseHelper beforeMouse = client.mouse();
        boolean beforePause = client.pause();
        if (touchesMouse) {
            if (beforeMouse == null) throw new BridgeException("invalid_state", "Vanilla MouseHelper initialization is incomplete.");
            if (mouseProtected && beforeMouse != passive)
                throw new BridgeException("invalid_state", "Another mod replaced MouseHelper. Its helper was preserved; use unfocused off to restore the pause setting and inspect before continuing.");
            if (nextMouse) requireUngrabbed(client.grabbed());
        }
        releaseInput.run();
        MouseHelper nextOriginal = originalMouse;
        PassiveMouseHelper nextPassive = passive;
        boolean nextOriginalPause = originalPause;
        MouseHelper writtenMouse = beforeMouse;
        try {
            if (touchesMouse && nextMouse && !mouseProtected) {
                nextOriginal = beforeMouse;
                nextPassive = new PassiveMouseHelper();
                writtenMouse = nextPassive;
                client.mouse(writtenMouse);
            }
            if (touchesMouse && (mouseProtected || nextMouse)) client.clearFocus();
            if (touchesMouse && !nextMouse && mouseProtected) {
                writtenMouse = originalMouse;
                client.mouse(writtenMouse);
                nextOriginal = null;
                nextPassive = null;
            }
            if (nextUnfocused) {
                if (!unfocusedEnabled) nextOriginalPause = beforePause;
                client.pause(false);
            } else if (unfocusedEnabled) client.pause(originalPause);
        } catch (RuntimeException | LinkageError failure) {
            // Do not overwrite a third-party helper installed during a failed transition.
            if (client.mouse() == writtenMouse && writtenMouse != beforeMouse) client.mouse(beforeMouse);
            client.pause(beforePause);
            throw new BridgeException("background_unavailable", "Control transition failed and its settings were restored: " + failure.getClass().getSimpleName());
        }
        originalMouse = nextOriginal;
        passive = nextPassive;
        mouseProtected = nextMouse;
        originalPause = nextOriginalPause;
        unfocusedEnabled = nextUnfocused;
        return inspect(client);
    }

    public void requireLifecycle(Minecraft client) { requireLifecycle(access(client)); }

    @com.google.common.annotations.VisibleForTesting
    public void requireLifecycle(Access client) {
        if (!mouseProtected || !unfocusedEnabled || client.mouse() != passive || client.pause())
            throw new BridgeException("background_required", "Lifecycle control requires full background mode. Enable client.background(true) or /mcb background on first; release a grabbed mouse normally with Esc or chat before takeover.");
        requireUngrabbed(client.grabbed());
    }

    @com.google.common.annotations.VisibleForTesting
    public static void requireUngrabbed(boolean grabbed) {
        if (grabbed) throw new BridgeException("mouse_grabbed", "The client has grabbed the native mouse. Open the pause menu with Esc or open chat normally first; the bridge will not release or warp your cursor.");
    }

    public void restoreForExit(Minecraft client, Runnable releaseInput) {
        restoreForExit(access(client), releaseInput);
    }

    @com.google.common.annotations.VisibleForTesting
    public void restoreForExit(Access client, Runnable releaseInput) { change(client, "background", false, false, releaseInput); }

    public void tick(Minecraft client) { tick(access(client)); }

    @com.google.common.annotations.VisibleForTesting
    public void tick(Access client) {
        if (unfocusedEnabled) client.pause(false);
        if (mouseProtected && client.mouse() == passive) client.clearFocus();
    }

    public JsonObject inspect(Minecraft client) { return inspect(access(client)); }

    public boolean active(Minecraft client) { return active(access(client)); }

    @com.google.common.annotations.VisibleForTesting
    public boolean active(Access client) {
        return mouseProtected && client.mouse() == passive && unfocusedEnabled && !client.pause() && !client.grabbed();
    }

    @com.google.common.annotations.VisibleForTesting
    public JsonObject inspect(Access client) {
        boolean installed = mouseProtected && client.mouse() == passive;
        JsonObject result = new JsonObject();
        result.addProperty("enabled", installed && unfocusedEnabled && !client.pause());
        result.addProperty("mouseEnabled", !installed);
        result.addProperty("mouseOverrideEnabled", mouseProtected);
        result.addProperty("unfocusedEnabled", unfocusedEnabled);
        result.addProperty("mouseProtectionActive", installed);
        result.addProperty("helperConflict", mouseProtected && !installed);
        result.addProperty("pauseOnLostFocus", client.pause());
        if (unfocusedEnabled) result.addProperty("originalPauseOnLostFocus", originalPause);
        result.addProperty("helperInstalled", installed);
        result.addProperty("mouseHelperClass", client.mouse() == null ? "" : client.mouse().getClass().getName());
        result.addProperty("releasedExistingGrab", false);
        result.addProperty("mousePolicy", "Manual mouse is the default. mouse off installs passive protection; mouse on restores the original helper without native grab, release or cursor warp. A pre-existing native grab must be released normally by the user before takeover.");
        result.addProperty("persistence", "Unfocused running overrides pauseOnLostFocus in memory. Turning it off or controlled exit restores the prior value; bridge never saves options. Other game code saving options during the override may persist the temporary value.");
        return result;
    }

    @com.google.common.annotations.VisibleForTesting
    public interface Access {
        MouseHelper mouse();
        void mouse(MouseHelper helper);
        boolean pause();
        void pause(boolean pause);
        boolean grabbed();
        void clearFocus();
    }

    private static Access access(Minecraft client) {
        if (client.gameSettings == null) throw new BridgeException("invalid_state", "Vanilla settings initialization is incomplete.");
        return new Access() {
            public MouseHelper mouse() { return client.mouseHelper; }
            public void mouse(MouseHelper helper) { client.mouseHelper = helper; }
            public boolean pause() { return client.gameSettings.pauseOnLostFocus; }
            public void pause(boolean value) { client.gameSettings.pauseOnLostFocus = value; }
            public boolean grabbed() { return Mouse.isCreated() && Mouse.isGrabbed(); }
            public void clearFocus() { client.setIngameNotInFocus(); }
        };
    }

    @com.google.common.annotations.VisibleForTesting
    public static class PassiveMouseHelper extends MouseHelper {
        @Override public void grabMouseCursor() { deltaX = 0; deltaY = 0; }
        @Override public void ungrabMouseCursor() { deltaX = 0; deltaY = 0; }
        @Override public void mouseXYChange() { deltaX = 0; deltaY = 0; }
    }
}
