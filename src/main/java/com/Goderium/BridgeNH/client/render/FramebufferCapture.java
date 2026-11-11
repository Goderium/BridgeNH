package com.Goderium.BridgeNH.client.render;

import com.Goderium.BridgeNH.client.control.ClientDisplayState;
import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Base64;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class FramebufferCapture {
    /** Called at render-tick END, on the thread that owns the GL context. */
    public static JsonObject capture(Minecraft client) throws IOException {
        Framebuffer framebuffer = client.getFramebuffer();
        boolean useTexture = OpenGlHelper.isFramebufferEnabled();
        int width = useTexture ? framebuffer.framebufferTextureWidth : client.displayWidth;
        int height = useTexture ? framebuffer.framebufferTextureHeight : client.displayHeight;
        int actualWidth = useTexture ? framebuffer.framebufferWidth : width;
        int actualHeight = useTexture ? framebuffer.framebufferHeight : height;
        validateDimensions(width, height, actualWidth, actualHeight);
        JsonObject display = ClientDisplayState.inspect(client);
        long capturedAt = System.currentTimeMillis();
        IntBuffer pixels = BufferUtils.createIntBuffer(width * height);
        int packAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            if (useTexture) {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, framebuffer.framebufferTexture);
                GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            } else {
                GL11.glReadPixels(0, 0, width, height, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixels);
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, packAlignment);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
        }
        BufferedImage original = pixelsToImage(pixels, width, height, actualWidth, actualHeight);
        BufferedImage output = original;
        if (Math.max(actualWidth, actualHeight) > 1600) {
            double scale = 1600.0 / Math.max(actualWidth, actualHeight);
            output = new BufferedImage(Math.max(1, (int) (actualWidth * scale)), Math.max(1, (int) (actualHeight * scale)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(original, 0, 0, output.getWidth(), output.getHeight(), null);
            } finally { graphics.dispose(); }
        }
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(output, "png", encoded)) throw new BridgeException("unsupported", "PNG encoding is unavailable.");
        if (encoded.size() > 8 * 1024 * 1024) throw new BridgeException("result_too_large", "Screenshot exceeds 8 MiB.");
        JsonObject result = new JsonObject();
        result.addProperty("mimeType", "image/png");
        result.addProperty("base64", Base64.getEncoder().encodeToString(encoded.toByteArray()));
        result.addProperty("width", output.getWidth());
        result.addProperty("height", output.getHeight());
        result.addProperty("originalWidth", actualWidth);
        result.addProperty("originalHeight", actualHeight);
        result.addProperty("scaled", output != original);
        result.addProperty("source", useTexture ? "client_framebuffer_texture" : "current_gl_read_buffer");
        result.addProperty("sourceTextureWidth", width); result.addProperty("sourceTextureHeight", height);
        result.addProperty("capturedAtMillis", capturedAt);
        result.addProperty("renderPhase", "RenderTick.END"); result.addProperty("cached", false);
        result.add("displayState", display);
        return result;
    }

    @com.google.common.annotations.VisibleForTesting
    public static void validateDimensions(int width, int height, int actualWidth, int actualHeight) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 16777216
                || actualWidth < 1 || actualHeight < 1 || actualWidth > width || actualHeight > height)
            throw new BridgeException("unsupported", "Framebuffer texture/visible dimensions are inconsistent or exceed the 16-megapixel capture limit.");
    }

    @com.google.common.annotations.VisibleForTesting
    public static BufferedImage pixelsToImage(IntBuffer pixels, int width, int height, int actualWidth, int actualHeight) {
        validateDimensions(width, height, actualWidth, actualHeight);
        BufferedImage result = new BufferedImage(actualWidth, actualHeight, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[actualWidth];
        for (int y = 0; y < actualHeight; y++) {
            pixels.position((actualHeight - 1 - y) * width);
            pixels.get(row); result.setRGB(0, y, actualWidth, 1, row, 0, actualWidth);
        }
        return result;
    }
}
