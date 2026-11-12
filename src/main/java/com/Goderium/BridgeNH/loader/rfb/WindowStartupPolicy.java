package com.Goderium.BridgeNH.loader.rfb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Early window policy; independent of Minecraft and native library initialization. */
public final class WindowStartupPolicy {
    private WindowStartupPolicy() { }
    public static String read(Path gameDirectory) {
        Path path = gameDirectory.resolve("config/codexbridge/window-startup.txt");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[65];
            int count = 0, read;
            while (count < bytes.length && (read = input.read(bytes, count, bytes.length - count)) != -1) count += read;
            if (count > 64) return "minimized";
            String mode = new String(bytes, 0, count, StandardCharsets.US_ASCII).trim();
            return valid(mode) ? mode : "minimized";
        } catch (IOException | SecurityException error) { return "minimized"; }
    }
    public static boolean valid(String mode) {
        return "background".equals(mode) || "minimized".equals(mode) || "normal".equals(mode);
    }
    public static String current() { return read(Paths.get(System.getProperty("user.dir"))); }
}
