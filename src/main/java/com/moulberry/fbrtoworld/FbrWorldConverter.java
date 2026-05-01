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

            // ── Decode every chunk packet ─────────────────────────────────
            // We keep only the LAST packet per ChunkPos (later entries overwrite earlier ones,
            // matching Minecraft's own behaviour).
            Map<Long, ClientboundLevelChunkWithLightPacket> chunkMap = new LinkedHashMap<>();
            int totalPackets = 0;

            for (Path cacheFile : cacheFiles) {
                try (InputStream is = Files.newInputStream(cacheFile)) {
                    while (true) {
                        byte[] sizeBytes = is.readNBytes(4);
                        if (sizeBytes.length < 4) break;

                        int size = ((sizeBytes[0] & 0xff) << 24)
                                 | ((sizeBytes[1] & 0xff) << 16)
                                 | ((sizeBytes[2] & 0xff) <<  8)
                                 |  (sizeBytes[3] & 0xff);

                        byte[] data = is.readNBytes(size);
                        if (data.length < size) {
                            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Truncated entry in {}", cacheFile);
                            break;
                        }

                        var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registryAccess);
                        try {
                            var packet = gamePacketCodec.decode(buf);
                            if (packet instanceof ClientboundLevelChunkWithLightPacket cp) {
                                chunkMap.put(ChunkPos.asLong(cp.getX(), cp.getZ()), cp);
                                totalPackets++;
                            }
                        } catch (Exception e) {
                            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to decode chunk: {}", e.getMessage());
                        }
                    }
                }
            }

            sendMessage(mc, "§a[FbrToWorld] " + totalPackets + " packets decoded → "
                    + chunkMap.size() + " unique chunk positions.");

            if (chunkMap.isEmpty()) {
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
            Files.createDirectories(worldPath.resolve("region"));
            Files.createDirectories(worldPath.resolve("entities"));

            writeLevelDat(worldPath, baseName);

            // ── Codec setup ───────────────────────────────────────────────
            var biomeRegistry = registryAccess.lookupOrThrow(Registries.BIOME);
            var ops = registryAccess.createSerializationContext(NbtOps.INSTANCE);

            var blockCodec = PalettedContainer.codecRW(
                    BlockState.CODEC,
                    Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY),
                    Blocks.AIR.defaultBlockState());

            // codecRO returns Codec<PalettedContainerRO<Holder<Biome>>> in 1.21.1+
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                    biomeRegistry.holderByNameCodec(),
                    Strategy.createForBiomes(biomeRegistry.asHolderIdMap()),
                    biomeRegistry.getOrThrow(Biomes.PLAINS));

            // ── Write all chunks ──────────────────────────────────────────
            Map<Long, RegionFileWriter> regionWriters = new HashMap<>();
            Map<Long, RegionFileWriter> entityWriters = new HashMap<>();
            int written = 0, skipped = 0;

            for (ClientboundLevelChunkWithLightPacket packet : chunkMap.values()) {
                ChunkPos pos = new ChunkPos(packet.getX(), packet.getZ());
                long regionKey = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());
                String regionName = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";

                try {
                    CompoundTag chunkNbt = buildChunkNbt(packet, ops, blockCodec, biomeCodec, registryAccess);
                    CompoundTag entityNbt = buildEntityChunkNbt(pos);

                    final Path wPath = worldPath;
                    regionWriters.computeIfAbsent(regionKey, k -> {
                        try { return new RegionFileWriter(wPath.resolve("region").resolve(regionName)); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).write(pos, chunkNbt);

                    entityWriters.computeIfAbsent(regionKey, k -> {
                        try { return new RegionFileWriter(wPath.resolve("entities").resolve(regionName)); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    }).write(pos, entityNbt);

                    written++;
                } catch (Exception e) {
                    FbrToWorldMod.LOGGER.warn("[FbrToWorld] Skipped {}: {}", pos, e.getMessage());
                    skipped++;
                }
            }

            for (RegionFileWriter w : regionWriters.values()) w.close();
            for (RegionFileWriter w : entityWriters.values()) w.close();

            sendMessage(mc, "§a[FbrToWorld] Done! " + written + " chunks written"
                    + (skipped > 0 ? " (" + skipped + " skipped)" : "")
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
}
