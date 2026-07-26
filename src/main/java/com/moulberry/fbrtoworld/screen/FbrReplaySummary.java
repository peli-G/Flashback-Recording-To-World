package com.moulberry.fbrtoworld.screen;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public class FbrReplaySummary {
    private final Path path;
    private final FbrMeta metadata;
    private final String replayId;
    private final long lastModified;
    private final long filesize;
    private final byte[] iconBytes;
    private Component info = null;
    private boolean canOpen = true;
    private boolean hasWarning = false;

    public FbrReplaySummary(Path path, FbrMeta metadata, String replayId, long lastModified,
                             long filesize, @Nullable byte[] iconBytes) {
        this.path = path;
        this.metadata = metadata;
        this.replayId = replayId;
        this.lastModified = lastModified;
        this.filesize = filesize;
        this.iconBytes = iconBytes;

        if (metadata.dataVersion != 0
                && metadata.dataVersion != SharedConstants.getCurrentVersion().dataVersion().version()) {
            this.hasWarning = true;
        }
    }

    public Path getPath() { return this.path; }
    public String getReplayId() { return this.replayId; }
    public FbrMeta getMetadata() { return this.metadata; }

    public String getReplayName() {
        return this.metadata.name == null || this.metadata.name.isEmpty() ? this.replayId : this.metadata.name;
    }

    @Nullable
    public String getWorldName() { return this.metadata.worldName; }

    public long getLastModified() { return this.lastModified; }
    public long getFilesize() { return this.filesize; }
    public byte[] getIconBytes() { return this.iconBytes; }
    public boolean canOpen() { return this.canOpen; }
    public boolean hasWarning() { return this.hasWarning; }

    public Component getInfo() {
        if (this.info == null) {
            if (this.metadata.totalTicks > 0) {
                int ticks = this.metadata.totalTicks;
                int seconds = (ticks / 20) % 60;
                int minutes = (ticks / 20 / 60) % 60;
                int hours = ticks / 20 / 60 / 60;

                StringBuilder b = new StringBuilder();
                if (hours > 0) b.append(hours).append('h');
                if (minutes > 0) { if (!b.isEmpty()) b.append(' '); b.append(minutes).append('m'); }
                if (seconds > 0) { if (!b.isEmpty()) b.append(' '); b.append(seconds).append('s'); }
                if (b.isEmpty()) b.append("0s");

                this.info = Component.literal("Duration: " + b);
            } else {
                this.info = Component.empty();
            }
        }
        return this.info;
    }
}
