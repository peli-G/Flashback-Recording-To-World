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
import java.util.List;

public class FbrSelectReplayScreen extends Screen {

    public final Screen lastScreen;
    public final Path path;
    public final boolean combineMode;

    private Button convertButton;
    private Button gameruleOptionsButton;
    private Button deleteButton;
    private EditBox searchBox;
    private FbrReplaySelectionList list;

    private FbrSorting currentSorting = FbrSorting.CREATED_DATE;
    private boolean sortDescending = true;

    public FbrSelectReplayScreen(Screen parent) {
        this(parent, FbrToWorldMod.getDefaultReplaysFolder(), false);
    }

    public FbrSelectReplayScreen(Screen parent, Path path) {
        this(parent, path, false);
    }

    public FbrSelectReplayScreen(Screen parent, Path path, boolean combineMode) {
        super(Component.literal(combineMode ? "Select Replays to Combine" : "Select Replay"));
        this.lastScreen = parent;
        this.path = path;
        this.combineMode = combineMode;
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
                this.currentSorting, this.sortDescending, this.combineMode, this.list));

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

        if (this.combineMode) {
            int combining = this.combineCount();
            String hint;
            int hintColour;
            if (combining >= 2) {
                hint = "Combining " + combining + " replays - later picks win overlaps";
                hintColour = 0xFF55FF55;
            } else if (combining == 1) {
                hint = "1 ticked - tick more to combine, or convert just this one";
                hintColour = 0xFFFFFF55;
            } else {
                hint = "You need to have at least one Flashback ticked!";
                hintColour = 0xFFFF5555;
            }
            guiGraphics.drawCenteredString(this.font, hint, this.width / 2, this.height - 62, hintColour);
        }
    }

    @Override
    public void removed() {
        if (this.list != null) this.list.clearEntries();
    }

    
    public void onCombineSelectionChanged() {
        if (this.list == null) return;
        FbrReplaySelectionEntry.ReplayListEntry selected = this.list.getReplayListEntry();
        this.updateButtonStatus(selected != null ? selected.summary : null);
    }

    private int combineCount() {
        return this.list == null ? 0 : this.list.getCombineCount();
    }

    public void updateButtonStatus(@Nullable FbrReplaySummary summary) {
        if (this.convertButton == null) return;

        if (this.combineMode) {
            int combining = this.combineCount();
            this.convertButton.setMessage(
                    combining >= 2 ? Component.literal("Combine " + combining + " to World")
                  : combining == 1 ? Component.literal("Convert Ticked Replay")
                                   : Component.literal("Convert to World"));
            this.convertButton.active = combining >= 1;
            this.deleteButton.active = summary != null;
            return;
        }

        if (summary == null) {
            this.convertButton.active = false;
            this.deleteButton.active = false;
        } else {
            this.convertButton.active = summary.canOpen();
            this.deleteButton.active = true;
        }
    }

    
    public void openConvertOptions(Path selectedReplay) {
        this.minecraft.setScreen(new FbrConvertOptionsScreen(this, selectedReplay));
    }

    private void tryConvert(Button button) {
        if (this.combineMode) {
            List<Path> combine = this.list.getCombineSelection();
            if (!combine.isEmpty()) {
                FbrConversionLauncher.start(this.minecraft, this.lastScreen, combine);
            }
            return;
        }

        FbrReplaySelectionEntry.ReplayListEntry entry = this.list.getReplayListEntry();
        if (entry != null) this.openConvertOptions(entry.summary.getPath());
    }

    private void tryDelete(Button button) {
        FbrReplaySelectionEntry.ReplayListEntry entry = this.list.getReplayListEntry();
        if (entry != null) entry.deleteReplay();
    }
}
