package com.moulberry.fbrtoworld;

import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Unit;
import net.minecraft.util.Util;
import net.minecraft.world.level.WorldDataConfiguration;

import java.util.concurrent.CompletableFuture;

public class FbrRegistryLoader {

    private static volatile CompletableFuture<RegistryAccess> pending = null;

    public static CompletableFuture<RegistryAccess> loadVanillaRegistries() {
        if (pending != null) return pending;

        PackRepository packRepository = ServerPacksSource.createVanillaTrustedRepository();
        packRepository.reload();

        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(
                packRepository, WorldDataConfiguration.DEFAULT, false, true);
        WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(
                packConfig, Commands.CommandSelection.INTEGRATED, PermissionSet.NO_PERMISSIONS);

        CompletableFuture<RegistryAccess> future = WorldLoader.load(
                initConfig,
                context -> new WorldLoader.DataLoadOutput<>(Unit.INSTANCE, context.datapackDimensions()),
                (resourceManager, dataPackResources, registries, cookie) -> {
                    resourceManager.close();
                    return registries.compositeAccess();
                },
                Util.backgroundExecutor(),
                Util.backgroundExecutor()
        ).thenApply(result -> (RegistryAccess) result);

        future.whenComplete((result, throwable) -> {
            if (throwable != null) {
                FbrToWorldMod.LOGGER.error("[FbrToWorld] Failed to load vanilla registries", throwable);
            } else {
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Loaded vanilla registries without a world (registries: {})",
                        result.registries().count());
            }
            pending = null;
        });

        pending = future;
        return future;
    }
}
