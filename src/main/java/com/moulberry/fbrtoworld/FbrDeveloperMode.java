package com.moulberry.fbrtoworld;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class FbrDeveloperMode {

    private static volatile boolean enabled = false;

    private FbrDeveloperMode() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        FbrToWorldMod.LOGGER.info("[FbrToWorld][DEV] Developer mode {}", enabled ? "enabled" : "disabled");
        return enabled;
    }

    public static void log(String message, Object... args) {
        if (enabled) FbrToWorldMod.LOGGER.info("[FbrToWorld][DEV] " + message, args);
    }

    public static void error(String message, Throwable error) {
        if (enabled) FbrToWorldMod.LOGGER.error("[FbrToWorld][DEV] " + message, error);
    }

    public static void drawLabel(GuiGraphics guiGraphics, Font font, int screenHeight) {
        if (!enabled) return;
        int y = screenHeight - font.lineHeight - 4;
        if (FbrDiagnostics.isEnabled()) y -= font.lineHeight + 2;
        guiGraphics.drawString(font, "Developer Mode", 4, y, 0xFFAA0000, true);
    }
}
