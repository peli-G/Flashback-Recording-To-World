package com.moulberry.fbrtoworld;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;

public class FbrToWorldMod implements ClientModInitializer {

    public static final String MOD_ID = "fbr-to-world";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(literal("fbrtoworld")
                .then(argument("path", greedyString())
                    .executes(ctx -> {
                        String path = getString(ctx, "path").trim()
                                .replace("\"", "").replace("'", "");
                        Minecraft mc = Minecraft.getInstance();
                        // Get RegistryAccess from the running game, not CommandBuildContext
                        var gameRegistryAccess = mc.getConnection() != null
                                ? mc.getConnection().registryAccess()
                                : mc.level != null ? mc.level.registryAccess() : null;
                        if (gameRegistryAccess == null) {
                            ctx.getSource().sendError(Component.literal(
                                    "[FbrToWorld] Must be in a world/replay to use this command."));
                            return 1;
                        }
                        boolean started = FbrWorldConverter.start(
                                java.nio.file.Path.of(path),
                                gameRegistryAccess,
                                mc);
                        if (!started) {
                            ctx.getSource().sendError(Component.literal(
                                    "[FbrToWorld] Already converting, please wait."));
                        }
                        return 1;
                    }))
            )
        );

        LOGGER.info("FBR-to-World loaded. Usage: /fbrtoworld <path/to/recording.fbr>");
    }
}
