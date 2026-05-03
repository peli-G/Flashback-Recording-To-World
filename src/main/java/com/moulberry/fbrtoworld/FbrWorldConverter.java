package com.moulberry.fbrtoworld;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.GameProtocols;
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
import java.util.zip.DeflaterOutputStream;

public class FbrWorldConverter {

    private static final int DATA_VERSION = 4189; // 1.21.11 — verify with F3 if needed
    private static final String VERSION_NAME = "1.21.11";

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

    private static void convert(Path fbrPath, RegistryAccess registryAccess, Minecraft mc) throws Exception {
        if (!Files.exists(fbrPath)) {
            sendMessage(mc, "§c[FbrToWorld] File not found: " + fbrPath);
            return;
        }

        sendMessage(mc, "§a[FbrToWorld] Opening " + fbrPath.getFileName() + " ...");

        try (FileSystem zip = FileSystems.newFileSystem(fbrPath)) {

            // ── Find all level_chunk_cache files ──────────────────────────
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

            sendMessage(mc, "§a[FbrToWorld] Found " + cacheFiles.size() + " cache file(s). Decoding...");

            // ── Build the game packet codec — same pattern as Flashback 1.21.11 ──
            var gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(registryAccess))
                    .codec();

            // ── Step 1: Load all chunk packets into a flat index → packet list ──
            // Flashback stores chunks in a flat indexed array across all dimensions.
            // Each index references a unique chunk packet regardless of dimension.
            List<ClientboundLevelChunkWithLightPacket> chunkPacketsByIndex = new ArrayList<>();
            int totalCacheFiles = cacheFiles.size();
            for (int cfi = 0; cfi < totalCacheFiles; cfi++) {
                Path cacheFile = cacheFiles.get(cfi);
                setActionBar(mc, "Loading chunks", cfi + 1, totalCacheFiles);
                try (InputStream is = Files.newInputStream(cacheFile)) {
                    while (true) {
                        byte[] sizeBytes = is.readNBytes(4);
                        if (sizeBytes.length < 4) break;
                        int size = ((sizeBytes[0] & 0xff) << 24) | ((sizeBytes[1] & 0xff) << 16)
                                 | ((sizeBytes[2] & 0xff) << 8) | (sizeBytes[3] & 0xff);
                        byte[] data = is.readNBytes(size);
                        if (data.length < size) break;
                        var buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data), registryAccess);
                        try {
                            var pkt = gamePacketCodec.decode(buf);
                            if (pkt instanceof ClientboundLevelChunkWithLightPacket cp) {
                                chunkPacketsByIndex.add(cp);
                            }
                        } catch (Exception e) {
                            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to decode chunk: {}", e.getMessage());
                            chunkPacketsByIndex.add(null); // keep index alignment
                        }
                    }
                }
            }

            // ── Step 2: Scan .flashback action files to map chunk index → dimension ──
            // Login and Respawn packets in ActionGamePacket entries tell us the current
            // dimension. ActionLevelChunkCached entries tell us which chunk index was sent.
            // We track the last-known dimension for each chunk index (last write wins).
            Map<String, Map<Long, ClientboundLevelChunkWithLightPacket>> dimChunkMap = new LinkedHashMap<>();
            String[] currentDimension = {"minecraft:overworld"}; // default

            // Find all .flashback chunk files sorted by name
            List<Path> flashbackFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(zip.getPath("/"))) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (name.endsWith(".flashback")) flashbackFiles.add(p);
                }
            }
            flashbackFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

            final int MAGIC = 0xD780E884;
            int totalFbFiles = flashbackFiles.size();
            for (int fbi = 0; fbi < totalFbFiles; fbi++) {
                Path fbFile = flashbackFiles.get(fbi);
                setActionBar(mc, "Scanning timeline", fbi + 1, totalFbFiles);
                byte[] fbBytes = Files.readAllBytes(fbFile);
                io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.wrappedBuffer(fbBytes);
                net.minecraft.network.FriendlyByteBuf fbuf = new net.minecraft.network.FriendlyByteBuf(raw);

                int magic = fbuf.readInt();
                if (magic != MAGIC) {
                    FbrToWorldMod.LOGGER.warn("[FbrToWorld] Bad magic in {}", fbFile.getFileName());
                    continue;
                }

                // Read action name table
                int actionCount = fbuf.readVarInt();
                List<String> actionNames = new ArrayList<>();
                for (int i = 0; i < actionCount; i++) {
                    actionNames.add(fbuf.readUtf(32767)); // identifier as string
                }

                // Identify the action IDs we care about
                int gamePacketActionId = -1, levelChunkCachedActionId = -1;
                for (int i = 0; i < actionNames.size(); i++) {
                    String name = actionNames.get(i);
                    if (name.equals("flashback:action/game_packet")) gamePacketActionId = i;
                    if (name.equals("flashback:action/level_chunk_cached")) levelChunkCachedActionId = i;
                }

                // Read snapshot — must scan it for Login packet and initial chunk indices,
                // but treat it as a separate bounded section to avoid byte misalignment.
                int snapshotSize = fbuf.readInt();
                byte[] snapshotBytes = new byte[snapshotSize];
                fbuf.readBytes(snapshotBytes);
                net.minecraft.network.FriendlyByteBuf snapBuf = new net.minecraft.network.FriendlyByteBuf(
                        io.netty.buffer.Unpooled.wrappedBuffer(snapshotBytes));

                // Scan snapshot actions
                while (snapBuf.readableBytes() > 0) {
                    int actionId = snapBuf.readVarInt();
                    int dataSize = snapBuf.readInt();
                    byte[] actionData = new byte[dataSize];
                    snapBuf.readBytes(actionData);

                    if (actionId == gamePacketActionId) {
                        var actionBuf = new RegistryFriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(actionData), registryAccess);
                        try {
                            var pkt = gamePacketCodec.decode(actionBuf);
                            String dimStr = null;
                            if (pkt instanceof ClientboundLoginPacket lp) {
                                dimStr = lp.commonPlayerSpawnInfo().dimension().toString();
                            } else if (pkt instanceof ClientboundRespawnPacket rp) {
                                dimStr = rp.commonPlayerSpawnInfo().dimension().toString();
                            }
                            if (dimStr != null) {
                                int slash = dimStr.indexOf('/');
                                currentDimension[0] = slash >= 0
                                        ? dimStr.substring(slash + 2, dimStr.length() - 1).trim()
                                        : "minecraft:overworld";
                            }
                        } catch (Exception ignored) {}
                    } else if (actionId == levelChunkCachedActionId) {
                        net.minecraft.network.FriendlyByteBuf indexBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(actionData));
                        int chunkIndex = indexBuf.readVarInt();
                        if (chunkIndex >= 0 && chunkIndex < chunkPacketsByIndex.size()) {
                            ClientboundLevelChunkWithLightPacket cp = chunkPacketsByIndex.get(chunkIndex);
                            if (cp != null) {
                                dimChunkMap.computeIfAbsent(currentDimension[0], k -> new LinkedHashMap<>())
                                        .put(ChunkPos.asLong(cp.getX(), cp.getZ()), cp);
                            }
                        }
                    }
                }

                // Now scan main actions
                while (fbuf.readableBytes() > 0) {
                    int actionId = fbuf.readVarInt();
                    int dataSize = fbuf.readInt();

                    if (actionId == gamePacketActionId) {
                        // Try to decode this packet and check if it's Login or Respawn
                        byte[] actionData = new byte[dataSize];
                        fbuf.readBytes(actionData);
                        var actionBuf = new RegistryFriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(actionData), registryAccess);
                        try {
                            var pkt = gamePacketCodec.decode(actionBuf);
                            // ResourceKey.toString() = "ResourceKey[minecraft:dimension / minecraft:overworld]"
                            // .location() doesn't exist in 1.21.11 — parse toString() instead
                            String dimStr = null;
                            if (pkt instanceof ClientboundLoginPacket lp) {
                                dimStr = lp.commonPlayerSpawnInfo().dimension().toString();
                            } else if (pkt instanceof ClientboundRespawnPacket rp) {
                                dimStr = rp.commonPlayerSpawnInfo().dimension().toString();
                            }
                            if (dimStr != null) {
                                int slash = dimStr.indexOf('/');
                                currentDimension[0] = slash >= 0
                                        ? dimStr.substring(slash + 2, dimStr.length() - 1).trim()
                                        : "minecraft:overworld";
                            }
                        } catch (Exception ignored) {}

                    } else if (actionId == levelChunkCachedActionId) {
                        byte[] actionData = new byte[dataSize];
                        fbuf.readBytes(actionData);
                        net.minecraft.network.FriendlyByteBuf indexBuf = new net.minecraft.network.FriendlyByteBuf(
                                io.netty.buffer.Unpooled.wrappedBuffer(actionData));
                        int chunkIndex = indexBuf.readVarInt();

                        if (chunkIndex >= 0 && chunkIndex < chunkPacketsByIndex.size()) {
                            ClientboundLevelChunkWithLightPacket cp = chunkPacketsByIndex.get(chunkIndex);
                            if (cp != null) {
                                String dim = currentDimension[0];
                                dimChunkMap.computeIfAbsent(dim, k -> new LinkedHashMap<>())
                                        .put(ChunkPos.asLong(cp.getX(), cp.getZ()), cp);
                            }
                        }
                    } else {
                        fbuf.skipBytes(dataSize);
                    }
                }
            }

            int totalChunks = dimChunkMap.values().stream().mapToInt(Map::size).sum();
            sendMessage(mc, "§a[FbrToWorld] " + chunkPacketsByIndex.size() + " packets decoded → "
                    + totalChunks + " unique chunk positions across " + dimChunkMap.size() + " dimension(s).");

            if (dimChunkMap.isEmpty()) {
                sendMessage(mc, "§c[FbrToWorld] No chunks found — nothing to export.");
                return;
            }

            // ── Create output world folder ────────────────────────────────
            String baseName = fbrPath.getFileName().toString()
                    .replaceAll("\\.[^.]+$", "")       // strip extension
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

            // ── Codec setup ───────────────────────────────────────────────
            var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
            var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);

            var blockCodec = PalettedContainer.codecRW(
                    BlockState.CODEC,
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                    Blocks.AIR.defaultBlockState());

            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                    biomeRegistry.holderByNameCodec(),
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap()),
                    biomeRegistry.getOrThrow(Biomes.PLAINS));

            // ── Write chunks per dimension ────────────────────────────────
            int written = 0, skipped = 0;

            for (Map.Entry<String, Map<Long, ClientboundLevelChunkWithLightPacket>> dimEntry : dimChunkMap.entrySet()) {
                String dimId = dimEntry.getKey();   // e.g. "minecraft:overworld"
                Map<Long, ClientboundLevelChunkWithLightPacket> chunkMap = dimEntry.getValue();

                // Resolve the correct subfolder for each dimension
                Path dimPath = worldPath;
                if (dimId.equals("minecraft:the_nether")) {
                    dimPath = worldPath.resolve("DIM-1");
                } else if (dimId.equals("minecraft:the_end")) {
                    dimPath = worldPath.resolve("DIM1");
                } else if (!dimId.equals("minecraft:overworld")) {
                    // Custom dimensions: saves/<world>/dimensions/<namespace>/<path>
                    String[] parts = dimId.split(":", 2);
                    dimPath = worldPath.resolve("dimensions").resolve(parts[0]).resolve(parts[1]);
                }

                Files.createDirectories(dimPath.resolve("region"));
                Files.createDirectories(dimPath.resolve("entities"));

                Map<Long, RegionFileWriter> regionWriters = new HashMap<>();
                Map<Long, RegionFileWriter> entityWriters = new HashMap<>();

                int dimTotal = chunkMap.size();
                int dimWritten = 0;
                for (ClientboundLevelChunkWithLightPacket packet : chunkMap.values()) {
                    ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
                    long regionKey = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());
                    String regionName = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";

                    // Update action bar every 16 chunks to avoid hammering the main thread
                    if (dimWritten % 16 == 0) {
                        String shortDim = dimId.contains(":") ? dimId.split(":")[1] : dimId;
                        setActionBar(mc, "Writing " + shortDim, written + dimWritten, totalChunks);
                    }

                    try {
                        CompoundTag chunkNbt = buildChunkNbt(packet, ops, blockCodec, biomeCodec, registryAccess);
                        CompoundTag entityNbt = buildEntityChunkNbt(pos);

                        final Path dPath = dimPath;
                        regionWriters.computeIfAbsent(regionKey, k -> {
                            try { return new RegionFileWriter(dPath.resolve("region").resolve(regionName)); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        }).write(pos, chunkNbt);

                        entityWriters.computeIfAbsent(regionKey, k -> {
                            try { return new RegionFileWriter(dPath.resolve("entities").resolve(regionName)); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        }).write(pos, entityNbt);

                        written++;
                        dimWritten++;
                    } catch (Exception e) {
                        FbrToWorldMod.LOGGER.warn("[FbrToWorld] Skipped {}/{}: {}", dimId, pos, e.getMessage());
                        skipped++;
                        dimWritten++;
                    }
                }

                for (RegionFileWriter w : regionWriters.values()) w.close();
                for (RegionFileWriter w : entityWriters.values()) w.close();
            }

            clearActionBar(mc);
            sendMessage(mc, "§a[FbrToWorld] Done! " + written + " chunks written"
                    + (skipped > 0 ? " (" + skipped + " skipped)" : "")
                    + " across " + dimChunkMap.size() + " dimension(s)"
                    + " → saves/" + worldPath.getFileName());
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

        // chunkData.buffer and .blockEntitiesData are private — access via widener
        // (declared in fbr-to-world.accesswidener)
        byte[] rawBuffer = ((ClientboundLevelChunkPacketDataAccessor) (Object) chunkData).fbr$getBuffer();
        List<?> beInfoList =
                ((ClientboundLevelChunkPacketDataAccessor) (Object) chunkData).fbr$getBlockEntitiesData();

        var rfbb = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(rawBuffer), registryAccess);

        // Overworld: 24 sections from y=-64 (sectionY=-4) to y=319 (sectionY=19)
        int minSectionY = -4;
        int sectionCount = 24;
        ListTag sections = new ListTag();

        // Get default biome holder for PalettedContainer construction
        var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeRegistry.getOrThrow(Biomes.PLAINS);

        // ── Extract light data from the packet ───────────────────────────────
        // Light sections cover (minSectionY-1) to (minSectionY+sectionCount), i.e. 26 entries
        // for the overworld. Section si corresponds to light index si+1.
        var lightData = packet.getLightData();
        java.util.BitSet skyMask   = lightData.getSkyYMask();
        java.util.BitSet blockMask = lightData.getBlockYMask();
        java.util.BitSet emptySky   = lightData.getEmptySkyYMask();
        java.util.BitSet emptyBlock = lightData.getEmptyBlockYMask();
        java.util.List<byte[]> skyArrays   = lightData.getSkyUpdates();
        java.util.List<byte[]> blockArrays = lightData.getBlockUpdates();

        // Build index-to-array maps from the packed bit arrays
        // (only set bits in skyMask/blockMask have an array entry)
        java.util.Map<Integer, byte[]> skyBySection   = new java.util.HashMap<>();
        java.util.Map<Integer, byte[]> blockBySection = new java.util.HashMap<>();
        int skyIdx = 0, blockIdx = 0;
        int totalLightSections = sectionCount + 2; // one below, one above
        for (int li = 0; li < totalLightSections; li++) {
            if (skyMask.get(li)   && skyIdx   < skyArrays.size())   skyBySection.put(li,   skyArrays.get(skyIdx++));
            if (blockMask.get(li) && blockIdx < blockArrays.size()) blockBySection.put(li, blockArrays.get(blockIdx++));
        }

        for (int si = 0; si < sectionCount && rfbb.isReadable(); si++) {
            int sectionY = si + minSectionY;
            CompoundTag sec = new CompoundTag();
            sec.putInt("Y", sectionY);

            rfbb.readShort(); // non-empty block count — discard

            // Read block states directly from binary packet format
            PalettedContainer<BlockState> states = new PalettedContainer<BlockState>(
                    Blocks.AIR.defaultBlockState(),
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            states.read(rfbb);

            // Read biomes directly from binary packet format
            PalettedContainer<Holder<Biome>> biomes = new PalettedContainer<Holder<Biome>>(
                    defaultBiome,
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap()));
            biomes.read(rfbb);

            // Encode to NBT for the world save file
            blockCodec.encodeStart(ops, states).result()
                    .ifPresent(tag -> sec.put("block_states", tag));
            biomeCodec.encodeStart(ops, biomes).result()
                    .ifPresent(tag -> sec.put("biomes", tag));

            // Write light arrays — light index is si+1 (offset by one extra section below)
            int li = si + 1;
            byte[] skyArr   = skyBySection.get(li);
            byte[] blockArr = blockBySection.get(li);
            if (skyArr != null)   sec.putByteArray("SkyLight",   skyArr);
            else if (!emptySky.get(li))   sec.putByteArray("SkyLight",   new byte[2048]); // all dark
            if (blockArr != null) sec.putByteArray("BlockLight", blockArr);
            else if (!emptyBlock.get(li)) sec.putByteArray("BlockLight", new byte[2048]); // all dark

            sections.add(sec);
        }

        ListTag blockEntities = new ListTag();
        for (var beInfoRaw : beInfoList) {
            try {
                Class<?> beInfoClass = beInfoRaw.getClass();
                java.lang.reflect.Field fPackedXZ = beInfoClass.getDeclaredField("packedXZ");
                java.lang.reflect.Field fY         = beInfoClass.getDeclaredField("y");
                java.lang.reflect.Field fType      = beInfoClass.getDeclaredField("type");
                java.lang.reflect.Field fTag       = beInfoClass.getDeclaredField("tag");
                fPackedXZ.setAccessible(true); fY.setAccessible(true);
                fType.setAccessible(true);     fTag.setAccessible(true);

                int packedXZ = (int) fPackedXZ.get(beInfoRaw);
                int beY      = (int) fY.get(beInfoRaw);
                net.minecraft.world.level.block.entity.BlockEntityType<?> beType =
                        (net.minecraft.world.level.block.entity.BlockEntityType<?>) fType.get(beInfoRaw);
                net.minecraft.nbt.CompoundTag rawTag =
                        (net.minecraft.nbt.CompoundTag) fTag.get(beInfoRaw);

                int worldX = cx * 16 + (packedXZ >> 4);
                int worldZ = cz * 16 + (packedXZ & 0xF);
                CompoundTag beTag = rawTag != null ? rawTag.copy() : new CompoundTag();
                beTag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(beType).toString());
                beTag.putInt("x", worldX);
                beTag.putInt("y", beY);
                beTag.putInt("z", worldZ);
                beTag.putByte("keepPacked", (byte) 0);
                blockEntities.add(beTag);
            } catch (Exception ignored) {}
        }

        CompoundTag chunk = new CompoundTag();
        chunk.putInt("DataVersion", DATA_VERSION);
        chunk.putInt("xPos", cx);
        chunk.putInt("zPos", cz);
        chunk.putInt("yPos", minSectionY);
        chunk.putString("Status", "full");
        chunk.putLong("LastUpdate", 0L);
        chunk.putLong("InhabitedTime", 0L);
        chunk.put("sections", sections);
        chunk.put("block_entities", blockEntities);
        chunk.put("Heightmaps", new CompoundTag());
        chunk.putByte("isLightOn", (byte) 1);
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
        data.putInt("DataVersion", DATA_VERSION);
        data.putString("LevelName", worldName);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putInt("version", 19133);
        data.putInt("GameType", 1);
        data.putInt("SpawnX", 0); data.putInt("SpawnY", 64); data.putInt("SpawnZ", 0);
        data.putByte("Difficulty", (byte) 1);
        data.putByte("initialized", (byte) 1);
        data.putByte("hardcore", (byte) 0);
        data.putByte("allowCommands", (byte) 1);

        CompoundTag version = new CompoundTag();
        version.putString("Name", VERSION_NAME);
        version.putInt("Id", DATA_VERSION);
        version.putString("Series", "main");
        version.putByte("Snapshot", (byte) 0);
        data.put("Version", version);

        CompoundTag gameRules = new CompoundTag();
        gameRules.putString("doDaylightCycle", "false");
        gameRules.putString("doWeatherCycle", "false");
        gameRules.putString("doMobSpawning", "false");
        gameRules.putString("doFireTick", "false");
        data.put("GameRules", gameRules);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag worldGenSettings = new CompoundTag();
        worldGenSettings.putLong("seed", 0L);
        worldGenSettings.putByte("generate_features", (byte) 0);
        worldGenSettings.putByte("bonus_chest", (byte) 0);
        CompoundTag dimensions = new CompoundTag();
        CompoundTag overworld = new CompoundTag();
        overworld.putString("type", "minecraft:overworld");
        CompoundTag gen = new CompoundTag();
        gen.putString("type", "minecraft:flat");
        CompoundTag genSettings = new CompoundTag();
        genSettings.put("layers", new ListTag());
        genSettings.putString("biome", "minecraft:plains");
        genSettings.putByte("structure", (byte) 0);
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

    // ── Chat ──────────────────────────────────────────────────────────────────

    private static void sendMessage(Minecraft mc, String message) {
        FbrToWorldMod.LOGGER.info(message);
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.getChat().addMessage(Component.literal(message));
            }
        });
    }

    /** Show a progress bar in the action bar (above hotbar). */
    private static void setActionBar(Minecraft mc, String stage, int done, int total) {
        int clampedDone = Math.min(done, total);
        int pct = total > 0 ? (clampedDone * 100 / total) : 0;
        int filled = pct / 5;  // 0-20
        String bar = "█".repeat(filled) + "░".repeat(20 - filled);
        String msg = "§a[FbrToWorld] §f" + stage + " §7[§a" + bar + "§7] §f" + pct + "% §7(" + clampedDone + "/" + total + ")";
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.setOverlayMessage(Component.literal(msg), false);
            }
        });
    }

    /** Clear the action bar. */
    private static void clearActionBar(Minecraft mc) {
        mc.execute(() -> {
            if (mc.gui != null) {
                mc.gui.setOverlayMessage(Component.literal(""), false);
            }
        });
    }
}
