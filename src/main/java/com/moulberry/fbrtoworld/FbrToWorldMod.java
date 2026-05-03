package com.moulberry.fbrtoworld;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

public class FbrToWorldMod implements ClientModInitializer {

    public static final String MOD_ID = "fbr-to-world";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** .minecraft/flashback/replays/ — mirrors Flashback's getReplayFolder() */
    public static Path getFlashbackReplaysFolder() {
        return FabricLoader.getInstance().getGameDir()
                .resolve("flashback")
                .resolve("replays");
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

    @Override
    public void onInitializeClient() {

        // Ensure .minecraft/flashback/replays/ exists on startup
        try {
            Files.createDirectories(getFlashbackReplaysFolder());
        } catch (IOException e) {
            LOGGER.warn("[FbrToWorld] Could not create flashback/replays folder: {}", e.getMessage());
        }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // /fbrtoworld list  — show recordings in flashback/replays/
            // /fbrtoworld <name|path>  — convert by filename (no path needed) or full path

            dispatcher.register(literal("fbrtoworld")

                // /fbrtoworld list
                .then(literal("list").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    Path replaysDir = getFlashbackReplaysFolder();
                    try {
                        List<String> names = listRecordings(replaysDir, replaysDir);
                        if (names.isEmpty()) {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "§e[FbrToWorld] No recordings found in flashback/replays/"));
                        } else {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "§a[FbrToWorld] Recordings in flashback/replays/:"));
                            for (String name : names) {
                                ctx.getSource().sendFeedback(Component.literal("§7  - " + name));
                            }
                        }
                    } catch (IOException e) {
                        ctx.getSource().sendError(Component.literal(
                                "§c[FbrToWorld] Could not read replays folder: " + e.getMessage()));
                    }
                    return 1;
                }))

                // /fbrtoworld <name or full path>
                .then(argument("path", greedyString())
                .suggests((SuggestionProvider<FabricClientCommandSource>) (ctx, builder) -> {
                    try {
                        String remaining = builder.getRemaining().toLowerCase();
                        for (String name : listRecordings(getFlashbackReplaysFolder(), getFlashbackReplaysFolder())) {
                            // Use forward slashes for consistency cross-platform
                            String normalized = name.replace("\\", "/");
                            if (normalized.toLowerCase().contains(remaining)) {
                                builder.suggest(normalized);
                            }
                        }
                    } catch (IOException ignored) {}
                    return builder.buildFuture();
                })
                .executes(ctx -> {
                    String input = getString(ctx, "path").trim()
                            .replace("\"", "").replace("'", "");
                    Minecraft mc = Minecraft.getInstance();

                    var gameRegistryAccess = mc.getConnection() != null
                            ? mc.getConnection().registryAccess()
                            : mc.level != null ? mc.level.registryAccess() : null;
                    if (gameRegistryAccess == null) {
                        ctx.getSource().sendError(Component.literal(
                                "[FbrToWorld] Must be in a world or replay to use this command."));
                        return 1;
                    }

                    // Resolve path: check if it's a full path first, then search replays folder recursively
                    Path fbrPath = Paths.get(input);
                    if (!Files.exists(fbrPath)) {
                        // Search replays folder and all subdirectories for a matching filename
                        Path replaysDir2 = getFlashbackReplaysFolder();
                        Path found = findRecording(replaysDir2, input);
                        if (found != null) {
                            fbrPath = found;
                        }
                    }

                    boolean started = FbrWorldConverter.start(fbrPath, gameRegistryAccess, mc);
                    if (!started) {
                        ctx.getSource().sendError(Component.literal(
                                "[FbrToWorld] Already converting, please wait."));
                    }
                    return 1;
                }))

                // /fbrtoworld (no args) — list available recordings
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    Path replaysDir = getFlashbackReplaysFolder();
                    try {
                        List<String> names = listRecordings(replaysDir, replaysDir);
                        if (names.isEmpty()) {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "§e[FbrToWorld] No recordings found in flashback/replays/. " +
                                    "Usage: /fbrtoworld <filename or full path>"));
                        } else {
                            ctx.getSource().sendFeedback(Component.literal(
                                    "§a[FbrToWorld] Recordings in flashback/replays/ (use /fbrtoworld <name>):"));
                            for (String name : names) {
                                ctx.getSource().sendFeedback(Component.literal("§7  - " + name));
                            }
                        }
                    } catch (IOException e) {
                        ctx.getSource().sendFeedback(Component.literal(
                                "§e[FbrToWorld] Usage: /fbrtoworld <filename or full path>"));
                    }
                    return 1;
                })
            );
        });

        LOGGER.info("FBR-to-World loaded. Usage: /fbrtoworld <recording name or path>");
    }
}
