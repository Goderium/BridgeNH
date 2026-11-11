package com.Goderium.BridgeNH.config;

import com.Goderium.BridgeNH.util.BridgeException;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import com.google.gson.JsonObject;

/** Shared bounded, optimistic config replacement. Never overwrites an unseen disk edit. */
final class LocalJsonConfig {
    static byte[] read(Path path, int maximum) throws IOException {
        if (Files.size(path) > maximum) throw new IOException("Config exceeds size limit");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > maximum) throw new IOException("Config exceeds size limit");
        return bytes;
    }

    static byte[] write(Path path, byte[] expected, JsonObject value, int maximum) {
        Path temporary = null;
        try {
            if (path == null) throw new IOException("Config path unavailable");
            if (expected == null ? Files.exists(path) : !Files.exists(path) || !Arrays.equals(expected, read(path, maximum)))
                throw new BridgeException("config_changed", "Config changed on disk; reload before editing.");
            byte[] bytes = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maximum) throw new IOException("Config exceeds size limit");
            Files.createDirectories(path.toAbsolutePath().getParent());
            temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "mcb-config-", ".tmp");
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            return bytes;
        } catch (IOException failure) { throw new BridgeException("config_write_failed", "Config was not changed; the new value could not be saved."); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }
}
