package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrWorldConverter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class FbrConvertProgressScreen extends Screen {

    private final Screen parent;
    private Button actionButton;

    public FbrConvertProgressScreen(Screen parent) {
        super(Component.literal("Converting Replay"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        this.actionButton = this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> {
            if (!FbrWorldConverter.progressDone) {
                FbrWorldConverter.cancel();
            }
            this.minecraft.setScreen(this.parent);
        }).bounds(this.width / 2 - 100, this.height / 4 + 150, 200, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.actionButton != null) {
            this.actionButton.setMessage(Component.literal(FbrWorldConverter.progressDone ? "Close" : "Cancel"));
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        String worldName = FbrWorldConverter.progressWorldName.isEmpty()
                ? "Replay" : FbrWorldConverter.progressWorldName;
        guiGraphics.drawCenteredString(this.font,
                "Converting Replay '" + worldName + "'", this.width / 2, 20, 0xFFFFFFFF);

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
