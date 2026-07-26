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

    public static Path getFlashbackReplaysFolder() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("flashback")
                .resolve("replays");
    }

    public static Path getDefaultReplaysFolder() {
        return getFlashbackReplaysFolder();
    }

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

    public static Path findRecording(Path replaysRoot, String name) {
        try {

            Path direct = replaysRoot.resolve(name);
            if (Files.exists(direct) && !Files.isDirectory(direct)) return direct;

            final String nameLower = name.toLowerCase();
            final String nameNoExt = nameLower.contains(".")
                    ? nameLower.substring(0, nameLower.lastIndexOf('.')) : nameLower;

            try (java.util.stream.Stream<Path> walk = Files.walk(replaysRoot)) {
                return walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> {
                        String fn = p.getFileName().toString().toLowerCase();
                        String fnNoExt = fn.contains(".") ? fn.substring(0, fn.lastIndexOf('.')) : fn;

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

    public static volatile net.minecraft.core.RegistryAccess cachedRegistryAccess = null;

    @Override
    public void onInitializeClient() {

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
