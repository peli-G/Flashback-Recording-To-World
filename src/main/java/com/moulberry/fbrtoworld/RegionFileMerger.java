package com.moulberry.fbrtoworld;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RegionFileMerger {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;

    private RegionFileMerger() {}

    public static void mergeInto(Path stagedFile, Path realFile) throws IOException {
        byte[][] entries = new byte[1024][];
        if (Files.exists(realFile)) {
            readAllEntries(realFile, entries);
        }
        readAllEntries(stagedFile, entries);
        writeMerged(realFile, entries);
    }

    private static void readAllEntries(Path file, byte[][] entries) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            if (raf.length() < (long) HEADER_SECTORS * SECTOR_SIZE) return;

            int[] offsets = new int[1024];
            raf.seek(0L);
            for (int i = 0; i < 1024; i++) offsets[i] = raf.readInt();

            for (int i = 0; i < 1024; i++) {
                int off = offsets[i];
                if (off == 0) continue;

                int sectorOffset = off >>> 8;
                int sectorCount = off & 0xFF;
                if (sectorOffset < HEADER_SECTORS || sectorCount == 0) continue;

                raf.seek((long) sectorOffset * SECTOR_SIZE);
                byte[] lenBuf = new byte[4];
                raf.readFully(lenBuf);
                int length = ((lenBuf[0] & 0xFF) << 24) | ((lenBuf[1] & 0xFF) << 16)
                        | ((lenBuf[2] & 0xFF) << 8) | (lenBuf[3] & 0xFF);
                if (length <= 0 || length > (long) sectorCount * SECTOR_SIZE) continue;

                byte[] full = new byte[4 + length];
                System.arraycopy(lenBuf, 0, full, 0, 4);
                raf.readFully(full, 4, length);
                entries[i] = full;
            }
        }
    }

    private static void writeMerged(Path realFile, byte[][] entries) throws IOException {
        Path parent = realFile.getParent();
        if (parent != null) Files.createDirectories(parent);

        int[] offsets = new int[1024];
        int[] timestamps = new int[1024];
        int nextSector = HEADER_SECTORS;
        int now = (int) (System.currentTimeMillis() / 1000);
        byte[] padding = new byte[SECTOR_SIZE];

        Path tmp = realFile.resolveSibling(realFile.getFileName().toString() + ".merging");
        try (RandomAccessFile raf = new RandomAccessFile(tmp.toFile(), "rw")) {
            raf.setLength(0L);
            raf.write(new byte[SECTOR_SIZE * HEADER_SECTORS]);

            for (int i = 0; i < 1024; i++) {
                byte[] entry = entries[i];
                if (entry == null) continue;

                int sectors = (entry.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
                raf.seek((long) nextSector * SECTOR_SIZE);
                raf.write(entry);
                int pad = SECTOR_SIZE - (entry.length % SECTOR_SIZE);
                if (pad < SECTOR_SIZE) raf.write(padding, 0, pad);

                offsets[i] = (nextSector << 8) | (sectors & 0xFF);
                timestamps[i] = now;
                nextSector += sectors;
            }

            raf.seek(0L);
            for (int off : offsets) raf.writeInt(off);
            for (int ts : timestamps) raf.writeInt(ts);
        }

        Files.move(tmp, realFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
