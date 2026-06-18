package com.moulberry.fbrtoworld.screen;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.Locale;

public interface FbrPendingEntry {

    FbrReplaySelectionEntry createEntry(FbrReplaySelectionList list, Minecraft minecraft);
    boolean matchesFilter(String filter);

    record Folder(Path path, String filename, long modifiedTime, int replayCount) implements FbrPendingEntry {
        public Folder(Path path, long modifiedTime, int replayCount) {
            this(path, path.getFileName().toString().toLowerCase(Locale.ROOT), modifiedTime, replayCount);
        }

        @Override
        public FbrReplaySelectionEntry createEntry(FbrReplaySelectionList list, Minecraft minecraft) {
            return new FbrReplaySelectionEntry.ReplayFolder(list, minecraft, this.path, this.modifiedTime, this.replayCount);
        }

        @Override
        public boolean matchesFilter(String filter) {
            return this.filename.contains(filter);
        }
    }

    record Replay(FbrReplaySummary summary) implements FbrPendingEntry {
        @Override
        public FbrReplaySelectionEntry createEntry(FbrReplaySelectionList list, Minecraft minecraft) {
            return new FbrReplaySelectionEntry.ReplayListEntry(list, minecraft, this.summary);
        }

        @Override
        public boolean matchesFilter(String filter) {
            if (this.summary.getReplayName().toLowerCase(Locale.ROOT).contains(filter)
                    || this.summary.getReplayId().toLowerCase(Locale.ROOT).contains(filter)) {
                return true;
            }
            String worldName = this.summary.getWorldName();
            return worldName != null && worldName.toLowerCase(Locale.ROOT).contains(filter);
        }
    }
}
