package com.moulberry.fbrtoworld;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.zip.DeflaterOutputStream;


public class RegionFileWriter implements Closeable {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int MAX_SECTORS = 255;
    private static final int MAX_OPEN_HANDLES = 64;

    private static final ArrayDeque<RegionFileWriter> OPEN_WRITERS = new ArrayDeque<>();

    private final Path path;
    private final int[] offsets = new int[1024];
    private final int[] timestamps = new int[1024];
    private final byte[] padding = new byte[SECTOR_SIZE];

    private RandomAccessFile raf;
    private boolean created = false;
    private boolean finished = false;
    private int nextSector = HEADER_SECTORS;

    public RegionFileWriter(Path path) throws IOException {
        this.path = path;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        if (this.finished) throw new IOException("Region file already closed: " + this.path);

        int index = (pos.x & 31) + (pos.z & 31) * 32;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(new DeflaterOutputStream(baos))) {
            NbtIo.write(nbt, (DataOutput) dos);
        }

        byte[] compressed = baos.toByteArray();

        int entryLen = 1 + compressed.length;
        byte[] entry = new byte[5 + compressed.length];
        entry[0] = (byte)((entryLen >> 24) & 0xFF);
        entry[1] = (byte)((entryLen >> 16) & 0xFF);
        entry[2] = (byte)((entryLen >>  8) & 0xFF);
        entry[3] = (byte)( entryLen        & 0xFF);
        entry[4] = 2;
        System.arraycopy(compressed, 0, entry, 5, compressed.length);

        int sectors = (entry.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
        if (sectors > MAX_SECTORS) {

            throw new IOException("Chunk " + pos.x + "," + pos.z
                    + " is too large for the region format (" + entry.length + " bytes)");
        }

        this.ensureOpen();
        this.raf.seek((long) this.nextSector * SECTOR_SIZE);
        this.raf.write(entry);
        int pad = SECTOR_SIZE - (entry.length % SECTOR_SIZE);
        if (pad < SECTOR_SIZE) this.raf.write(this.padding, 0, pad);

        this.offsets[index] = (this.nextSector << 8) | (sectors & 0xFF);
        this.timestamps[index] = (int)(System.currentTimeMillis() / 1000);
        this.nextSector += sectors;
    }

    @Override
    public void close() throws IOException {
        if (this.finished) return;
        try {
            this.ensureOpen();
            this.raf.seek(0L);
            for (int off : this.offsets)    this.raf.writeInt(off);
            for (int ts  : this.timestamps) this.raf.writeInt(ts);
        } finally {
            this.finished = true;
            this.releaseHandle();
        }
    }

    private void ensureOpen() throws IOException {
        if (this.raf != null) return;

        this.raf = new RandomAccessFile(this.path.toFile(), "rw");
        if (!this.created) {
            this.raf.setLength(0L);

            this.raf.write(new byte[SECTOR_SIZE * HEADER_SECTORS]);
            this.created = true;
        }
        registerOpen(this);
    }

    private void releaseHandle() {
        unregisterOpen(this);
        RandomAccessFile open = this.raf;
        this.raf = null;
        if (open == null) return;
        try {
            open.close();
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to close region file {}: {}", this.path, e.getMessage());
        }
    }

    private static synchronized void registerOpen(RegionFileWriter writer) {
        OPEN_WRITERS.addLast(writer);
        while (OPEN_WRITERS.size() > MAX_OPEN_HANDLES) {
            RegionFileWriter victim = OPEN_WRITERS.peekFirst();
            if (victim == null || victim == writer) break;
            victim.releaseHandle();
        }
    }

    private static synchronized void unregisterOpen(RegionFileWriter writer) {
        OPEN_WRITERS.remove(writer);
    }
}
