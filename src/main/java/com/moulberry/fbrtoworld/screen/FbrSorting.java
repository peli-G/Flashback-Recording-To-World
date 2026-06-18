package com.moulberry.fbrtoworld.screen;

import net.minecraft.network.chat.Component;

import java.util.Comparator;

public enum FbrSorting {

    CREATED_DATE(
        Component.literal("Created Date"),
        Comparator.comparingLong(FbrReplaySummary::getLastModified).reversed(),
        Comparator.comparingLong(FbrReplaySummary::getLastModified)
    ),
    REPLAY_NAME(
        Component.literal("Replay Name"),
        Comparator.comparing(FbrReplaySummary::getReplayName, FbrNaturalOrderComparator.INSTANCE),
        Comparator.comparing(FbrReplaySummary::getReplayName, FbrNaturalOrderComparator.INSTANCE.reversed())
    ),
    WORLD_NAME(
        Component.literal("World Name"),
        Comparator.comparing(FbrReplaySummary::getWorldName, Comparator.nullsLast(FbrNaturalOrderComparator.INSTANCE)),
        Comparator.comparing(FbrReplaySummary::getWorldName, Comparator.nullsLast(FbrNaturalOrderComparator.INSTANCE.reversed()))
    ),
    DURATION(
        Component.literal("Duration"),
        Comparator.<FbrReplaySummary>comparingInt(r -> r.getMetadata().totalTicks).reversed(),
        Comparator.comparingInt(r -> r.getMetadata().totalTicks)
    ),
    FILESIZE(
        Component.literal("File Size"),
        Comparator.comparingLong(FbrReplaySummary::getFilesize).reversed(),
        Comparator.comparingLong(FbrReplaySummary::getFilesize)
    );

    private final Component component;
    private final Comparator<FbrReplaySummary> descendingComparator;
    private final Comparator<FbrReplaySummary> ascendingComparator;

    FbrSorting(Component component, Comparator<FbrReplaySummary> descendingComparator, Comparator<FbrReplaySummary> ascendingComparator) {
        this.component = component;
        this.descendingComparator = descendingComparator.thenComparing(FbrReplaySummary::getReplayId, FbrNaturalOrderComparator.INSTANCE);
        this.ascendingComparator = ascendingComparator.thenComparing(FbrReplaySummary::getReplayId, FbrNaturalOrderComparator.INSTANCE);
    }

    public Component component() { return this.component; }

    public Comparator<FbrPendingEntry> comparator(boolean descending) {
        Comparator<FbrReplaySummary> replayComparator = descending ? this.descendingComparator : this.ascendingComparator;
        return (entry1, entry2) -> {
            if (entry1 instanceof FbrPendingEntry.Replay r1) {
                if (entry2 instanceof FbrPendingEntry.Replay r2) {
                    return replayComparator.compare(r1.summary(), r2.summary());
                }
                return 1;
            } else if (entry1 instanceof FbrPendingEntry.Folder f1) {
                if (entry2 instanceof FbrPendingEntry.Folder f2) {
                    return FbrNaturalOrderComparator.INSTANCE.compare(f1.filename(), f2.filename());
                }
                return -1;
            }
            return 0;
        };
    }
}
