package com.moulberry.fbrtoworld.screen;

import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Standalone replay browser screen — equivalent to Flashback's SelectReplayScreen
 * but with zero dependency on Flashback being installed. Full UI: thumbnails,
 * search bar, sort dropdown + direction, folder navigation.
 */
public class FbrSelectReplayScreen extends Screen {

    public final Screen lastScreen;
    public final Path path;

    private Button convertButton;
    private Button gameruleOptionsButton;
    private Button deleteButton;
    private EditBox searchBox;
    private FbrReplaySelectionList list;

    private FbrSorting currentSorting = FbrSorting.CREATED_DATE;
    private boolean sortDescending = true;

    public FbrSelectReplayScreen(Screen parent) {
        this(parent, FbrToWorldMod.getDefaultReplaysFolder());
    }

    public FbrSelectReplayScreen(Screen parent, Path path) {
        super(Component.literal("Select Replay"));
        this.lastScreen = parent;
        this.path = path;
    }

    @Override
    protected void init() {
        this.searchBox = new EditBox(this.font, this.width / 2 - 128, 22, 126, 20, this.searchBox, Component.literal("Search"));
        this.searchBox.setResponder(value -> this.list.updateFilter(value, this.currentSorting, this.sortDescending));
        this.addWidget(this.searchBox);

        this.addRenderableWidget(CycleButton.builder(FbrSorting::component, this.currentSorting)
                .withValues(FbrSorting.values())
                .create(this.width / 2 + 2, 22, 125, 20, Component.literal("Sort"), (button, sorting) -> {
                    this.currentSorting = sorting;
                    this.list.updateFilter(this.searchBox.getValue(), this.currentSorting, this.sortDescending);
                }));

        this.addRenderableWidget(new FbrSortDirectionButton(this.width / 2 + 131, 22, 20, 20,
                Component.literal("Sort Direction"), descending -> {
            this.sortDescending = descending;
            this.list.updateFilter(this.searchBox.getValue(), this.currentSorting, this.sortDescending);
        }, this.sortDescending));

        this.list = this.addRenderableWidget(new FbrReplaySelectionList(this, this.minecraft,
                this.width, this.height - 112, 48, 36, this.searchBox.getValue(),
                this.currentSorting, this.sortDescending, this.list));

        this.convertButton = this.addRenderableWidget(Button.builder(Component.literal("Convert to World"), this::tryConvert)
                .bounds(this.width / 2 - 151, this.height - 52, 150, 20).build());

        this.gameruleOptionsButton = this.addRenderableWidget(Button.builder(Component.literal("Gamerule Options"), btn ->
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

    public void updateButtonStatus(@Nullable FbrReplaySummary summary) {
        if (this.convertButton == null) return;
        if (summary == null) {
            this.convertButton.active = false;
            this.deleteButton.active = false;
        } else {
            this.convertButton.active = summary.canOpen();
            this.deleteButton.active = true;
        }
    }

    private void tryConvert(Button button) {
        FbrReplaySelectionEntry.ReplayListEntry entry = this.list.getReplayListEntry();
        if (entry != null) entry.startConversion();
    }

    private void tryDelete(Button button) {
        FbrReplaySelectionEntry.ReplayListEntry entry = this.list.getReplayListEntry();
        if (entry != null) entry.deleteReplay();
    }
}
