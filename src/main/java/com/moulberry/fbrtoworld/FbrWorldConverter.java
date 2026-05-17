package com.moulberry.fbrtoworld;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.ChunkPos;
import com.moulberry.fbrtoworld.mixin.ClientboundLevelChunkPacketDataAccessor;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

public class FbrWorldConverter {

    private static final int DATA_VERSION = net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version();
    private static final String VERSION_NAME = net.minecraft.SharedConstants.getCurrentVersion().name();

    public static volatile boolean running = false;

    public static boolean start(Path fbrPath, RegistryAccess registryAccess, Minecraft mc) {
        if (running) return false;
        running = true;
        Thread t = new Thread(() -> {
            try {
                convert(fbrPath, registryAccess, mc);
            } catch (Exception e) {
                FbrToWorldMod.LOGGER.error("[FbrToWorld] Conversion failed", e);
                clearActionBar(mc);
                sendMessage(mc, "§c[FbrToWorld] Failed: " + e.getMessage() + " — see log.");
            } finally {
                running = false;
            }
        }, "fbr-to-world");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // ── Main conversion ───────────────────────────────────────────────────────

    private static void convert(Path fbrPath, RegistryAccess registryAccess, Minecraft mc) throws Exception {
        if (!Files.exists(fbrPath)) {
            sendMessage(mc, "§c[FbrToWorld] File not found: " + fbrPath);
            return;
        }
        sendMessage(mc, "§a[FbrToWorld] Opening " + fbrPath.getFileName() + " ...");

        try (FileSystem zip = FileSystems.newFileSystem(fbrPath)) {

            Path cacheDir = zip.getPath("/level_chunk_caches");
            if (!Files.exists(cacheDir)) {
                sendMessage(mc, "§c[FbrToWorld] No level_chunk_caches found — may be an older .fbr format.");
                return;
            }

            List<Path> cacheFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir)) {
                for (Path p : ds) cacheFiles.add(p);
            }
            cacheFiles.sort(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString())));
            int totalCacheFiles = cacheFiles.size();

            List<Path> flashbackFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(zip.getPath("/"))) {
                for (Path p : ds)
                    if (p.getFileName().toString().endsWith(".flashback")) flashbackFiles.add(p);
            }
            flashbackFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
            int totalFbFiles = flashbackFiles.size();

            var gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(registryAccess))
                    .codec();

            // ═══════════════════════════════════════════════════════════════
            // PHASE 1 — Lightweight position index
            // Read only the first ~16 bytes of each cache entry (VarInt ID +
            // x int + z int). Skip the rest without allocating a buffer.
            // Result: indexToXZ[globalIndex] = [x, z]
            // ═══════════════════════════════════════════════════════════════
            sendMessage(mc, "§a[FbrToWorld] Phase 1/3: Indexing chunk positions...");

            Map<Integer, int[]> indexToXZ = new HashMap<>();
            int totalPackets = 0;
            byte[] headBuf = new byte[16];

            for (int cfi = 0; cfi < totalCacheFiles; cfi++) {
                setActionBar(mc, "Indexing cache", cfi + 1, totalCacheFiles);
                try (InputStream is = Files.newInputStream(cacheFiles.get(cfi))) {
                    while (true) {
                        byte[] sb = is.readNBytes(4);
                        if (sb.length < 4) break;
                        int size = readInt4(sb, 0);
                        if (size <= 0) break;

                        int headLen = Math.min(size, 16);
                        int got = is.readNBytes(headBuf, 0, headLen);
                        if (got < headLen) break;

                        skipFully(is, size - headLen);

                        int vi = varIntLength(headBuf, got);
                        if (got >= vi + 8) {
                            indexToXZ.put(totalPackets, new int[]{
                                readInt4(headBuf, vi),
                                readInt4(headBuf, vi + 4)
                            });
                        }
                        totalPackets++;
                    }
                }
            }

            sendMessage(mc, "§a[FbrToWorld] Phase 1 done: " + indexToXZ.size()
                    + " of " + totalPackets + " entries indexed.");

            // ═══════════════════════════════════════════════════════════════
            // PHASE 2 — Timeline scan
            // Walk .flashback files, track dimension via Login/Respawn packets,
            // record which chunk index was last used per (dim, chunkPos).
            // Last write wins — matches the original single-pass behaviour.
            // ═══════════════════════════════════════════════════════════════
            sendMessage(mc, "§a[FbrToWorld] Phase 2/3: Scanning timeline...");

            Map<String, Map<Long, Integer>> dimPosToWinnerIndex = new LinkedHashMap<>();
            String[] currentDim = {"minecraft:overworld"};
            final int MAGIC = 0xD780E884;

            for (int fbi = 0; fbi < totalFbFiles; fbi++) {
                setActionBar(mc, "Scanning timeline", fbi + 1, totalFbFiles);
                byte[] fbBytes = Files.readAllBytes(flashbackFiles.get(fbi));
                FriendlyByteBuf fbuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(fbBytes));

                if (fbuf.readInt() != MAGIC) {
                    FbrToWorldMod.LOGGER.warn("[FbrToWorld] Bad magic in {}", flashbackFiles.get(fbi).getFileName());
                    continue;
                }

                int actionCount = fbuf.readVarInt();
                List<String> actionNames = new ArrayList<>();
                for (int i = 0; i < actionCount; i++) actionNames.add(fbuf.readUtf(32767));

                int gamePacketId = -1, chunkCachedId = -1;
                for (int i = 0; i < actionNames.size(); i++) {
                    if ("flashback:action/game_packet".equals(actionNames.get(i)))          gamePacketId  = i;
                    if ("flashback:action/level_chunk_cached".equals(actionNames.get(i)))   chunkCachedId = i;
                }

                // Snapshot section
                int snapSize = fbuf.readInt();
                byte[] snapBytes = new byte[snapSize];
                fbuf.readBytes(snapBytes);
                FriendlyByteBuf snapBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(snapBytes));
                scanActions(snapBuf, gamePacketId, chunkCachedId,
                        gamePacketCodec, registryAccess, indexToXZ, currentDim, dimPosToWinnerIndex);

                // Main section
                scanActions(fbuf, gamePacketId, chunkCachedId,
                        gamePacketCodec, registryAccess, indexToXZ, currentDim, dimPosToWinnerIndex);
            }

            // Build reverse map: winner cache index → dim
            Map<Integer, String> winnerIndexToDim = new HashMap<>();
            for (var e : dimPosToWinnerIndex.entrySet())
                for (int idx : e.getValue().values())
                    winnerIndexToDim.put(idx, e.getKey());

            int totalChunks = winnerIndexToDim.size();
            sendMessage(mc, "§a[FbrToWorld] Phase 2 done: " + totalChunks + " unique chunks across "
                    + dimPosToWinnerIndex.size() + " dimension(s).");

            if (dimPosToWinnerIndex.isEmpty()) {
                sendMessage(mc, "§c[FbrToWorld] No chunks found — nothing to export.");
                return;
            }

            // Create output world
            String baseName = fbrPath.getFileName().toString()
                    .replaceAll("\\.[^.]+$", "")
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            Path savesDir = mc.gameDirectory.toPath().resolve("saves");
            Path worldPath = savesDir.resolve(baseName);
            if (Files.exists(worldPath)) {
                int i = 1;
                while (Files.exists(savesDir.resolve(baseName + "_" + i))) i++;
                worldPath = savesDir.resolve(baseName + "_" + i);
            }
            Files.createDirectories(worldPath);
            writeLevelDat(worldPath, baseName);

            var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
            var ops            = registryAccess.createSerializationContext(NbtOps.INSTANCE);
            var blockCodec     = PalettedContainer.codecRW(
                    BlockState.CODEC,
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                    Blocks.AIR.defaultBlockState());
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                    biomeRegistry.holderByNameCodec(),
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap()),
                    biomeRegistry.getOrThrow(Biomes.PLAINS));

            Map<String, Path> dimPaths = new LinkedHashMap<>();
            for (String dimId : dimPosToWinnerIndex.keySet()) {
                Path dp = resolveDimPath(worldPath, dimId);
                Files.createDirectories(dp.resolve("region"));
                Files.createDirectories(dp.resolve("entities"));
                dimPaths.put(dimId, dp);
            }

            // ═══════════════════════════════════════════════════════════════
            // PHASE 3 — Streaming write
            // Walk cache files a second time. Non-winners: skipFully() with
            // zero allocation. Winners: readNBytes() → decode → write → discard.
            // Peak heap: one decoded chunk at a time, regardless of file size.
            // ═══════════════════════════════════════════════════════════════
            sendMessage(mc, "§a[FbrToWorld] Phase 3/3: Writing chunks...");

            Map<String, Map<Long, RegionFileWriter>> regionWriters = new HashMap<>();
            Map<String, Map<Long, RegionFileWriter>> entityWriters = new HashMap<>();
            int written = 0, skipped = 0;
            int globalIndex = 0;

            for (int cfi = 0; cfi < totalCacheFiles; cfi++) {
                try (InputStream is = Files.newInputStream(cacheFiles.get(cfi))) {
                    while (true) {
                        byte[] sb = is.readNBytes(4);
                        if (sb.length < 4) break;
                        int size = readInt4(sb, 0);
                        if (size <= 0) break;

                        int idx = globalIndex++;
                        String dim = winnerIndexToDim.get(idx);

                        if (dim == null) {
                            skipFully(is, size);
                            continue;
                        }

                        if (written % 16 == 0) {
                            String shortDim = dim.contains(":") ? dim.split(":")[1] : dim;
                            setActionBar(mc, "Writing " + shortDim, written, totalChunks);
                        }

                        byte[] data = is.readNBytes(size);
                        if (data.length < size) break;

                        var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registryAccess);
                        try {
                            var pkt = gamePacketCodec.decode(buf);
                            if (pkt instanceof ClientboundLevelChunkWithLightPacket cp) {
                                ChunkPos pos      = new ChunkPos(cp.getX(), cp.getZ());
                                long regionKey    = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());
                                String regionName = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
                                Path dimPath      = dimPaths.get(dim);

                                CompoundTag chunkNbt  = buildChunkNbt(cp, ops, blockCodec, biomeCodec, registryAccess);
                                CompoundTag entityNbt = buildEntityChunkNbt(pos);

                                final Path dPath  = dimPath;
                                final String fDim = dim;
                                regionWriters.computeIfAbsent(fDim, k -> new HashMap<>())
                                        .computeIfAbsent(regionKey, k -> {
                                            try { return new RegionFileWriter(dPath.resolve("region").resolve(regionName)); }
                                            catch (Exception e) { throw new RuntimeException(e); }
                                        }).write(pos, chunkNbt);
                                entityWriters.computeIfAbsent(fDim, k -> new HashMap<>())
                                        .computeIfAbsent(regionKey, k -> {
                                            try { return new RegionFileWriter(dPath.resolve("entities").resolve(regionName)); }
                                            catch (Exception e) { throw new RuntimeException(e); }
                                        }).write(pos, entityNbt);

                                written++;
                            }
                        } catch (Exception e) {
                            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Skipped {}/idx {}: {}", dim, idx, e.getMessage());
                            skipped++;
                        }
                    }
                }
            }

            for (var m : regionWriters.values()) for (var w : m.values()) w.close();
            for (var m : entityWriters.values()) for (var w : m.values()) w.close();

            clearActionBar(mc);
            sendMessage(mc, "§a[FbrToWorld] Done! " + written + " chunks written"
                    + (skipped > 0 ? " (" + skipped + " skipped)" : "")
                    + " across " + dimPosToWinnerIndex.size() + " dimension(s)"
                    + " → saves/" + worldPath.getFileName());
        }
    }

    // ── Timeline scanner ──────────────────────────────────────────────────────

    private static void scanActions(
            FriendlyByteBuf buf,
            int gamePacketId,
            int chunkCachedId,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
            RegistryAccess registryAccess,
            Map<Integer, int[]> indexToXZ,
            String[] currentDim,
            Map<String, Map<Long, Integer>> dimPosToWinnerIndex) {

        while (buf.readableBytes() > 0) {
            int actionId = buf.readVarInt();
            int dataSize = buf.readInt();

            if (actionId == gamePacketId) {
                byte[] actionData = new byte[dataSize];
                buf.readBytes(actionData);
                var actionBuf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(actionData), registryAccess);
                try {
                    var pkt = codec.decode(actionBuf);
                    String dimStr = null;
                    if (pkt instanceof ClientboundLoginPacket   lp) dimStr = lp.commonPlayerSpawnInfo().dimension().toString();
                    else if (pkt instanceof ClientboundRespawnPacket rp) dimStr = rp.commonPlayerSpawnInfo().dimension().toString();
                    if (dimStr != null) {
                        int slash = dimStr.indexOf('/');
                        currentDim[0] = slash >= 0
                                ? dimStr.substring(slash + 2, dimStr.length() - 1).trim()
                                : "minecraft:overworld";
                    }
                } catch (Exception ignored) {}

            } else if (actionId == chunkCachedId) {
                byte[] actionData = new byte[dataSize];
                buf.readBytes(actionData);
                FriendlyByteBuf indexBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(actionData));
                int chunkIndex = indexBuf.readVarInt();
                int[] xz = indexToXZ.get(chunkIndex);
                if (xz != null) {
                    dimPosToWinnerIndex
                            .computeIfAbsent(currentDim[0], k -> new LinkedHashMap<>())
                            .put(ChunkPos.asLong(xz[0], xz[1]), chunkIndex);
                }

            } else {
                buf.skipBytes(dataSize);
            }
        }
    }

    // ── Dimension path ────────────────────────────────────────────────────────

    private static Path resolveDimPath(Path worldPath, String dimId) {
        return switch (dimId) {
            case "minecraft:overworld"  -> worldPath;
            case "minecraft:the_nether" -> worldPath.resolve("DIM-1");
            case "minecraft:the_end"    -> worldPath.resolve("DIM1");
            default -> {
                String[] parts = dimId.split(":", 2);
                yield worldPath.resolve("dimensions").resolve(parts[0]).resolve(parts[1]);
            }
        };
    }

    // ── Byte utilities ────────────────────────────────────────────────────────

    private static int readInt4(byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16)
             | ((b[off + 2] & 0xff) << 8)  |  (b[off + 3] & 0xff);
    }

    private static int varIntLength(byte[] b, int validLen) {
        for (int i = 0; i < validLen && i < 5; i++) {
            if ((b[i] & 0x80) == 0) return i + 1;
        }
        return 1;
    }

    private static void skipFully(InputStream is, long n) throws IOException {
        while (n > 0) {
            long s = is.skip(n);
            if (s > 0) { n -= s; continue; }
            int chunk = (int) Math.min(n, 8192);
            byte[] discard = new byte[chunk];
            int r = is.read(discard, 0, chunk);
            if (r < 0) break;
            n -= r;
        }
    }

    // ── Chunk NBT ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static CompoundTag buildChunkNbt(
            ClientboundLevelChunkWithLightPacket packet,
            com.mojang.serialization.DynamicOps<Tag> ops,
            com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec,
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            RegistryAccess registryAccess) throws Exception {

        int cx = packet.getX();
        int cz = packet.getZ();
        var chunkData = packet.getChunkData();

        byte[] rawBuffer  = ((ClientboundLevelChunkPacketDataAccessor)(Object) chunkData).fbr$getBuffer();
        List<?> beInfoList = ((ClientboundLevelChunkPacketDataAccessor)(Object) chunkData).fbr$getBlockEntitiesData();

        var rfbb = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(rawBuffer), registryAccess);

        int minSectionY  = -4;
        int sectionCount = 24;
        ListTag sections = new ListTag();

        var biomeRegistry  = registryAccess.lookupOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);

        var lightData   = packet.getLightData();
        var skyMask     = lightData.getSkyYMask();
        var blockMask   = lightData.getBlockYMask();
        var emptySky    = lightData.getEmptySkyYMask();
        var emptyBlock  = lightData.getEmptyBlockYMask();
        var skyArrays   = lightData.getSkyUpdates();
        var blockArrays = lightData.getBlockUpdates();

        Map<Integer, byte[]> skyBySection   = new HashMap<>();
        Map<Integer, byte[]> blockBySection = new HashMap<>();
        int skyIdx = 0, blockIdx = 0;
        int totalLightSections = sectionCount + 2;
        for (int li = 0; li < totalLightSections; li++) {
            if (skyMask.get(li)   && skyIdx   < skyArrays.size())   skyBySection.put(li,   skyArrays.get(skyIdx++));
            if (blockMask.get(li) && blockIdx < blockArrays.size()) blockBySection.put(li, blockArrays.get(blockIdx++));
        }

        for (int si = 0; si < sectionCount && rfbb.isReadable(); si++) {
            int sectionY = si + minSectionY;
            CompoundTag sec = new CompoundTag();
            sec.putInt("Y", sectionY);

            rfbb.readShort();

            PalettedContainer<BlockState> states = new PalettedContainer<>(
                    Blocks.AIR.defaultBlockState(),
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            states.read(rfbb);

            PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<>(
                    defaultBiome,
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap()));
            biomes.read(rfbb);

            blockCodec.encodeStart(ops, states).result().ifPresent(t -> sec.put("block_states", t));
            biomeCodec.encodeStart(ops, biomes).result().ifPresent(t -> sec.put("biomes", t));

            int li = si + 1;
            byte[] skyArr   = skyBySection.get(li);
            byte[] blockArr = blockBySection.get(li);
            if (skyArr   != null) sec.putByteArray("SkyLight",   skyArr);
            else if (!emptySky.get(li))   sec.putByteArray("SkyLight",   new byte[2048]);
            if (blockArr != null) sec.putByteArray("BlockLight", blockArr);
            else if (!emptyBlock.get(li)) sec.putByteArray("BlockLight", new byte[2048]);

            sections.add(sec);
        }

        ListTag blockEntities = new ListTag();
        for (var beInfoRaw : beInfoList) {
            try {
                Class<?> c = beInfoRaw.getClass();
                var fPackedXZ = c.getDeclaredField("packedXZ"); fPackedXZ.setAccessible(true);
                var fY        = c.getDeclaredField("y");         fY.setAccessible(true);
                var fType     = c.getDeclaredField("type");      fType.setAccessible(true);
                var fTag      = c.getDeclaredField("tag");       fTag.setAccessible(true);

                int packedXZ = (int) fPackedXZ.get(beInfoRaw);
                int beY      = (int) fY.get(beInfoRaw);
                var beType   = (net.minecraft.world.level.block.entity.BlockEntityType<?>) fType.get(beInfoRaw);
                var rawTag   = (CompoundTag) fTag.get(beInfoRaw);

                int worldX = cx * 16 + (packedXZ >> 4);
                int worldZ = cz * 16 + (packedXZ & 0xF);
                CompoundTag beTag = rawTag != null ? rawTag.copy() : new CompoundTag();
                beTag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(beType).toString());
                beTag.putInt("x", worldX); beTag.putInt("y", beY); beTag.putInt("z", worldZ);
                beTag.putByte("keepPacked", (byte) 0);
                blockEntities.add(beTag);
            } catch (Exception ignored) {}
        }

        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion",    DATA_VERSION);
        chunk.putInt("xPos",           cx);
        chunk.putInt("zPos",           cz);
        chunk.putInt("yPos",           minSectionY);
        chunk.putString("Status",      "full");
        chunk.putLong("LastUpdate",    0L);
        chunk.putLong("InhabitedTime", 0L);
        chunk.put("sections",          sections);
        chunk.put("block_entities",    blockEntities);
        chunk.put("Heightmaps",        new CompoundTag());
        chunk.putByte("isLightOn",     (byte) 1);
        return chunk;
    }

    private static CompoundTag buildEntityChunkNbt(ChunkPos pos) {
        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", DATA_VERSION);
        chunk.putIntArray("Position", new int[]{pos.x, pos.z});
        chunk.put("Entities", new ListTag());
        return chunk;
    }

    // ── level.dat ─────────────────────────────────────────────────────────────

    private static void writeLevelDat(Path worldFolder, String worldName) throws IOException {
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion",  DATA_VERSION);
        data.putString("LevelName", worldName);
        data.putLong("LastPlayed",  System.currentTimeMillis());
        data.putInt("version",      19133);
        data.putInt("GameType",     1);
        data.putByte("Difficulty",  (byte) 2);
        data.putByte("DifficultyLocked", (byte) 0);
        data.putByte("initialized", (byte) 1);
        data.putByte("hardcore",    (byte) 0);
        data.putByte("allowCommands", (byte) 1);
        data.putByte("WasModded",   (byte) 1);

        data.putLong("Time", 0L);
        data.putLong("DayTime", 0L);
        data.putByte("raining",   (byte) 0);
        data.putByte("thundering", (byte) 0);
        data.putInt("rainTime",   0);
        data.putInt("thunderTime", 0);
        data.putInt("clearWeatherTime", 0);
        data.putInt("WanderingTraderSpawnChance", 25);
        data.putInt("WanderingTraderSpawnDelay",  24000);

        CompoundTag spawn = new CompoundTag();
        spawn.put("pos", new net.minecraft.nbt.IntArrayTag(new int[]{8, -64, 8}));
        spawn.putFloat("pitch", 0.0f);
        spawn.putFloat("yaw",   0.0f);
        spawn.putString("dimension", "minecraft:overworld");
        data.put("spawn", spawn);

        CompoundTag version = new CompoundTag();
        version.putString("Name",   VERSION_NAME);
        version.putInt("Id",        DATA_VERSION);
        version.putString("Series", "main");
        version.putByte("Snapshot", (byte) 0);
        data.put("Version", version);

        CompoundTag gameRules = new CompoundTag();
        gameRules.putByte("minecraft:advance_time", (byte) 0);
        gameRules.putByte("minecraft:advance_weather", (byte) 0);
        gameRules.putByte("minecraft:allow_entering_nether_using_portals", (byte) 1);
        gameRules.putByte("minecraft:block_drops", (byte) 1);
        gameRules.putByte("minecraft:block_explosion_drop_decay", (byte) 1);
        gameRules.putByte("minecraft:command_block_output", (byte) 1);
        gameRules.putByte("minecraft:command_blocks_work", (byte) 1);
        gameRules.putByte("minecraft:drowning_damage", (byte) 1);
        gameRules.putByte("minecraft:elytra_movement_check", (byte) 1);
        gameRules.putByte("minecraft:ender_pearls_vanish_on_death", (byte) 1);
        gameRules.putByte("minecraft:entity_drops", (byte) 1);
        gameRules.putByte("minecraft:fall_damage", (byte) 1);
        gameRules.putByte("minecraft:fire_damage", (byte) 1);
        gameRules.putInt("minecraft:fire_spread_radius_around_player", 128);
        gameRules.putByte("minecraft:forgive_dead_players", (byte) 1);
        gameRules.putByte("minecraft:freeze_damage", (byte) 1);
        gameRules.putByte("minecraft:global_sound_events", (byte) 1);
        gameRules.putByte("minecraft:immediate_respawn", (byte) 0);
        gameRules.putByte("minecraft:keep_inventory", (byte) 0);
        gameRules.putByte("minecraft:limited_crafting", (byte) 0);
        gameRules.putByte("minecraft:lava_source_conversion", (byte) 0);
        gameRules.putByte("minecraft:locator_bar", (byte) 1);
        gameRules.putByte("minecraft:log_admin_commands", (byte) 1);
        gameRules.putInt("minecraft:max_block_modifications", 32768);
        gameRules.putInt("minecraft:max_command_forks", 65536);
        gameRules.putInt("minecraft:max_command_sequence_length", 65536);
        gameRules.putInt("minecraft:max_entity_cramming", 24);
        gameRules.putInt("minecraft:max_snow_accumulation_height", 1);
        gameRules.putByte("minecraft:mob_drops", (byte) 1);
        gameRules.putByte("minecraft:mob_explosion_drop_decay", (byte) 1);
        gameRules.putByte("minecraft:mob_griefing", (byte) 1);
        gameRules.putByte("minecraft:natural_health_regeneration", (byte) 1);
        gameRules.putByte("minecraft:player_movement_check", (byte) 1);
        gameRules.putInt("minecraft:players_nether_portal_creative_delay", 0);
        gameRules.putInt("minecraft:players_nether_portal_default_delay", 80);
        gameRules.putInt("minecraft:players_sleeping_percentage", 100);
        gameRules.putByte("minecraft:projectiles_can_break_blocks", (byte) 1);
        gameRules.putByte("minecraft:pvp", (byte) 1);
        gameRules.putByte("minecraft:raids", (byte) 1);
        gameRules.putInt("minecraft:random_tick_speed", 3);
        gameRules.putByte("minecraft:reduced_debug_info", (byte) 0);
        gameRules.putInt("minecraft:respawn_radius", 10);
        gameRules.putByte("minecraft:send_command_feedback", (byte) 1);
        gameRules.putByte("minecraft:show_advancement_messages", (byte) 1);
        gameRules.putByte("minecraft:show_death_messages", (byte) 1);
        gameRules.putByte("minecraft:spawn_mobs", (byte) 0);
        gameRules.putByte("minecraft:spawn_monsters", (byte) 0);
        gameRules.putByte("minecraft:spawn_patrols", (byte) 0);
        gameRules.putByte("minecraft:spawn_phantoms", (byte) 0);
        gameRules.putByte("minecraft:spawn_wandering_traders", (byte) 0);
        gameRules.putByte("minecraft:spawn_wardens", (byte) 0);
        gameRules.putByte("minecraft:spawner_blocks_work", (byte) 1);
        gameRules.putByte("minecraft:spectators_generate_chunks", (byte) 1);
        gameRules.putByte("minecraft:spread_vines", (byte) 1);
        gameRules.putByte("minecraft:tnt_explodes", (byte) 1);
        gameRules.putByte("minecraft:tnt_explosion_drop_decay", (byte) 0);
        gameRules.putByte("minecraft:universal_anger", (byte) 0);
        gameRules.putByte("minecraft:water_source_conversion", (byte) 1);
        data.put("game_rules", gameRules);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag dragonFight = new CompoundTag();
        dragonFight.putByte("PreviouslyKilled",    (byte) 0);
        dragonFight.putByte("NeedsStateScanning",  (byte) 1);
        dragonFight.putByte("DragonKilled",        (byte) 0);
        data.put("DragonFight", dragonFight);

        data.put("CustomBossEvents", new CompoundTag());
        data.put("ScheduledEvents",  new ListTag());
        ListTag serverBrands = new ListTag();
        serverBrands.add(StringTag.valueOf("fabric"));
        data.put("ServerBrands", serverBrands);

        // Flat generator with no layers — unvisited areas generate nothing
        CompoundTag worldGenSettings = new CompoundTag();
        worldGenSettings.putLong("seed", 0L);
        worldGenSettings.putByte("generate_features", (byte) 0);
        worldGenSettings.putByte("bonus_chest",       (byte) 0);
        CompoundTag dimensions = new CompoundTag();
        CompoundTag overworld  = new CompoundTag();
        overworld.putString("type", "minecraft:overworld");
        CompoundTag gen = new CompoundTag();
        gen.putString("type", "minecraft:flat");
        CompoundTag genSettings = new CompoundTag();
        genSettings.put("layers",  new ListTag());
        genSettings.putString("biome", "minecraft:plains");
        genSettings.putByte("features", (byte) 0);
        genSettings.putByte("lakes",    (byte) 0);
        gen.put("settings", genSettings);
        overworld.put("generator", gen);
        dimensions.put("minecraft:overworld", overworld);
        worldGenSettings.put("dimensions", dimensions);
        data.put("WorldGenSettings", worldGenSettings);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldFolder.resolve("level.dat"));
        Files.write(worldFolder.resolve("session.lock"),
                ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
    }

    // ── Chat / HUD ────────────────────────────────────────────────────────────

    private static void sendMessage(Minecraft mc, String message) {
        FbrToWorldMod.LOGGER.info(message);
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal(message));
        });
    }

    private static void setActionBar(Minecraft mc, String stage, int done, int total) {
        int clamped = Math.min(done, total);
        int pct     = total > 0 ? (clamped * 100 / total) : 0;
        int filled  = pct / 5;
        String bar  = "█".repeat(filled) + "░".repeat(20 - filled);
        String msg  = "§a[FbrToWorld] §f" + stage + " §7[§a" + bar + "§7] §f" + pct
                    + "% §7(" + clamped + "/" + total + ")";
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal(msg), false);
        });
    }

    private static void clearActionBar(Minecraft mc) {
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.setOverlayMessage(Component.literal(""), false);
        });
    }
}
