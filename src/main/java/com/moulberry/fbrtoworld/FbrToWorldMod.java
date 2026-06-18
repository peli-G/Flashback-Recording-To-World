package com.moulberry.fbrtoworld;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class FbrToWorldMod implements ClientModInitializer {

    public static final String MOD_ID = "fbr-to-world";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** .minecraft/flashback/replays/ — mirrors Flashback's getReplayFolder() */
    public static Path getFlashbackReplaysFolder() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("flashback")
                .resolve("replays");
    }

    /** Default folder shown when FbrSelectReplayScreen is opened from the title screen. */
    public static Path getDefaultReplaysFolder() {
        return getFlashbackReplaysFolder();
    }

    /**
     * Recursively list all recordings (zip/fbr files) under root,
     * returning paths relative to root so they look like "subfolder/name.zip".
     */
    public static List<String> listRecordings(Path root, Path dir) throws IOException {
        List<String> results = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p)) {
                    results.addAll(listRecordings(root, p));
                } else {
                    String name = p.getFileName().toString().toLowerCase();
                    if (name.endsWith(".zip") || name.endsWith(".fbr")) {
                        results.add(root.relativize(p).toString());
                    }
                }
            }
        }
        results.sort(String::compareToIgnoreCase);
        return results;
    }

    /**
     * Search replaysRoot and all subdirectories for a file matching the given name.
     * Supports exact match or partial/extensionless match.
     */
    public static Path findRecording(Path replaysRoot, String name) {
        try {
            // First try direct match in root
            Path direct = replaysRoot.resolve(name);
            if (Files.exists(direct) && !Files.isDirectory(direct)) return direct;

            // Walk all files recursively
            final String nameLower = name.toLowerCase();
            final String nameNoExt = nameLower.contains(".")
                    ? nameLower.substring(0, nameLower.lastIndexOf('.')) : nameLower;

            try (java.util.stream.Stream<Path> walk = Files.walk(replaysRoot)) {
                return walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String fn = p.getFileName().toString().toLowerCase();
                        String fnNoExt = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;
                        // Match: exact filename, relative path, or basename without extension
                        return fn.equals(nameLower)
                                || fnNoExt.equals(nameNoExt)
                                || replaysRoot.relativize(p).toString().replace("\\", "/").equalsIgnoreCase(name);
                    })
                    .findFirst().orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Caches the RegistryAccess from the most recently active world/connection,
     * so the replay browser can still convert replays from the title screen
     * after quitting a world (Minecraft tears down mc.level/mc.getConnection()
     * on disconnect, but the registry contents themselves don't actually change
     * between vanilla worlds/servers in the common case).
     */
    public static volatile net.minecraft.core.RegistryAccess cachedRegistryAccess = null;

    @Override
    public void onInitializeClient() {

        // Ensure .minecraft/flashback/replays/ exists on startup
        try {
            Files.createDirectories(getFlashbackReplaysFolder());
        } catch (IOException e) {
            LOGGER.warn("[FbrToWorld] Could not create flashback/replays folder: {}", e.getMessage());
        }

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            cachedRegistryAccess = handler.registryAccess();
            LOGGER.info("[FbrToWorld] Cached RegistryAccess on world join (registries: {})",
                    cachedRegistryAccess.registries().count());
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("[FbrToWorld] World/connection disconnected — cached RegistryAccess from this session is still available for conversion");
        });

        LOGGER.info("FBR-to-World loaded.");
    }
}
