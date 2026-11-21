package com.Goderium.BridgeNH.client.gui;

import java.io.IOException;
import java.io.InputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Renders the agent cursor in the current GUI coordinate system. All layer offsets are in art pixels. */
final class GuiMouseOverlay {
    enum Appearance { NORMAL, PRESSED, TEXT, BUSY }

    private enum Sprite {
        NORMAL("pointer"), PRESSED("pointer_pressed"), TEXT("pointer_text"), BUSY("pointer_busy"),
        ARROW("animation/arrow"), PRESSED_BASE("animation/pressed_base"), HALO("animation/interaction_halo"),
        CLOUD("animation/cloud_idle", 64, 64, 16, 1),
        GAZE("animation/eyes_gaze", 64, 64, 21, 21), PRESSED_GAZE("animation/eyes_gaze_pressed", 64, 64, 21, 21),
        IBEAM("animation/text_ibeam", 80, 80, 1, 1), GLASSES("animation/text_glasses", 64, 64, 1, 1),
        RING("animation/busy_ring", 80, 80, 8, 4), THOUGHT("animation/busy_thought", 56, 48, 8, 4);

        final ResourceLocation texture;
        final int width, height, columns, rows;
        boolean available;

        Sprite(String name) { this(name, 384, 384, 1, 1); }
        Sprite(String name, int width, int height, int columns, int rows) {
            this.texture = new ResourceLocation("codexbridge", "textures/gui/" + name + ".png");
            this.width = width;
            this.height = height;
            this.columns = columns;
            this.rows = rows;
        }
        void draw(Minecraft client, int frame, int x, int y) {
            client.getTextureManager().bindTexture(texture);
            Gui.drawModalRectWithCustomSizedTexture(x, y, frame % columns * width, frame / columns * height,
                    width, height, width * columns, height * rows);
        }
    }

    private static IResourceManager manager;
    private static boolean normalLayers, pressedLayers, textLayers, busyLayers;
    private boolean tracking, lastWasPointer;
    private int lastX, lastY;
    private long started, lastFrame, lastMoved;
    private double targetX, targetY, gazeX, gazeY;

    private static void reload(IResourceManager resources) {
        for (Sprite sprite : Sprite.values()) {
            try (InputStream input = resources.getResource(sprite.texture).getInputStream()) { sprite.available = true; }
            catch (IOException missing) { sprite.available = false; }
        }
        boolean cloudEyes = Sprite.CLOUD.available && Sprite.GAZE.available;
        normalLayers = cloudEyes && Sprite.ARROW.available;
        pressedLayers = Sprite.PRESSED_BASE.available && Sprite.PRESSED_GAZE.available;
        textLayers = cloudEyes && Sprite.HALO.available && Sprite.IBEAM.available && Sprite.GLASSES.available;
        busyLayers = cloudEyes && Sprite.HALO.available && Sprite.RING.available && Sprite.THOUGHT.available;
    }

    void reset() { tracking = false; }

    private int updateGaze(int x, int y, boolean busy, long now) {
        if (!tracking) {
            tracking = true;
            lastFrame = started = now;
            lastMoved = now - 150_000_000L;
            lastX = x;
            lastY = y;
            gazeX = gazeY = targetX = targetY = 0;
        }
        int dx = x - lastX, dy = y - lastY;
        int distance = Math.max(Math.abs(dx), Math.abs(dy));
        if (distance > 0) {
            targetX = (double) dx / distance;
            targetY = (double) dy / distance;
            lastMoved = now;
            lastX = x;
            lastY = y;
        }
        boolean resting = now - lastMoved >= 150_000_000L;
        if (resting) {
            double phase = (now - started) % 2_560_000_000L * (2 * Math.PI / 2_560_000_000L);
            targetX = busy ? .58 + .14 * Math.sin(phase) : 0;
            targetY = busy ? -.72 + .09 * Math.cos(phase) : 0;
        }
        double dt = Math.min(.05, (now - lastFrame) / 1e9);
        lastFrame = now;
        double error = Math.max(Math.abs(targetX - gazeX), Math.abs(targetY - gazeY));
        if (error > 0) {
            double step = Math.min((resting ? 3.6 : 5.1) * dt / error, -Math.expm1(-dt / (resting ? .060 : .042)));
            gazeX += step * (targetX - gazeX);
            gazeY += step * (targetY - gazeY);
        }
        return (10 + (int) Math.round(10 * gazeY)) * 21 + 10 + (int) Math.round(10 * gazeX);
    }

    void drawPointer(Minecraft client, int x, int y, Appearance appearance, long now) {
        draw(client, x - 40, y - 40, true, appearance, now);
    }

    void drawStatus(Minecraft client, int width, int height, Appearance appearance, long now) {
        // A 64-pixel logo spans 16 scaled GUI units, centered below the vanilla crosshair.
        draw(client, width / 2 - 8, height / 2 + 9, false, appearance, now);
    }

    private void draw(Minecraft client, int x, int y, boolean pointer, Appearance appearance, long now) {
        if (manager != client.getResourceManager()) {
            manager = client.getResourceManager();
            if (manager instanceof IReloadableResourceManager)
                ((IReloadableResourceManager) manager).registerReloadListener(GuiMouseOverlay::reload);
            else reload(manager);
        }
        if (lastWasPointer != pointer) {
            lastX = x;
            lastY = y;
            lastWasPointer = pointer;
        }
        int eyes = updateGaze(x, y, appearance == Appearance.BUSY, now);
        long millis = (now - started) / 1_000_000L;
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_CURRENT_BIT | GL11.GL_TEXTURE_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(x, y, 0);
            GL11.glScalef(.25f, .25f, 1);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1, 1, 1, 1);
            if (pointer) drawLayers(client, appearance, eyes, millis);
            else drawBadge(client, appearance, eyes, millis);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void drawBadge(Minecraft mc, Appearance appearance, int eyes, long millis) {
        if (Sprite.CLOUD.available) Sprite.CLOUD.draw(mc, (int) (millis / 160 % 16), 0, 0);
        if (Sprite.GAZE.available) Sprite.GAZE.draw(mc, eyes, 0, 0);
        if (appearance == Appearance.TEXT && Sprite.GLASSES.available) Sprite.GLASSES.draw(mc, 0, 0, 0);
        if (appearance == Appearance.BUSY && Sprite.THOUGHT.available) Sprite.THOUGHT.draw(mc, (int) (millis / 80 % 32), 35, -31);
    }

    private static void drawLayers(Minecraft mc, Appearance appearance, int eyes, long millis) {
        int cloudFrame = (int) (millis / 160 % 16);
        switch (appearance) {
            case BUSY:
                if (!busyLayers) { Sprite.BUSY.draw(mc, 0, 0, 0); return; }
                Sprite.HALO.draw(mc, 0, 0, 0);
                Sprite.RING.draw(mc, (int) (millis / 40 % 32), 120, 120);
                Sprite.CLOUD.draw(mc, cloudFrame, 184, 184);
                Sprite.GAZE.draw(mc, eyes, 184, 184);
                Sprite.THOUGHT.draw(mc, (int) (millis / 80 % 32), 219, 153);
                return;
            case TEXT:
                if (!textLayers) { Sprite.TEXT.draw(mc, 0, 0, 0); return; }
                Sprite.HALO.draw(mc, 0, 0, 0);
                Sprite.IBEAM.draw(mc, 0, 120, 120);
                Sprite.CLOUD.draw(mc, cloudFrame, 176, 174);
                Sprite.GAZE.draw(mc, eyes, 176, 174);
                Sprite.GLASSES.draw(mc, 0, 176, 174);
                return;
            case PRESSED:
                if (!pressedLayers) { Sprite.PRESSED.draw(mc, 0, 0, 0); return; }
                Sprite.PRESSED_BASE.draw(mc, 0, 0, 0);
                Sprite.PRESSED_GAZE.draw(mc, eyes, 216, 216);
                return;
            default:
                if (!normalLayers) { Sprite.NORMAL.draw(mc, 0, 0, 0); return; }
                Sprite.ARROW.draw(mc, 0, 0, 0);
                Sprite.CLOUD.draw(mc, cloudFrame, 216, 216);
                Sprite.GAZE.draw(mc, eyes, 216, 216);
        }
    }
}
