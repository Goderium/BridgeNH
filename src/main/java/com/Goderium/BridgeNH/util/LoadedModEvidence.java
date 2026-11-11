package com.Goderium.BridgeNH.util;

import com.Goderium.BridgeNH.BridgeNH;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.ModContainer;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loader selection is authoritative. On-disk candidates and hashes are explicitly separate evidence. */
public final class LoadedModEvidence {
    private static JsonObject bridgeAtStartup;
    private static String fingerprintAtStartup;

    public static synchronized void captureBridge() {
        if (bridgeAtStartup == null) {
            // Resolve and execute the identity class during startup, before a running jar
            // could be replaced. A later first reference must not identify disk replacement code.
            fingerprintAtStartup = BridgeBuildIdentity.fingerprint();
            bridgeAtStartup = classEvidence(BridgeNH.class);
            bridgeAtStartup.add("identityClassAtPreInit", classEvidence(BridgeBuildIdentity.class));
        }
    }

    public static synchronized JsonObject bridge() {
        JsonObject result = bridgeAtStartup == null ? classEvidence(BridgeNH.class)
                : new JsonParser().parse(bridgeAtStartup.toString()).getAsJsonObject();
        if (fingerprintAtStartup != null) result.addProperty("buildFingerprint", fingerprintAtStartup);
        result.addProperty("fingerprintCapturedAtPreInit", fingerprintAtStartup != null);
        result.addProperty("fingerprintScope", "SHA256 of sorted main source/resource paths and bytes plus build.gradle, compiled into the executing identity class; not a jar hash or another mod's build ID");
        result.addProperty("capturedAtPreInit", bridgeAtStartup != null);
        return result;
    }

    public static void add(JsonObject entry, ModContainer mod, boolean hashes, boolean candidates) {
        File selected = mod.getSource();
        entry.addProperty("sourceAuthority", "FML active ModContainer.getSource; disk presence and version alone do not prove selection");
        if (selected != null) entry.addProperty("loadedSource", selected.getAbsolutePath());
        if (hashes && selected != null) entry.add("sourceFileAtObservation", fileEvidence(selected));
        Object instance = mod.getMod();
        if (instance != null) {
            Class<?> type = instance.getClass();
            entry.addProperty("mainClass", type.getName());
            if (hashes) entry.add("mainClassOrigin", classEvidence(type));
            if (candidates) entry.add("diskCandidates", candidates(mod.getModId(), type.getName().replace('.', '/') + ".class", selected));
        } else if (candidates) entry.add("diskCandidates", candidates(mod.getModId(), null, selected));
        if ("codexbridge".equals(mod.getModId())) entry.add("executingBridge", bridge());
    }

    private static JsonObject classEvidence(Class<?> type) {
        JsonObject row = new JsonObject(); row.addProperty("className", type.getName());
        row.addProperty("observedAtMillis", System.currentTimeMillis());
        try {
            java.net.URL location = type.getProtectionDomain().getCodeSource().getLocation();
            row.addProperty("codeSource", location.toExternalForm());
            if ("file".equals(location.getProtocol())) row.add("sourceFile", fileEvidence(new File(location.toURI())));
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            java.net.URL url = type.getResource(resource);
            if (url != null) row.addProperty("classResource", url.toExternalForm());
            try (InputStream stream = type.getResourceAsStream(resource)) {
                if (stream != null) row.addProperty("classResourceSha256", hash(stream));
            }
            row.addProperty("available", true);
        } catch (Exception | LinkageError error) { row.addProperty("available", false); row.addProperty("reason", error.getClass().getSimpleName()); }
        row.addProperty("scope", "Runtime class origin; resource/file bytes at observation, not a dump of transformed or already-loaded class bytes");
        return row;
    }

    private static JsonObject fileEvidence(File file) {
        JsonObject row = new JsonObject(); row.addProperty("path", file.getAbsolutePath());
        row.addProperty("observedAtMillis", System.currentTimeMillis());
        row.addProperty("meaning", "File bytes at this observation; replacing a running jar does not prove new code loaded");
        try {
            row.addProperty("size", file.length()); row.addProperty("modifiedAtMillis", file.lastModified());
            if (!file.isFile() || file.length() > 512L * 1024 * 1024) throw new java.io.IOException("not_bounded_file");
            try (InputStream stream = Files.newInputStream(file.toPath())) { row.addProperty("sha256", hash(stream)); }
            row.addProperty("available", true);
        } catch (Exception error) { row.addProperty("available", false); row.addProperty("reason", error.getClass().getSimpleName()); }
        return row;
    }

    private static String hash(InputStream stream) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536]; int read;
        while ((read = stream.read(bytes)) != -1) digest.update(bytes, 0, read);
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static JsonObject candidates(String modId, String classPath, File selected) {
        JsonObject result = new JsonObject(); JsonArray found = new JsonArray(); result.add("entries", found);
        result.addProperty("scope", "Sibling jars identified by mcmod.info modid or actual main-class entry, never filename patterns; candidates are not loader selections");
        File parent = selected == null ? null : selected.getParentFile();
        File[] files = parent == null ? null : parent.listFiles(file -> file.isFile() && file.getName().endsWith(".jar"));
        int unreadable = 0, examined = 0, matches = 0; boolean limited = files == null || files.length > 4096;
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File file : files) {
                if (++examined > 4096) break;
                try (ZipFile zip = new ZipFile(file)) {
                    boolean marker = classPath != null && zip.getEntry(classPath) != null, metadata = false;
                    ZipEntry info = zip.getEntry("mcmod.info");
                    if (info != null) try (InputStream stream = zip.getInputStream(info)) {
                        byte[] bytes = new byte[262145]; int size = 0, read;
                        while (size < bytes.length && (read = stream.read(bytes, size, bytes.length - size)) != -1) size += read;
                        if (size > 262144) { unreadable++; }
                        else {
                            JsonElement parsed = new JsonParser().parse(new String(bytes, 0, size, StandardCharsets.UTF_8));
                            JsonElement list = parsed.isJsonObject() ? parsed.getAsJsonObject().get("modList") : parsed;
                            if (list == null || !list.isJsonArray()) unreadable++;
                            else for (JsonElement item : list.getAsJsonArray())
                                if (item.isJsonObject() && item.getAsJsonObject().has("modid") && modId.equals(item.getAsJsonObject().get("modid").getAsString())) metadata = true;
                        }
                    } catch (RuntimeException failure) { unreadable++; }
                    if (marker || metadata) {
                        matches++;
                        if (found.size() >= 128) { limited = true; continue; }
                        JsonObject row = new JsonObject(); row.addProperty("path", file.getAbsolutePath());
                        row.addProperty("mainClassEntry", marker); row.addProperty("metadataModId", metadata);
                        row.addProperty("selectedByFml", selected != null && file.getCanonicalFile().equals(selected.getCanonicalFile())); found.add(row);
                    }
                } catch (Exception failure) { unreadable++; }
            }
        }
        result.addProperty("totalCandidatesFound", matches); result.addProperty("duplicateCandidatesDetected", matches > 1);
        result.addProperty("filesExamined", Math.min(examined, 4096)); result.addProperty("unreadableFiles", unreadable);
        result.addProperty("complete", !limited && unreadable == 0); result.addProperty("truncated", limited);
        result.addProperty("loaderRejectionReasonAvailable", false); result.addProperty("rejectionEvidence", "Consult this launch's FML duplicate/sorter records; no rejection cause is inferred from candidate discovery");
        return result;
    }
}
