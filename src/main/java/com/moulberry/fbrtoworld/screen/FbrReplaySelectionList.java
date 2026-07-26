package com.moulberry.fbrtoworld.screen;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
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

    private final FbrSelectReplayScreen screen;
    private CompletableFuture<List<FbrPendingEntry>> pendingReplays;
    @Nullable
    private List<FbrPendingEntry> currentlyDisplayed;
    private String filter;
    private FbrSorting sorting;
    private boolean sortDescending;
    private final FbrReplaySelectionEntry.LoadingHeader loadingHeader;

    public FbrReplaySelectionList(FbrSelectReplayScreen screen, Minecraft minecraft, int width, int height,
                                   int y0, int itemHeight, String filter, FbrSorting sorting, boolean sortDescending,
                                   @Nullable FbrReplaySelectionList previous) {
        super(minecraft, width, height, y0, itemHeight);
        this.screen = screen;
        this.loadingHeader = new FbrReplaySelectionEntry.LoadingHeader(minecraft);
        this.filter = filter;
        this.sorting = sorting;
        this.sortDescending = sortDescending;
        this.pendingReplays = previous != null ? previous.pendingReplays : this.loadReplays();
        this.fillLoadingReplays();
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
                entry.startConversion();
                return true;
            }
        }
        return super.keyPressed(keyEvent);
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
    public int getRowWidth() { return 270; }

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
