package com.moulberry.fbrtoworld.config;

import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Small settings file: config/fbr-to-world-settings.properties */
public final class FbrSettings {

    private static boolean loaded = false;
    private static volatile boolean lz4 = false;
    private static volatile boolean skipAdded = true;

    private FbrSettings() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("fbr-to-world-settings.properties");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path p = path();
        if (!Files.isRegularFile(p)) return;
        Properties props = new Properties();
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            props.load(r);
            lz4 = Boolean.parseBoolean(props.getProperty("lz4Compression", "false").trim());
            skipAdded = Boolean.parseBoolean(props.getProperty("skipRecordingsAlreadyInWorld", "true").trim());
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't read settings: {}", e.getMessage());
        }
    }

    private static synchronized void save() {
        Properties props = new Properties();
        props.setProperty("lz4Compression", Boolean.toString(lz4));
        props.setProperty("skipRecordingsAlreadyInWorld", Boolean.toString(skipAdded));
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                props.store(w, "FBR to World settings");
            }
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't save settings: {}", e.getMessage());
        }
    }

    /** Save chunks with LZ4 instead of zlib: much faster, bigger files. */
    public static boolean isLz4() { ensureLoaded(); return lz4; }

    public static void setLz4(boolean value) { ensureLoaded(); lz4 = value; save(); }

    /** On Import, skip recordings that were already added to that world before. */
    public static boolean isSkipAdded() { ensureLoaded(); return skipAdded; }

    public static void setSkipAdded(boolean value) { ensureLoaded(); skipAdded = value; save(); }
}
