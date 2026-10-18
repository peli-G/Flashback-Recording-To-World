package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrWorldConverter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class FbrConvertProgressScreen extends Screen {

    private final Screen parent;
    private Button actionButton;
    private Button backgroundButton;

    public FbrConvertProgressScreen(Screen parent) {
        super(Component.literal("Converting Replay"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        FbrBackgroundOverlay.ensureRegistered();

        this.backgroundButton = this.addRenderableWidget(Button.builder(Component.literal("Keep Converting in Background"), btn -> {
            FbrBackgroundOverlay.markBackgrounded();
            this.minecraft.setScreen(this.parent);
        }).bounds(this.width / 2 - 154, this.height / 4 + 150, 180, 20).build());

        this.actionButton = this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            if (!FbrWorldConverter.progressDone) {
                FbrWorldConverter.cancel();
            }
            this.minecraft.setScreen(this.parent);
        }).bounds(this.width / 2 + 30, this.height / 4 + 150, 124, 20).build());

        this.updateButtons();
    }

    private void updateButtons() {
        boolean done = FbrWorldConverter.progressDone;
        if (this.actionButton != null) {
            this.actionButton.setMessage(Component.literal(done ? "Close" : "Cancel"));
        }
        if (this.backgroundButton != null) {
            this.backgroundButton.active = !done;
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.updateButtons();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        String worldName = FbrWorldConverter.progressWorldName.isEmpty()
                ? "Replay" : FbrWorldConverter.progressWorldName;
        int recordingCount = FbrWorldConverter.progressRecordingCount;

        guiGraphics.drawCenteredString(this.font,
                recordingCount > 1
                        ? "Combining " + recordingCount + " Replays into '" + worldName + "'"
                        : "Converting Replay '" + worldName + "'",
                this.width / 2, 20, 0xFFFFFFFF);

        if (recordingCount > 1) {
            int currentRecording = Math.max(1, Math.min(FbrWorldConverter.progressRecording, recordingCount));
            String name = FbrWorldConverter.progressRecordingName;
            String line = "Recording " + currentRecording + " / " + recordingCount
                    + (name.isEmpty() ? "" : ": " + name);
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, 34, 0xFF909090);
        }

        int current = FbrWorldConverter.progressCurrent;
        int total = Math.max(FbrWorldConverter.progressTotal, 1);
        int pct = FbrWorldConverter.progressDone ? 100
                : Math.min(100, (int) ((current * 100L) / total));

        String phaseLabel = FbrWorldConverter.progressFailed
                ? "Failed: " + FbrWorldConverter.progressResultMessage
                : FbrWorldConverter.progressDone
                    ? "Done! " + FbrWorldConverter.progressResultMessage
                    : FbrWorldConverter.progressPhase;
        int phaseColor = FbrWorldConverter.progressFailed ? 0xFFFF5555
                : FbrWorldConverter.progressDone ? 0xFF55FF55 : 0xFFA0A0A0;

        guiGraphics.drawCenteredString(this.font, phaseLabel, this.width / 2, 50, phaseColor);

        if (FbrWorldConverter.progressThreads > 1 && !FbrWorldConverter.progressDone) {
            guiGraphics.drawCenteredString(this.font, "Fast convert: using " + FbrWorldConverter.progressThreads + " CPU threads",
                    this.width / 2, 64, 0xFF55FFFF);
        }
        if (!FbrWorldConverter.progressDone) {
            guiGraphics.drawCenteredString(this.font,
                    "You can use other menus while this runs - progress shows in the corner",
                    this.width / 2, this.height / 4 + 138, 0xFF808080);
        }

        int barWidth = 200;
        int barX = this.width / 2 - barWidth / 2;
        int barY = this.height / 4 + 100;
        int barHeight = 10;

        guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF000000);
        int filledWidth = (int) (barWidth * (pct / 100.0));
        int barColor = FbrWorldConverter.progressFailed ? 0xFFAA0000 : 0xFF00AA00;
        guiGraphics.fill(barX + 1, barY + 1, barX + 1 + Math.max(0, filledWidth - 2), barY + barHeight - 1, barColor);

        guiGraphics.drawCenteredString(this.font, pct + "%", this.width / 2, barY + barHeight + 4, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font, current + " / " + total,
                this.width / 2, barY + barHeight + 16, 0xFF808080);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
