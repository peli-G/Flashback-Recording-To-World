package com.moulberry.fbrtoworld;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;


/**
 * Writes a Minecraft region (.mca) file. Chunks are appended one after another through a
 * large buffer (big sequential writes instead of a seek + small write per chunk); the
 * header with every chunk's location is written once, on close.
 */
public class RegionFileWriter implements Closeable {

    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int MAX_SECTORS = 255;
    private static final int MAX_OPEN_HANDLES = 96;
    private static final int WRITE_BUFFER = 1 << 18;

    /** Region-file compression ids (same numbers Minecraft uses). */
    private static final byte COMPRESSION_ZLIB = 2;
    private static final byte COMPRESSION_LZ4 = 4;

    private static final ArrayDeque<RegionFileWriter> OPEN_WRITERS = new ArrayDeque<>();

    /** One reusable zlib compressor per thread (making a new one per chunk is slow). */
    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(Deflater::new);

    private static final boolean LZ4_AVAILABLE = detectLz4();

    private final Path path;
    private final int[] offsets = new int[1024];
    private final int[] timestamps = new int[1024];
    private final byte[] padding = new byte[SECTOR_SIZE];

    private OutputStream out;
    private boolean created = false;
    private boolean finished = false;
    private int nextSector = HEADER_SECTORS;

    public RegionFileWriter(Path path) throws IOException {
        this.path = path;
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);
    }

    private static boolean detectLz4() {
        try {
            Class.forName("net.jpountz.lz4.LZ4BlockOutputStream");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Whether LZ4 can be used (Minecraft ships the LZ4 library; checked just in case). */
    public static boolean lz4Available() {
        return LZ4_AVAILABLE;
    }

    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        this.writeEntry(pos, encode(pos, nbt));
    }

    public static byte[] encode(ChunkPos pos, CompoundTag nbt) throws IOException {
        return encode(pos, nbt, false);
    }

    /** Compresses a chunk into the exact bytes stored in a region file. Safe to run on many threads. */
    public static byte[] encode(ChunkPos pos, CompoundTag nbt, boolean lz4) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(16384);
        try (DataOutputStream dos = new DataOutputStream(raw)) {
            NbtIo.write(nbt, (DataOutput) dos);
        }
        return encodeRaw(pos, raw.toByteArray(), lz4);
    }

    /** Same as {@link #encode} but from already-serialized NBT bytes. */
    public static byte[] encodeRaw(ChunkPos pos, byte[] nbtBytes, boolean lz4) throws IOException {
        boolean useLz4 = lz4 && LZ4_AVAILABLE;
        byte[] compressed = useLz4 ? Lz4.compress(nbtBytes) : deflate(nbtBytes);

        int entryLen = 1 + compressed.length;
        byte[] entry = new byte[5 + compressed.length];
        entry[0] = (byte)((entryLen >> 24) & 0xFF);
        entry[1] = (byte)((entryLen >> 16) & 0xFF);
        entry[2] = (byte)((entryLen >>  8) & 0xFF);
        entry[3] = (byte)( entryLen        & 0xFF);
        entry[4] = useLz4 ? COMPRESSION_LZ4 : COMPRESSION_ZLIB;
        System.arraycopy(compressed, 0, entry, 5, compressed.length);

        int sectors = (entry.length + SECTOR_SIZE - 1) / SECTOR_SIZE;
        if (sectors > MAX_SECTORS) {

            throw new IOException("Chunk " + pos.x + "," + pos.z
                    + " is too large for the region format (" + entry.length + " bytes)");
        }
        return entry;
    }

    private static byte[] deflate(byte[] input) throws IOException {
        Deflater deflater = DEFLATER.get();
        deflater.reset();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, input.length / 4));
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater, 8192)) {
            dos.write(input);
        }
        return baos.toByteArray();
    }

    /** Kept in its own class so a missing LZ4 library can never break loading this one. */
    private static final class Lz4 {
        static byte[] compress(byte[] input) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, input.length / 2));
            try (OutputStream os = new net.jpountz.lz4.LZ4BlockOutputStream(baos)) {
                os.write(input);
            }
            return baos.toByteArray();
        }
    }

    /** Writes bytes made by {@link #encode}/{@link #encodeRaw}. Region files are written from one thread only. */
    public void writeEntry(ChunkPos pos, byte[] entry) throws IOException {
        if (this.finished) throw new IOException("Region file already closed: " + this.path);

        int index = (pos.x & 31) + (pos.z & 31) * 32;
        int sectors = (entry.length + SECTOR_SIZE - 1) / SECTOR_SIZE;

        this.ensureOpen();
        this.out.write(entry);
        int pad = SECTOR_SIZE - (entry.length % SECTOR_SIZE);
        if (pad < SECTOR_SIZE) this.out.write(this.padding, 0, pad);

        this.offsets[index] = (this.nextSector << 8) | (sectors & 0xFF);
        this.timestamps[index] = (int)(System.currentTimeMillis() / 1000);
        this.nextSector += sectors;
    }

    @Override
    public void close() throws IOException {
        if (this.finished) return;
        try {
            this.ensureOpen();      // creates the file if nothing was written yet
            this.releaseHandle();   // flushes everything written so far
            ByteBuffer header = ByteBuffer.allocate(SECTOR_SIZE * HEADER_SECTORS);
            for (int off : this.offsets)    header.putInt(off);
            for (int ts  : this.timestamps) header.putInt(ts);
            try (RandomAccessFile raf = new RandomAccessFile(this.path.toFile(), "rw")) {
                raf.seek(0L);
                raf.write(header.array());
            }
        } finally {
            this.finished = true;
            this.releaseHandle();
        }
    }

    private void ensureOpen() throws IOException {
        if (this.out != null) return;

        if (!this.created) {
            this.out = new BufferedOutputStream(new FileOutputStream(this.path.toFile(), false), WRITE_BUFFER);
            this.out.write(new byte[SECTOR_SIZE * HEADER_SECTORS]);
            this.created = true;
        } else {
            // Re-opened after being closed to free a handle: keep appending at the end.
            this.out = new BufferedOutputStream(new FileOutputStream(this.path.toFile(), true), WRITE_BUFFER);
        }
        registerOpen(this);
    }

    private void releaseHandle() {
        unregisterOpen(this);
        OutputStream open = this.out;
        this.out = null;
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
