package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrRegistryLoader;
import com.moulberry.fbrtoworld.FbrToWorldMod;
import com.moulberry.fbrtoworld.FbrWorldConverter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FbrConversionLauncher {

    private FbrConversionLauncher() {}

    public static void start(Minecraft minecraft, Screen previousScreen, List<Path> recordings) {
        start(minecraft, previousScreen, recordings, null);
    }

    public static void startImport(Minecraft minecraft, Screen previousScreen, List<Path> recordings, Path targetWorld) {
        start(minecraft, previousScreen, recordings, targetWorld);
    }

    private static void start(Minecraft minecraft, Screen previousScreen, List<Path> recordings, Path targetWorld) {
        FbrBackgroundOverlay.ensureRegistered();
        if (FbrWorldConverter.running) {
            // One conversion at a time - show the one that's already going.
            minecraft.setScreen(new FbrConvertProgressScreen(previousScreen));
            return;
        }
        FbrBackgroundOverlay.resetForNewConversion();

        List<Path> paths = new ArrayList<>(recordings);
        if (paths.isEmpty()) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Convert requested with no recordings selected");
            return;
        }

        var connection = minecraft.getConnection();
        var level = minecraft.level;
        var cached = FbrToWorldMod.cachedRegistryAccess;

        FbrToWorldMod.LOGGER.info(
                "[FbrToWorld] Convert clicked — recordings={}, targetWorld={}, connection={}, level={}, cachedRegistryAccess={}",
                paths.size(), targetWorld, connection != null, level != null, cached != null);

        RegistryAccess registryAccess;
        String source;
        if (connection != null) {
            registryAccess = connection.registryAccess();
            source = "live connection";
        } else if (level != null) {
            registryAccess = level.registryAccess();
            source = "live level";
        } else if (cached != null) {
            registryAccess = cached;
            source = "cached from last session";
        } else {
            registryAccess = null;
            source = "none available";
        }

        FbrToWorldMod.LOGGER.info("[FbrToWorld] Using registry access source: {}", source);

        if (registryAccess == null) {
            FbrToWorldMod.LOGGER.info(
                    "[FbrToWorld] No live/cached registries — loading vanilla registries in the background");
            startAfterLoadingRegistries(minecraft, previousScreen, paths, targetWorld);
            return;
        }

        boolean started = targetWorld != null
                ? FbrWorldConverter.startImport(paths, registryAccess, minecraft, targetWorld)
                : FbrWorldConverter.start(paths, registryAccess, minecraft);
        FbrToWorldMod.LOGGER.info("[FbrToWorld] FbrWorldConverter start returned: {}", started);
        if (started) {
            minecraft.setScreen(new FbrConvertProgressScreen(previousScreen));
        }
    }

    private static void startAfterLoadingRegistries(Minecraft minecraft, Screen previousScreen, List<Path> paths, Path targetWorld) {
        minecraft.setScreen(new GenericMessageScreen(Component.literal("Loading registries...")));

        FbrRegistryLoader.loadVanillaRegistries().thenAccept(registryAccess -> {
            minecraft.execute(() -> {
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Vanilla registries loaded, starting conversion");
                boolean started = targetWorld != null
                        ? FbrWorldConverter.startImport(paths, registryAccess, minecraft, targetWorld)
                        : FbrWorldConverter.start(paths, registryAccess, minecraft);
                if (started) {
                    minecraft.setScreen(new FbrConvertProgressScreen(previousScreen));
                } else {
                    minecraft.setScreen(previousScreen);
                }
            });
        }).exceptionally(throwable -> {
            minecraft.execute(() -> {
                FbrToWorldMod.LOGGER.error("[FbrToWorld] Failed to load vanilla registries", throwable);
                minecraft.setScreen(new AlertScreen(
                        () -> minecraft.setScreen(previousScreen),
                        Component.literal("Couldn't Load Registries"),
                        Component.literal("Failed to load vanilla registries: " + throwable.getMessage()
                                + " — try joining any world once this session instead."),
                        Component.literal("Ok"),
                        true
                ));
            });
            return null;
        });
    }
}
