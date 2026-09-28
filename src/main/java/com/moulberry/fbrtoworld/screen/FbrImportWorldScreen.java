package com.moulberry.fbrtoworld.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public class FbrImportWorldScreen extends Screen {

    private final Screen lastScreen;
    private final Path selectedReplay;

    private EditBox searchBox;
    private FbrWorldSelectionList list;
    private Button convertButton;
    private Button deleteButton;

    public FbrImportWorldScreen(Screen lastScreen, Path selectedReplay) {
        super(Component.literal("Select World to Import Into"));
        this.lastScreen = lastScreen;
        this.selectedReplay = selectedReplay;
    }

    @Override
    protected void init() {
        this.searchBox = new EditBox(this.font, this.width / 2 - 128, 22, 256, 20, this.searchBox, Component.literal("Search"));
        this.searchBox.setResponder(value -> this.list.updateFilter(value));
        this.addWidget(this.searchBox);

        this.list = this.addRenderableWidget(new FbrWorldSelectionList(this, this.minecraft,
                this.width, this.height - 112, 48, 36, this.searchBox.getValue()));

        this.convertButton = this.addRenderableWidget(Button.builder(Component.literal("Convert to World"), this::tryConvert)
                .bounds(this.width / 2 - 151, this.height - 52, 150, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Gamerule Options"), btn ->
                this.minecraft.setScreen(FbrGameRuleScreen.create(this)))
                .bounds(this.width / 2 + 1, this.height - 52, 150, 20).build());

        this.deleteButton = this.addRenderableWidget(Button.builder(Component.literal("Delete"), this::tryDelete)
                .bounds(this.width / 2 - 151, this.height - 28, 150, 20).build());
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, btn -> this.minecraft.setScreen(this.lastScreen))
                .bounds(this.width / 2 + 1, this.height - 28, 150, 20).build());

        this.updateButtonStatus(null);
    }

    @Override
    protected void setInitialFocus() {
        this.setInitialFocus(this.searchBox);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(this.lastScreen);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.searchBox.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public void removed() {
        if (this.list != null) this.list.clearEntries();
    }

    public void updateButtonStatus(@Nullable FbrWorldSelectionEntry selected) {
        if (this.convertButton == null) return;
        this.convertButton.active = selected != null;
        this.deleteButton.active = selected != null;
    }

    private void tryConvert(Button button) {
        FbrWorldSelectionEntry selected = this.list.getSelected();
        if (selected == null) return;
        FbrConversionLauncher.startImport(this.minecraft, this.lastScreen, List.of(this.selectedReplay), selected.worldPath);
    }

    private void tryDelete(Button button) {
        FbrWorldSelectionEntry selected = this.list.getSelected();
        if (selected == null) return;
        try (var walk = Files.walk(selected.worldPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException e) {
            com.moulberry.fbrtoworld.FbrToWorldMod.LOGGER.error("Failed to delete world {}", selected.worldName, e);
        }
        this.list.reload();
    }
}
