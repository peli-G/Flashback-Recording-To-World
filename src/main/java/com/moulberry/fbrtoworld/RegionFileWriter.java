package com.moulberry.fbrtoworld;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal Anvil region file writer.
 * Accumulates all chunks in memory, then flushes everything in one write on close().
 */
public class RegionFileWriter implements Closeable {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;

    private final Path path;
    private final int[] offsets = new int[1024];
    private final int[] timestamps = new int[1024];
    private final byte[][] chunkData = new byte[1024][];

    public RegionFileWriter(Path path) {
        this.path = path;
    }

    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        int index = (pos.x & 31) + (pos.z & 31) * 32;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))) {
            NbtIo.write(nbt, (DataOutput) dos);
        }

        byte[] compressed = baos.toByteArray();
        // 5-byte entry header: 4 bytes length (includes the compression-type byte) + 1 byte type
        int entryLen = 1 + compressed.length;
        byte[] entry = new byte[5 + compressed.length];
        entry[0] = (byte)((entryLen >> 24) & 0xFF);
        entry[1] = (byte)((entryLen >> 16) & 0xFF);
        entry[2] = (byte)((entryLen >>  8) & 0xFF);
        entry[3] = (byte)( entryLen        & 0xFF);
        entry[4] = 2; // zlib
        System.arraycopy(compressed, 0, entry, 5, compressed.length);

        chunkData[index] = entry;
        timestamps[index] = (int)(System.currentTimeMillis() / 1000);
    }

    @Override
    public void close() throws IOException {
        // Build offset table: each entry is (sectorOffset << 8) | sectorCount
        int currentSector = HEADER_SECTORS;
        for (int i = 0; i < 1024; i++) {
            if (chunkData[i] == null) { offsets[i] = 0; continue; }
            int sectors = (chunkData[i].length + SECTOR_SIZE - 1) / SECTOR_SIZE;
            offsets[i] = (currentSector << 8) | (sectors & 0xFF);
            currentSector += sectors;
        }

        Files.createDirectories(path.getParent());
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
            for (int off : offsets)    raf.writeInt(off);
            for (int ts  : timestamps) raf.writeInt(ts);
            for (int i = 0; i < 1024; i++) {
                if (chunkData[i] == null) continue;
                raf.write(chunkData[i]);
                // Pad to sector boundary
                int pad = SECTOR_SIZE - (chunkData[i].length % SECTOR_SIZE);
                if (pad < SECTOR_SIZE) raf.write(new byte[pad]);
            }
        }
    }
}
