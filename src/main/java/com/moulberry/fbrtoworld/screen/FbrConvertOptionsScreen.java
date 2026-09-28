package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

public class FbrConvertOptionsScreen extends Screen {

    private final Screen previousScreen;
    private final Path selectedReplay;

    public FbrConvertOptionsScreen(Screen previousScreen, Path selectedReplay) {
        super(Component.literal("Select an option"));
        this.previousScreen = previousScreen;
        this.selectedReplay = selectedReplay;
    }

    @Override
    protected void init() {
        int optionWidth = 280;
        int cancelWidth = 150;
        int x = this.width / 2 - optionWidth / 2;
        int y = this.height / 2 - 70;

        this.addRenderableWidget(Button.builder(Component.literal("Convert FBR to World"), btn ->
                FbrConversionLauncher.start(this.minecraft, this.previousScreen, List.of(this.selectedReplay)))
                .bounds(x, y, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Combine Flashbacks into one World"), btn -> {
                    FbrReplaySelectionList.startCombineSession(this.selectedReplay);
                    this.minecraft.setScreen(new FbrSelectReplayScreen(
                            this.previousScreen, FbrToWorldMod.getDefaultReplaysFolder(), true));
                })
                .bounds(x, y + 24, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Import chunks into existing worlds"), btn ->
                this.minecraft.setScreen(new FbrImportWorldScreen(this.previousScreen, this.selectedReplay)))
                .bounds(x, y + 48, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn ->
                this.minecraft.setScreen(this.previousScreen))
                .bounds(this.width / 2 - cancelWidth / 2, y + 72, cancelWidth, 20).build());
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.previousScreen);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 96, 0xFFFFFFFF);
    }
}
