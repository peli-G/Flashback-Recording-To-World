package com.moulberry.fbrtoworld;

import com.moulberry.fbrtoworld.mixin.ClientboundLevelChunkPacketDataAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Direct chunk translation (Faster Convert): turns a recorded chunk packet straight into
 * save-file NBT bytes, instead of loading it into Minecraft's chunk objects and encoding
 * those again. Block/biome palette entries are worked out once per block state through
 * Minecraft's own code and then reused.
 *
 * Safety: while converting, chunks are regularly also built the old way and compared by
 * meaning. If anything ever differs, direct translation switches itself off for the rest
 * of that conversion and the old way is used. Any chunk it can't read exactly also falls
 * back to the old way.
 */
final class FbrChunkTranslator {

    /** Fully checked chunks needed before checking drops to a sample. */
    private static final int CHECKS_BEFORE_SAMPLING = 64;
    /** After that, one chunk in this many is still checked. */
    private static final int SAMPLE_EVERY = 40;
    /** Give up on direct translation after this many unreadable chunks in a row. */
    private static final int MAX_CONSECUTIVE_FAILURES = 50;

    record Result(byte[] nbt, boolean hasBlocks) {}

    /** Per-conversion state, shared by all worker threads. */
    static final class Session {
        final RegistryAccess registryAccess;
        final com.mojang.serialization.DynamicOps<Tag> ops;
        final com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec;
        final com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
        final int dataVersion;

        final ConcurrentHashMap<Integer, Object> blockEntries = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Object> biomeEntries = new ConcurrentHashMap<>();

        volatile boolean enabled = true;
        volatile String disabledReason = null;
        final AtomicInteger checksPassed = new AtomicInteger();
        final AtomicLong translated = new AtomicLong();
        final AtomicLong fellBack = new AtomicLong();
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final AtomicLong counter = new AtomicLong();

        Session(RegistryAccess registryAccess,
                com.mojang.serialization.DynamicOps<Tag> ops,
                com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec,
                com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                int dataVersion) {
            this.registryAccess = registryAccess;
            this.ops = ops;
            this.blockCodec = blockCodec;
            this.biomeCodec = biomeCodec;
            this.dataVersion = dataVersion;
        }

        boolean shouldCheck() {
            if (this.checksPassed.get() < CHECKS_BEFORE_SAMPLING) return true;
            return this.counter.incrementAndGet() % SAMPLE_EVERY == 0;
        }

        void checkPassed() {
            this.checksPassed.incrementAndGet();
        }

        synchronized void disable(String reason) {
            if (!this.enabled) return;
            this.enabled = false;
            this.disabledReason = reason;
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Direct chunk translation switched off: {}", reason);
        }

        void noteSuccess() {
            this.translated.incrementAndGet();
            this.consecutiveFailures.set(0);
        }

        void noteFailure(String why) {
            this.fellBack.incrementAndGet();
            if (this.consecutiveFailures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                disable("too many chunks it couldn't read (last: " + why + ")");
            }
        }
    }

    private FbrChunkTranslator() {}

    // ------------------------------------------------------------------------------------
    // Translation
    // ------------------------------------------------------------------------------------

    static Result translate(Session s, ClientboundLevelChunkWithLightPacket packet, String dimension) throws Exception {
        int cx = packet.getX();
        int cz = packet.getZ();
        var chunkData = packet.getChunkData();
        byte[] raw = ((ClientboundLevelChunkPacketDataAccessor) (Object) chunkData).fbr$getBuffer();

        int minSectionY;
        int sectionCount;
        if ("minecraft:the_nether".equals(dimension) || "minecraft:the_end".equals(dimension)) {
            minSectionY = 0;
            sectionCount = 16;
        } else {
            minSectionY = -4;
            sectionCount = 24;
        }

        var lightData = packet.getLightData();
        BitSet skyMask = lightData.getSkyYMask();
        BitSet blockMask = lightData.getBlockYMask();
        BitSet emptySky = lightData.getEmptySkyYMask();
        BitSet emptyBlock = lightData.getEmptyBlockYMask();
        List<byte[]> skyArrays = lightData.getSkyUpdates();
        List<byte[]> blockArrays = lightData.getBlockUpdates();

        Map<Integer, byte[]> skyBySection = new HashMap<>();
        Map<Integer, byte[]> blockBySection = new HashMap<>();
        int skyIdx = 0, blockIdx = 0;
        int totalLightSections = sectionCount + 2;
        for (int li = 0; li < totalLightSections; li++) {
            if (skyMask.get(li) && skyIdx < skyArrays.size()) skyBySection.put(li, skyArrays.get(skyIdx++));
            if (blockMask.get(li) && blockIdx < blockArrays.size()) blockBySection.put(li, blockArrays.get(blockIdx++));
        }

        // The chunk's section data: try the current format first, then the older one with array lengths.
        int[] blockCount = new int[1];
        List<Object> sections = parseSections(s, raw, sectionCount, minSectionY, false,
                skyBySection, blockBySection, emptySky, emptyBlock, blockCount);
        if (sections == null) {
            blockCount[0] = 0;
            sections = parseSections(s, raw, sectionCount, minSectionY, true,
                    skyBySection, blockBySection, emptySky, emptyBlock, blockCount);
        }
        if (sections == null) throw new IllegalStateException("section data didn't match the expected layout");

        List<Object> blockEntities = new ArrayList<>();
        IOException[] beError = new IOException[1];
        chunkData.getBlockEntitiesTagsConsumer(cx, cz).accept((blockPos, beType, nbt) -> {
            CompoundTag beTag = nbt != null ? nbt.copy() : new CompoundTag();
            beTag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(beType).toString());
            beTag.putInt("x", blockPos.getX());
            beTag.putInt("y", blockPos.getY());
            beTag.putInt("z", blockPos.getZ());
            beTag.putByte("keepPacked", (byte) 0);
            try {
                blockEntities.add(new FbrNbtIO.RawCompound(FbrNbtIO.compoundPayload(nbtBytes(beTag))));
            } catch (IOException e) {
                beError[0] = e;
            }
        });
        if (beError[0] != null) throw beError[0];

        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("DataVersion", s.dataVersion);
        chunk.put("xPos", cx);
        chunk.put("zPos", cz);
        chunk.put("yPos", minSectionY);
        chunk.put("Status", "full");
        chunk.put("LastUpdate", 0L);
        chunk.put("InhabitedTime", 0L);
        chunk.put("sections", sections);
        chunk.put("block_entities", blockEntities);
        chunk.put("Heightmaps", new LinkedHashMap<String, Object>());
        chunk.put("isLightOn", (byte) 1);
        return new Result(FbrNbtIO.writeRoot(chunk), blockCount[0] > 0);
    }

    /** Returns the section list, or null if the bytes don't fit this layout exactly. */
    private static List<Object> parseSections(Session s, byte[] raw, int sectionCount, int minSectionY,
                                              boolean lengthPrefixedArrays,
                                              Map<Integer, byte[]> skyBySection, Map<Integer, byte[]> blockBySection,
                                              BitSet emptySky, BitSet emptyBlock, int[] blockCountOut) throws Exception {
        Reader in = new Reader(raw);
        List<Object> sections = new ArrayList<>(sectionCount);
        try {
            for (int si = 0; si < sectionCount && in.readable(); si++) {
                int sectionY = si + minSectionY;
                Map<String, Object> sec = new LinkedHashMap<>();
                sec.put("Y", sectionY);

                int nonAir = in.readShort();
                if (nonAir > 0) blockCountOut[0] += nonAir;

                Map<String, Object> blocks = readContainer(s, in, 4096, true, lengthPrefixedArrays);
                Map<String, Object> biomes = readContainer(s, in, 64, false, lengthPrefixedArrays);
                if (blocks == null || biomes == null) return null;
                sec.put("block_states", blocks);
                sec.put("biomes", biomes);

                int li = si + 1;
                byte[] skyArr = skyBySection.get(li);
                byte[] blockArr = blockBySection.get(li);
                if (skyArr != null) sec.put("SkyLight", skyArr);
                else if (!emptySky.get(li)) sec.put("SkyLight", new byte[2048]);
                if (blockArr != null) sec.put("BlockLight", blockArr);
                else if (!emptyBlock.get(li)) sec.put("BlockLight", new byte[2048]);

                sections.add(sec);
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        // The whole buffer must be used up exactly, otherwise this isn't the right layout.
        return in.pos == raw.length ? sections : null;
    }

    /**
     * Reads one paletted container from the network bytes and returns its save-file form
     * ({palette: [...], data: long[]}), or null if it doesn't fit the expected layout.
     */
    private static Map<String, Object> readContainer(Session s, Reader in, int entries, boolean blocks,
                                                     boolean lengthPrefixedArrays) throws Exception {
        int wireBits = in.readUnsignedByte();

        // How Minecraft reads each bit size (same table as its Strategy configs).
        int storageBits;
        int kind; // 0 single value, 1 local palette, 2 global ids
        if (wireBits == 0) {
            kind = 0;
            storageBits = 0;
        } else if (blocks && wireBits <= 4) {
            kind = 1;
            storageBits = 4;
        } else if (blocks && wireBits <= 8) {
            kind = 1;
            storageBits = wireBits;
        } else if (!blocks && wireBits <= 3) {
            kind = 1;
            storageBits = wireBits;
        } else {
            kind = 2;
            storageBits = wireBits;
        }
        if (storageBits > 31) return null;

        int[] paletteIds;
        if (kind == 0) {
            paletteIds = new int[]{in.readVarInt()};
        } else if (kind == 1) {
            int n = in.readVarInt();
            if (n <= 0 || n > 4096) return null;
            paletteIds = new int[n];
            for (int i = 0; i < n; i++) paletteIds[i] = in.readVarInt();
        } else {
            paletteIds = null;
        }

        int expectedLongs = storageBits == 0 ? 0 : (entries + (64 / storageBits) - 1) / (64 / storageBits);
        if (lengthPrefixedArrays) {
            int len = in.readVarInt();
            if (len != expectedLongs) return null;
        }
        long[] data = new long[expectedLongs];
        for (int i = 0; i < expectedLongs; i++) data[i] = in.readLong();

        Map<String, Object> out = new LinkedHashMap<>();

        if (kind == 0) {
            List<Object> palette = new ArrayList<>(1);
            palette.add(entryFor(s, paletteIds[0], blocks));
            out.put("palette", palette);
            return out;
        }

        int[] values = unpack(data, storageBits, entries);

        int[] localIds;     // palette (as network ids) for the saved container
        int[] localValues;  // per-entry index into localIds
        if (kind == 1) {
            for (int v : values) if (v >= paletteIds.length) return null;
            localIds = paletteIds;
            localValues = values;
        } else {
            // Global ids: build a local palette in order of first appearance.
            Map<Integer, Integer> indexOf = new HashMap<>();
            List<Integer> ids = new ArrayList<>();
            localValues = new int[entries];
            for (int i = 0; i < entries; i++) {
                Integer idx = indexOf.get(values[i]);
                if (idx == null) {
                    idx = ids.size();
                    indexOf.put(values[i], idx);
                    ids.add(values[i]);
                }
                localValues[i] = idx;
            }
            localIds = ids.stream().mapToInt(Integer::intValue).toArray();
        }

        List<Object> palette = new ArrayList<>(localIds.length);
        for (int id : localIds) palette.add(entryFor(s, id, blocks));
        out.put("palette", palette);

        int bitsOut = FbrNbtIO.diskBits(localIds.length, blocks);
        if (bitsOut > 0) {
            long[] packed = (kind == 1 && bitsOut == storageBits) ? data : pack(localValues, bitsOut);
            out.put("data", packed);
        }
        return out;
    }

    static int[] unpack(long[] data, int bits, int entries) {
        int[] out = new int[entries];
        if (bits == 0) return out;
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int i = 0; i < entries; i++) {
            out[i] = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
        }
        return out;
    }

    static long[] pack(int[] values, int bits) {
        int perLong = 64 / bits;
        long[] out = new long[(values.length + perLong - 1) / perLong];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < values.length; i++) {
            out[i / perLong] |= (values[i] & mask) << ((i % perLong) * bits);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------
    // Palette entries: worked out once per id through Minecraft's own codec, then cached.
    // ------------------------------------------------------------------------------------

    private static Object entryFor(Session s, int id, boolean blocks) {
        ConcurrentHashMap<Integer, Object> cache = blocks ? s.blockEntries : s.biomeEntries;
        Object cached = cache.get(id);
        if (cached != null) return cached;
        Object entry = resolveEntry(s, id, blocks);
        cache.putIfAbsent(id, entry);
        return entry;
    }

    @SuppressWarnings("unchecked")
    private static Object resolveEntry(Session s, int id, boolean blocks) {
        try {
            // A one-entry container in network form: [bits = 0][id][0 = empty data].
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8);
            baos.write(0);
            writeVarInt(baos, id);
            baos.write(0);
            var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(baos.toByteArray()), s.registryAccess);

            Tag encoded;
            if (blocks) {
                PalettedContainer<BlockState> states = new PalettedContainer<>(
                        Blocks.AIR.defaultBlockState(),
                        Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
                states.read(buf);
                encoded = s.blockCodec.encodeStart(s.ops, states).result().orElse(null);
            } else {
                var biomeRegistry = s.registryAccess.lookupOrThrow(Registries.BIOME);
                PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                        biomeRegistry.getOrThrow(Biomes.PLAINS),
                        Strategy.createForBiomes(biomeRegistry.asHolderIdMap()));
                biomes.read(buf);
                encoded = s.biomeCodec.encodeStart(s.ops, biomes).result().orElse(null);
            }
            if (encoded == null) throw new IllegalStateException("codec gave no result for id " + id);

            CompoundTag holder = new CompoundTag();
            holder.put("v", encoded);
            Map<String, Object> parsed = FbrNbtIO.readRoot(nbtBytes(holder));
            Map<String, Object> container = (Map<String, Object>) parsed.get("v");
            List<Object> palette = (List<Object>) container.get("palette");
            if (palette == null || palette.size() != 1) throw new IllegalStateException("unexpected palette for id " + id);
            return palette.get(0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] nbtBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            NbtIo.write(tag, (DataOutput) dos);
        }
        return baos.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** Minimal big-endian reader over the chunk bytes. Throws IndexOutOfBounds past the end. */
    private static final class Reader {
        final byte[] b;
        int pos;

        Reader(byte[] b) { this.b = b; }

        boolean readable() { return this.pos < this.b.length; }

        int readUnsignedByte() {
            if (this.pos >= this.b.length) throw new IndexOutOfBoundsException();
            return this.b[this.pos++] & 0xFF;
        }

        int readShort() {
            int hi = readUnsignedByte(), lo = readUnsignedByte();
            return (short) ((hi << 8) | lo);
        }

        long readLong() {
            if (this.pos + 8 > this.b.length) throw new IndexOutOfBoundsException();
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (this.b[this.pos++] & 0xFFL);
            return v;
        }

        int readVarInt() {
            int value = 0;
            for (int i = 0; i < 5; i++) {
                int x = readUnsignedByte();
                value |= (x & 0x7F) << (7 * i);
                if ((x & 0x80) == 0) return value;
            }
            throw new IndexOutOfBoundsException("VarInt too long");
        }
    }
}
