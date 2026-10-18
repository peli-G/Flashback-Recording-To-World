package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrWorldConverter;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * Small progress box in the top-right corner of every menu while a conversion runs in the
 * background (and for a while after it finishes). In-game with no menu open, the existing
 * action-bar progress and chat messages already cover it.
 */
public final class FbrBackgroundOverlay {

    private static final int BOX_WIDTH = 200;
    private static final long SHOW_DONE_FOR_MS = 20_000L;

    private static boolean registered = false;
    private static volatile boolean backgrounded = false;
    private static volatile long finishedAt = -1L;

    private FbrBackgroundOverlay() {}

    public static synchronized void ensureRegistered() {
        if (registered) return;
        registered = true;
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register(FbrBackgroundOverlay::render));
    }

    /** The player pressed "Keep Converting in Background". */
    public static void markBackgrounded() {
        backgrounded = true;
    }

    public static void resetForNewConversion() {
        backgrounded = false;
        finishedAt = -1L;
    }

    private static void render(Screen screen, GuiGraphics g, int mouseX, int mouseY, float tickDelta) {
        if (screen instanceof FbrConvertProgressScreen) return;

        boolean running = FbrWorldConverter.running;
        boolean done = FbrWorldConverter.progressDone;

        if (!running) {
            if (!backgrounded || !done) return;
            long now = System.currentTimeMillis();
            if (finishedAt < 0) finishedAt = now;
            if (now - finishedAt > SHOW_DONE_FOR_MS) return;
        }

        Font font = Minecraft.getInstance().font;
        int x1 = screen.width - BOX_WIDTH - 4;
        int y1 = 4;
        int x2 = screen.width - 4;
        int cx = x1 + BOX_WIDTH / 2;
        int boxHeight = 44;

        int border = FbrWorldConverter.progressFailed ? 0xFFAA0000 : (done ? 0xFF00AA00 : 0xFF555555);
        g.fill(x1 - 1, y1 - 1, x2 + 1, y1 + boxHeight + 1, border);
        g.fill(x1, y1, x2, y1 + boxHeight, 0xE0101010);

        String world = FbrWorldConverter.progressWorldName.isEmpty() ? "new world" : FbrWorldConverter.progressWorldName;

        if (running && !done) {
            int count = FbrWorldConverter.progressRecordingCount;
            String title = count > 1
                    ? "FBR to World: combining " + Math.max(1, FbrWorldConverter.progressRecording) + "/" + count
                    : "FBR to World: converting";
            g.drawCenteredString(font, title, cx, y1 + 4, 0xFFFFFF55);
            g.drawCenteredString(font, fit(font, FbrWorldConverter.progressPhase, BOX_WIDTH - 8), cx, y1 + 15, 0xFFA0A0A0);

            int current = FbrWorldConverter.progressCurrent;
            int total = Math.max(1, FbrWorldConverter.progressTotal);
            int pct = Math.min(100, (int) ((current * 100L) / total));
            int barX1 = x1 + 8, barX2 = x2 - 40, barY = y1 + 30;
            g.fill(barX1, barY, barX2, barY + 6, 0xFF000000);
            int filled = (int) ((barX2 - barX1 - 2) * (pct / 100.0));
            g.fill(barX1 + 1, barY + 1, barX1 + 1 + Math.max(0, filled), barY + 5, 0xFF00AA00);
            g.drawCenteredString(font, pct + "%", x2 - 20, barY - 1, 0xFFFFFFFF);
        } else if (FbrWorldConverter.progressFailed) {
            g.drawCenteredString(font, "FBR to World: failed", cx, y1 + 6, 0xFFFF5555);
            g.drawCenteredString(font, fit(font, FbrWorldConverter.progressResultMessage, BOX_WIDTH - 8), cx, y1 + 18, 0xFFA0A0A0);
            g.drawCenteredString(font, "See chat or the log for details", cx, y1 + 30, 0xFF808080);
        } else {
            g.drawCenteredString(font, "FBR to World: done!", cx, y1 + 6, 0xFF55FF55);
            g.drawCenteredString(font, fit(font, "saves/" + world, BOX_WIDTH - 8), cx, y1 + 18, 0xFFFFFFFF);
            g.drawCenteredString(font, "Ready in Singleplayer", cx, y1 + 30, 0xFF808080);
        }
    }

    /** Shortens text with "..." so it fits in maxWidth pixels. */
    private static String fit(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) return text;
        String s = text;
        while (s.length() > 1 && font.width(s + "...") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "...";
    }
}
