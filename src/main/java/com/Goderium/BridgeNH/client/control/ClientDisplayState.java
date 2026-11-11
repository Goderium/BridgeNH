package com.Goderium.BridgeNH.client.control;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/** Read-only dimensions and mode evidence. Never activates, resizes, toggles fullscreen or saves preferences. */
public final class ClientDisplayState {
    public static JsonObject inspect(Minecraft client) {
        JsonObject result = new JsonObject();
        result.addProperty("observedAtMillis", System.currentTimeMillis());
        result.addProperty("clientWidth", client.displayWidth); result.addProperty("clientHeight", client.displayHeight);
        result.addProperty("clientFullscreen", client.isFullScreen());
        result.add("window", ClientWindowControls.inspect(client));
        if (client.gameSettings != null) {
            result.addProperty("fullscreenPreference", client.gameSettings.fullScreen);
            result.addProperty("guiScalePreference", client.gameSettings.guiScale);
        }
        if (client.currentScreen != null) {
            result.addProperty("guiWidth", client.currentScreen.width); result.addProperty("guiHeight", client.currentScreen.height);
        }
        result.addProperty("framebufferEnabled", OpenGlHelper.isFramebufferEnabled());
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer != null) {
            result.addProperty("framebufferWidth", framebuffer.framebufferWidth); result.addProperty("framebufferHeight", framebuffer.framebufferHeight);
            result.addProperty("textureWidth", framebuffer.framebufferTextureWidth); result.addProperty("textureHeight", framebuffer.framebufferTextureHeight);
        }
        boolean created = Display.isCreated(); result.addProperty("nativeDisplayCreated", created);
        if (created) {
            result.addProperty("nativeWidth", Display.getWidth()); result.addProperty("nativeHeight", Display.getHeight());
            result.addProperty("nativeFullscreen", Display.isFullscreen()); result.addProperty("nativeActive", Display.isActive());
            result.addProperty("nativeVisible", Display.isVisible());
            DisplayMode mode = Display.getDisplayMode();
            result.addProperty("modeWidth", mode.getWidth()); result.addProperty("modeHeight", mode.getHeight());
        }
        result.addProperty("scope", "current LWJGL/client/framebuffer dimensions; independent of scaled image size; no OS window capture or historical mode inference");
        return result;
    }
}
