package com.moulberry.fbrtoworld;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public final class FbrDiagnostics {

    private static volatile boolean enabled = false;

    private FbrDiagnostics() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        FbrToWorldMod.LOGGER.info("[FbrToWorld] Diagnostic mode {}", enabled ? "enabled" : "disabled");
        return enabled;
    }

    public static void info(String message, Object... args) {
        if (enabled) FbrToWorldMod.LOGGER.info(message, args);
    }

    public static void drawLabel(GuiGraphics guiGraphics, Font font, int screenHeight) {
        if (!enabled) return;
        guiGraphics.drawString(font, "Diagnostic Mode", 4, screenHeight - font.lineHeight - 4, 0xFFFFFF55, true);
    }
}
