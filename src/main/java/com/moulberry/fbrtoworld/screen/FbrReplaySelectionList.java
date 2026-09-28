package com.moulberry.fbrtoworld.screen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class FbrReplaySelectionList extends ObjectSelectionList<FbrReplaySelectionEntry> {

    public static final int CHECKBOX_GUTTER = 24;

    
    private static final LinkedHashSet<Path> COMBINE_SELECTION = new LinkedHashSet<>();

    private final FbrSelectReplayScreen screen;
    private final boolean combineMode;
    private CompletableFuture<List<FbrPendingEntry>> pendingReplays;
    @Nullable
    private List<FbrPendingEntry> currentlyDisplayed;
    private String filter;
    private FbrSorting sorting;
    private boolean sortDescending;
    private final FbrReplaySelectionEntry.LoadingHeader loadingHeader;

    public FbrReplaySelectionList(FbrSelectReplayScreen screen, Minecraft minecraft, int width, int height,
                                   int y0, int itemHeight, String filter, FbrSorting sorting, boolean sortDescending,
                                   boolean combineMode, @Nullable FbrReplaySelectionList previous) {
        super(minecraft, width, height, y0, itemHeight);
        this.screen = screen;
        this.combineMode = combineMode;
        this.loadingHeader = new FbrReplaySelectionEntry.LoadingHeader(minecraft);
        this.filter = filter;
        this.sorting = sorting;
        this.sortDescending = sortDescending;
        this.pendingReplays = previous != null ? previous.pendingReplays : this.loadReplays();
        this.fillLoadingReplays();
    }

    public boolean isCombineMode() { return this.combineMode; }

    
    public static void startCombineSession(@Nullable Path preselected) {
        COMBINE_SELECTION.clear();
        if (preselected != null) COMBINE_SELECTION.add(preselected);
    }

    
    public int getCombineOrder(Path path) {
        int i = 1;
        for (Path p : COMBINE_SELECTION) {
            if (p.equals(path)) return i;
            i++;
        }
        return 0;
    }

    public void toggleCombine(Path path) {
        if (!COMBINE_SELECTION.remove(path)) {
            COMBINE_SELECTION.add(path);
        }
        this.screen.onCombineSelectionChanged();
    }

    public void removeFromCombine(Path path) {
        if (COMBINE_SELECTION.remove(path)) {
            this.screen.onCombineSelectionChanged();
        }
    }

    
    public List<Path> getCombineSelection() {
        List<Path> result = new ArrayList<>();
        for (Path p : COMBINE_SELECTION) {
            if (Files.exists(p)) result.add(p);
        }
        return result;
    }

    public int getCombineCount() {
        return this.getCombineSelection().size();
    }

    @Override
    protected void clearEntries() {
        this.children().forEach(FbrReplaySelectionEntry::close);
        super.clearEntries();
    }

    @Nullable
    private List<FbrPendingEntry> pollIgnoreErrors() {
        try {
            return this.pendingReplays.getNow(null);
        } catch (CancellationException | CompletionException e) {
            return null;
        }
    }

    public void reloadReplayList() {
        this.pendingReplays = this.loadReplays();
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (keyEvent.isSelection()) {
            FbrReplaySelectionEntry.ReplayListEntry entry = this.getReplayListEntry();
            if (entry != null) {
                this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                if (this.combineMode) {
                    this.toggleCombine(entry.summary.getPath());
                } else {
                    this.screen.openConvertOptions(entry.summary.getPath());
                }
                return true;
            }
        }
        return super.keyPressed(keyEvent);
    }

    /**
     * The tick boxes are drawn in the gutter to the LEFT of each row, which is outside the row's
     * hitbox - vanilla never routes those clicks to the entry. Catch them here instead.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (this.combineMode && event.button() == 0 && this.isMouseOver(event.x(), event.y())) {
            int rowLeft = this.getRowLeft();
            if (event.x() >= rowLeft - CHECKBOX_GUTTER && event.x() < rowLeft) {
                for (FbrReplaySelectionEntry entry : this.children()) {
                    if (entry instanceof FbrReplaySelectionEntry.ReplayListEntry replay
                            && event.y() >= entry.getY() && event.y() < entry.getY() + entry.getHeight()) {
                        this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                        this.setSelected(replay);
                        this.toggleCombine(replay.summary.getPath());
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int i, int j, float f) {
        List<FbrPendingEntry> list = this.pollIgnoreErrors();
        if (this.currentlyDisplayed != list) {
            if (list == null) {
                this.fillLoadingReplays();
            } else {
                list.sort(this.sorting.comparator(this.sortDescending));
                this.fillReplays(this.filter, list);
            }
            this.currentlyDisplayed = list;
        }
        super.renderWidget(guiGraphics, i, j, f);
    }

    public void updateFilter(String filter, FbrSorting sorting, boolean sortDescending) {
        if (this.currentlyDisplayed != null) {
            if (this.sorting != sorting || this.sortDescending != sortDescending) {
                this.currentlyDisplayed.sort(sorting.comparator(sortDescending));
                this.fillReplays(filter, this.currentlyDisplayed);
            } else if (!filter.equals(this.filter)) {
                this.fillReplays(filter, this.currentlyDisplayed);
            }
        }
        this.filter = filter;
        this.sorting = sorting;
        this.sortDescending = sortDescending;
    }

    private CompletableFuture<List<FbrPendingEntry>> loadReplays() {
        Path dir = this.screen.path;
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        List<CompletableFuture<FbrPendingEntry>> futures = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        try {
                            BasicFileAttributes attrs = Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
                            long lastModified = Math.max(attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis());
                            int replayCount = 0;
                            try (DirectoryStream<Path> inner = Files.newDirectoryStream(path)) {
                                for (Path f : inner) {
                                    if (f.toString().endsWith(".zip")) replayCount++;
                                }
                            }
                            return new FbrPendingEntry.Folder(path, lastModified, replayCount);
                        } catch (IOException e) {
                            FbrToWorldMod.LOGGER.error("Failed to load replay folder", e);
                            return null;
                        }
                    }, Util.backgroundExecutor()));
                    continue;
                }

                if (!path.toString().endsWith(".zip")) continue;

                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        String fileName = path.getFileName().toString();
                        BasicFileAttributes attrs = Files.getFileAttributeView(path, BasicFileAttributeView.class).readAttributes();
                        long lastModified = Math.max(attrs.creationTime().toMillis(), attrs.lastModifiedTime().toMillis());
                        long filesize = attrs.size();

                        byte[] iconBytes = null;
                        String metadataString = null;

                        try (FileSystem fs = FileSystems.newFileSystem(path)) {
                            Path iconPath = fs.getPath("/icon.png");
                            if (Files.exists(iconPath)) iconBytes = Files.readAllBytes(iconPath);
                            Path metaPath = fs.getPath("/metadata.json");
                            if (Files.exists(metaPath)) metadataString = Files.readString(metaPath);
                        }

                        if (metadataString == null) return null;

                        JsonObject metaJson = new Gson().fromJson(metadataString, JsonObject.class);
                        FbrMeta meta = FbrMeta.fromJson(metaJson);
                        if (meta == null) return null;

                        FbrReplaySummary summary = new FbrReplaySummary(path, meta, fileName, lastModified, filesize, iconBytes);
                        return new FbrPendingEntry.Replay(summary);
                    } catch (IOException e) {
                        FbrToWorldMod.LOGGER.error("Failed to load replay {}", path, e);
                        return null;
                    }
                }, Util.backgroundExecutor()));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }

        return Util.sequenceFailFastAndCancel(futures).thenApply(list -> {
            list.removeIf(Objects::isNull);
            return list;
        });
    }

    private void fillReplays(String filter, List<FbrPendingEntry> list) {
        this.clearEntries();
        String lowerFilter = filter.toLowerCase(Locale.ROOT);
        for (FbrPendingEntry entry : list) {
            if (entry.matchesFilter(lowerFilter)) {
                this.addEntry(entry.createEntry(this, this.minecraft));
            }
        }
        this.notifyListUpdated();
    }

    private void fillLoadingReplays() {
        this.clearEntries();
        this.addEntry(this.loadingHeader);
        this.notifyListUpdated();
    }

    private void notifyListUpdated() {
        this.refreshScrollAmount();
        this.screen.triggerImmediateNarration(true);
        this.screen.updateButtonStatus(null);
    }

    @Override
    public int getRowWidth() { return this.combineMode ? 270 - CHECKBOX_GUTTER : 270; }

    @Override
    public int getRowLeft() { return super.getRowLeft() + (this.combineMode ? CHECKBOX_GUTTER : 0); }

    @Override
    public void setSelected(@Nullable FbrReplaySelectionEntry entry) {
        super.setSelected(entry);
        if (entry instanceof FbrReplaySelectionEntry.ReplayListEntry e) {
            this.screen.updateButtonStatus(e.summary);
        } else {
            this.screen.updateButtonStatus(null);
        }
    }

    @Nullable
    public FbrReplaySelectionEntry.ReplayListEntry getReplayListEntry() {
        return this.getSelected() instanceof FbrReplaySelectionEntry.ReplayListEntry e ? e : null;
    }

    public FbrSelectReplayScreen getScreen() { return this.screen; }

    @Override
    public void updateWidgetNarration(NarrationElementOutput output) {
        if (this.children().contains(this.loadingHeader)) {
            this.loadingHeader.updateNarration(output);
            return;
        }
        super.updateWidgetNarration(output);
    }
}
