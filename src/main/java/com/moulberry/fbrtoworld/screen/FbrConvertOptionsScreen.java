package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrToWorldMod;
import com.moulberry.fbrtoworld.FbrWorldConverter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

public class FbrConvertOptionsScreen extends Screen {

    private final Screen previousScreen;
    private final Path selectedReplay;
    private EditBox nameBox;

    public FbrConvertOptionsScreen(Screen previousScreen, Path selectedReplay) {
        super(Component.literal("Select an option"));
        this.previousScreen = previousScreen;
        this.selectedReplay = selectedReplay;
    }

    /** Top of the button stack (keeps everything on screen even on small windows). */
    private int top() {
        return Math.max(26, this.height / 2 - 100);
    }

    @Override
    protected void init() {
        int optionWidth = 280;
        int cancelWidth = 150;
        int x = this.width / 2 - optionWidth / 2;
        int y = this.top();

        // World name for Convert / Combine. Blank = use the Flashback recording's name.
        this.nameBox = new EditBox(this.font, x, y, optionWidth, 20, this.nameBox, Component.literal("World name"));
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("Convert FBR to World"), btn -> {
                    FbrWorldConverter.requestedWorldName = this.nameBox.getValue();
                    FbrConversionLauncher.start(this.minecraft, this.previousScreen, List.of(this.selectedReplay));
                })
                .bounds(x, y + 38, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Combine Flashbacks into one World"), btn -> {
                    FbrWorldConverter.requestedWorldName = this.nameBox.getValue();
                    FbrReplaySelectionList.startCombineSession(this.selectedReplay);
                    this.minecraft.setScreen(new FbrSelectReplayScreen(
                            this.previousScreen, FbrToWorldMod.getDefaultReplaysFolder(), true));
                })
                .bounds(x, y + 62, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Import chunks into existing worlds"), btn -> {
                    FbrWorldConverter.requestedWorldName = null; // importing keeps the existing world's name
                    this.minecraft.setScreen(new FbrImportWorldScreen(this.previousScreen, this.selectedReplay));
                })
                .bounds(x, y + 86, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(lz4Label(), btn -> {
                    com.moulberry.fbrtoworld.config.FbrSettings.setLz4(!com.moulberry.fbrtoworld.config.FbrSettings.isLz4());
                    btn.setMessage(lz4Label());
                })
                .bounds(x, y + 114, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(skipLabel(), btn -> {
                    com.moulberry.fbrtoworld.config.FbrSettings.setSkipAdded(!com.moulberry.fbrtoworld.config.FbrSettings.isSkipAdded());
                    btn.setMessage(skipLabel());
                })
                .bounds(x, y + 138, optionWidth, 20).build());

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn ->
                this.minecraft.setScreen(this.previousScreen))
                .bounds(this.width / 2 - cancelWidth / 2, y + 174, cancelWidth, 20).build());
    }

    private static Component lz4Label() {
        return Component.literal(com.moulberry.fbrtoworld.config.FbrSettings.isLz4()
                ? "Fast compression (LZ4): §aON"
                : "Fast compression (LZ4): §7OFF");
    }

    private static Component skipLabel() {
        return Component.literal(com.moulberry.fbrtoworld.config.FbrSettings.isSkipAdded()
                ? "Import: skip recordings already in the world: §aON"
                : "Import: skip recordings already in the world: §7OFF");
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.previousScreen);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int y = this.top();
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, y - 14, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font, "Leave blank to use the Flashback name",
                this.width / 2, y + 24, 0xFF808080);
        guiGraphics.drawCenteredString(this.font, "LZ4 saves much faster but world files are bigger",
                this.width / 2, y + 162, 0xFF808080);
    }
}
