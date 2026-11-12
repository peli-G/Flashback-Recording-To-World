package com.moulberry.fbrtoworld;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moulberry.fbrtoworld.screen.FbrMeta;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FbrVersionCheck {

    private FbrVersionCheck() {}

    public static String findMismatch(List<Path> recordings) {
        for (Path recording : recordings) {
            String message = findMismatch(recording);
            if (message != null) return message;
        }
        return null;
    }

    public static String findMismatch(Path recording) {
        FbrMeta meta = readMeta(recording);
        if (meta == null) return null;
        if (VersionSupport.accepts(meta.dataVersion, meta.versionString)) return null;

        String recorded = meta.versionString != null ? meta.versionString : "data version " + meta.dataVersion;
        String current = SharedConstants.getCurrentVersion().name();
        FbrDiagnostics.info("[FbrToWorld] Version mismatch for {}: recorded on {} (data {}), instance is {}",
                recording.getFileName(), recorded, meta.dataVersion, current);
        return "This Flashback was recorded on version " + recorded + ", and you are trying to convert it on "
                + current + ". Please use an older or newer version of this mod that lets you convert this Flashback.";
    }

    public static void showError(Minecraft minecraft, Screen returnTo, String message) {
        minecraft.setScreen(new AlertScreen(
                () -> minecraft.setScreen(returnTo),
                Component.literal("Version Mismatch").withStyle(ChatFormatting.RED),
                Component.literal(message).withStyle(ChatFormatting.RED),
                Component.literal("Close"),
                true
        ));
    }

    private static FbrMeta readMeta(Path recording) {
        try {
            if (!recording.toString().toLowerCase().endsWith(".zip")) return null;
            try (FileSystem fs = FileSystems.newFileSystem(recording)) {
                Path metaPath = fs.getPath("/metadata.json");
                if (!Files.exists(metaPath)) return null;
                JsonObject json = new Gson().fromJson(Files.readString(metaPath), JsonObject.class);
                return FbrMeta.fromJson(json);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
