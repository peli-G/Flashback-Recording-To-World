package com.moulberry.fbrtoworld;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    public static volatile boolean cancelRequested = false;
    private static volatile Thread currentThread = null;

    public static volatile String progressPhase = "";
    public static volatile int progressCurrent = 0;
    public static volatile int progressTotal = 0;
    public static volatile String progressWorldName = "";
    public static volatile boolean progressDone = false;
    public static volatile String progressResultMessage = "";
    public static volatile boolean progressFailed = false;

    public static volatile int progressRecording = 0;
    public static volatile int progressRecordingCount = 0;
    public static volatile String progressRecordingName = "";

    public static volatile int progressThreads = 1;

    public static volatile Path progressWorldPath = null;
    public static volatile Path progressStagingPath = null;

    public static void cancel() {
        FbrToWorldMod.LOGGER.info("[FbrToWorld] Cancel requested by user");
        cancelRequested = true;
        Thread t = currentThread;
        if (t != null) t.interrupt();
    }

    public static boolean start(Path fbrPath, RegistryAccess registryAccess, Minecraft mc) {
        return start(Collections.singletonList(fbrPath), registryAccess, mc);
    }

    public static boolean start(List<Path> fbrPaths, RegistryAccess registryAccess, Minecraft mc) {
        return start(fbrPaths, registryAccess, mc, null);
    }

    public static boolean startImport(List<Path> fbrPaths, RegistryAccess registryAccess, Minecraft mc, Path targetWorld) {
        return start(fbrPaths, registryAccess, mc, targetWorld);
    }

    private static boolean start(List<Path> fbrPaths, RegistryAccess registryAccess, Minecraft mc, Path existingWorldTarget) {
        final boolean importMode = existingWorldTarget != null;
        FbrToWorldMod.LOGGER.info("[FbrToWorld] start() called — {} recording(s)={}, alreadyRunning={}, importMode={}",
                fbrPaths.size(), fbrPaths, running, importMode);
        if (running) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] start() aborted — a conversion is already running");
            return false;
        }
        if (fbrPaths.isEmpty()) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] start() aborted — no recordings given");
            return false;
        }

        final List<Path> inputs = new ArrayList<>(fbrPaths);

        running = true;
        cancelRequested = false;
        progressPhase = "Starting...";
        progressCurrent = 0;
        progressTotal = 0;
        progressWorldName = "";
        progressWorldPath = null;
        progressStagingPath = null;
        progressDone = false;
        progressResultMessage = "";
        progressFailed = false;
        progressRecording = 0;
        progressRecordingCount = inputs.size();
        progressRecordingName = "";
        progressThreads = 1;
        Thread t = new Thread(() -> {
            try {
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Conversion thread started for {}", inputs);
                convert(inputs, registryAccess, mc, existingWorldTarget);
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Conversion thread completed normally for {}", inputs);
            } catch (CancellationException e) {
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Conversion cancelled by user");
                cleanupAfterCancel(mc, importMode);
                sendMessage(mc, "§e[FbrToWorld] Conversion cancelled.");
                progressResultMessage = "Cancelled.";
                progressDone = true;
            } catch (Exception e) {
                if (cancelRequested) {
                    FbrToWorldMod.LOGGER.info("[FbrToWorld] Conversion stopped due to cancellation");
                    cleanupAfterCancel(mc, importMode);
                    sendMessage(mc, "§e[FbrToWorld] Conversion cancelled.");
                    progressResultMessage = "Cancelled.";
                    progressDone = true;
                } else {
                    FbrToWorldMod.LOGGER.error("[FbrToWorld] Conversion failed", e);
                    clearActionBar(mc);
                    writeLevelDatIfMissing(mc, importMode);
                    sendMessage(mc, "§c[FbrToWorld] Failed: " + e.getMessage() + " — see log.");
                    progressFailed = true;
                    progressResultMessage = "Failed: " + e.getMessage();
                    progressDone = true;
                }
            } finally {
                running = false;
                currentThread = null;
            }
        }, "fbr-to-world");
        t.setDaemon(true);
        currentThread = t;
        t.start();
        FbrToWorldMod.LOGGER.info("[FbrToWorld] Conversion thread launched, start() returning true");
        return true;
    }

    /** After a failure the new world's level.dat may not exist yet - write it so the partial world can still be opened. */
    private static void writeLevelDatIfMissing(Minecraft mc, boolean importMode) {
        Path world = progressWorldPath;
        if (importMode || world == null || Files.exists(world.resolve("level.dat"))) return;
        try {
            writeLevelDat(world, world.getFileName().toString());
            FbrStartSpawn.apply(world, mc);
        } catch (Exception ex) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't write level.dat for the partial world: {}", ex.getMessage());
        }
    }

    private static void cleanupAfterCancel(Minecraft mc, boolean importMode) {
        clearActionBar(mc);
        if (importMode) {
            if (progressStagingPath != null) {
                deleteRecursivelyQuiet(progressStagingPath);
                progressStagingPath = null;
            }
        } else if (progressWorldPath != null) {
            deleteRecursivelyQuiet(progressWorldPath);
            sendMessage(mc, "§e[FbrToWorld] Deleted the incomplete world.");
            progressWorldPath = null;
        }
    }

    private static void deleteRecursivelyQuiet(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (IOException e) { FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to delete {}: {}", p, e.getMessage()); }
            });
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to clean up {}: {}", root, e.getMessage());
        }
    }

    private static void mergeRegionDir(Path stagedDir, Path realDir) throws IOException {
        if (!Files.isDirectory(stagedDir)) return;
        Files.createDirectories(realDir);
        List<Path> stagedFiles = new ArrayList<>();
        try (var files = Files.list(stagedDir)) {
            for (Path staged : (Iterable<Path>) files::iterator) {
                if (staged.getFileName().toString().endsWith(".mca")) stagedFiles.add(staged);
            }
        }
        // Each region file is independent, so they're merged in parallel (Faster Convert).
        ExecutorService pool = newWorkerPool(workerThreadCount());
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (Path staged : stagedFiles) {
                Path real = realDir.resolve(staged.getFileName().toString());
                jobs.add(pool.submit(() -> {
                    RegionFileMerger.mergeInto(staged, real);
                    return null;
                }));
            }
            for (Future<?> job : jobs) {
                checkCancelled();
                try {
                    job.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Conversion cancelled");
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException io) throw io;
                    throw new IOException(cause);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static final int BATCH_SIZE = 256;

    /** Leave one core for the game itself; cap so a huge CPU doesn't eat all the RAM with in-flight chunks. */
    private static int workerThreadCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(8, cores - 1));
    }

    private static ExecutorService newWorkerPool(int threads) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "fbr-to-world-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
    }

    private record PendingChunk(int index, String dim, byte[] data) {}

    /** Result of decoding/building/compressing one chunk on a worker thread. */
    private record EncodedChunk(ChunkPos pos, boolean hasBlocks, byte[] chunkEntry, byte[] entityEntry,
                                String decodeError, String encodeError) {}

    /** Write-phase state; only touched from the conversion thread. */
    private static final class WriteContext {
        final Map<String, Path> dimPaths;
        final Map<String, Map<Long, RegionFileWriter>> regionWriters;
        final Map<String, Map<Long, RegionFileWriter>> entityWriters;
        final Map<String, Map<Long, Boolean>> placed;
        final int[] writtenPerRecording;
        final int[] skippedPerRecording;
        int written, skipped, keptTerrain;

        WriteContext(int recordingCount, Map<String, Path> dimPaths,
                     Map<String, Map<Long, RegionFileWriter>> regionWriters,
                     Map<String, Map<Long, RegionFileWriter>> entityWriters,
                     Map<String, Map<Long, Boolean>> placed) {
            this.dimPaths = dimPaths;
            this.regionWriters = regionWriters;
            this.entityWriters = entityWriters;
            this.placed = placed;
            this.writtenPerRecording = new int[recordingCount];
            this.skippedPerRecording = new int[recordingCount];
        }
    }

    private static EncodedChunk encodeChunk(
            PendingChunk pc,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
            RegistryAccess registryAccess,
            com.mojang.serialization.DynamicOps<Tag> ops,
            com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec,
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec) {
        ChunkPos pos = null;
        boolean hasBlocks = false;
        try {
            var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(pc.data()), registryAccess);
            var pkt = codec.decode(buf);
            if (!(pkt instanceof ClientboundLevelChunkWithLightPacket cp)) {
                return new EncodedChunk(null, false, null, null, null, null); // not a chunk packet
            }
            pos = new ChunkPos(cp.getX(), cp.getZ());
            int[] blockCount = new int[1];
            CompoundTag chunkNbt = buildChunkNbt(cp, ops, blockCodec, biomeCodec, registryAccess, pc.dim(), blockCount);
            hasBlocks = blockCount[0] > 0;
            byte[] chunkEntry = RegionFileWriter.encode(pos, chunkNbt);
            byte[] entityEntry = RegionFileWriter.encode(pos, buildEntityChunkNbt(pos));
            return new EncodedChunk(pos, hasBlocks, chunkEntry, entityEntry, null, null);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            return pos == null
                    ? new EncodedChunk(null, false, null, null, msg, null)
                    : new EncodedChunk(pos, hasBlocks, null, null, null, msg);
        }
    }

    private static void flushBatch(
            List<PendingChunk> batch, int r, ExecutorService pool, WriteContext ctx,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
            RegistryAccess registryAccess,
            com.mojang.serialization.DynamicOps<Tag> ops,
            com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec,
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec) throws Exception {
        if (batch.isEmpty()) return;

        List<Future<EncodedChunk>> futures = new ArrayList<>(batch.size());
        for (PendingChunk pc : batch) {
            futures.add(pool.submit(() -> encodeChunk(pc, codec, registryAccess, ops, blockCodec, biomeCodec)));
        }

        // Apply results strictly in recording order (same rules as before).
        for (int i = 0; i < batch.size(); i++) {
            PendingChunk pc = batch.get(i);
            EncodedChunk res;
            try {
                res = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Conversion cancelled");
            } catch (ExecutionException e) {
                res = new EncodedChunk(null, false, null, null, String.valueOf(e.getCause()), null);
            }

            String dim = pc.dim();
            if (res.decodeError() != null) {
                FbrToWorldMod.LOGGER.warn("[FbrToWorld] Skipped {}/idx {} (recording {}): {}",
                        dim, pc.index(), r + 1, res.decodeError());
                ctx.skipped++;
                ctx.skippedPerRecording[r]++;
                continue;
            }
            if (res.pos() == null) continue; // not a chunk packet

            ChunkPos pos = res.pos();
            Map<Long, Boolean> placedInDim = ctx.placed.computeIfAbsent(dim, k -> new HashMap<>());
            long posKey = ChunkPos.asLong(pos.x, pos.z);
            Boolean alreadyPlaced = placedInDim.get(posKey);

            if (alreadyPlaced != null && !res.hasBlocks()) {
                ctx.keptTerrain++;
                continue;
            }

            try {
                if (res.encodeError() != null) throw new IOException(res.encodeError());
                long regionKey = ChunkPos.asLong(pos.getRegionX(), pos.getRegionZ());
                String regionName = "r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca";
                final Path dPath = ctx.dimPaths.get(dim);
                ctx.regionWriters.computeIfAbsent(dim, k -> new HashMap<>())
                        .computeIfAbsent(regionKey, k -> {
                            try { return new RegionFileWriter(dPath.resolve("region").resolve(regionName)); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        }).writeEntry(pos, res.chunkEntry());
                ctx.entityWriters.computeIfAbsent(dim, k -> new HashMap<>())
                        .computeIfAbsent(regionKey, k -> {
                            try { return new RegionFileWriter(dPath.resolve("entities").resolve(regionName)); }
                            catch (Exception e) { throw new RuntimeException(e); }
                        }).writeEntry(pos, res.entityEntry());

                placedInDim.put(posKey, res.hasBlocks() || Boolean.TRUE.equals(alreadyPlaced));
                if (alreadyPlaced == null) ctx.written++;
                ctx.writtenPerRecording[r]++;
            } catch (Exception e) {
                FbrToWorldMod.LOGGER.warn("[FbrToWorld] Skipped {}/idx {} (recording {}): {}",
                        dim, pc.index(), r + 1, e.getMessage());
                ctx.skipped++;
                ctx.skippedPerRecording[r]++;
            }
        }
    }

    private static void checkCancelled() {
        if (cancelRequested || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Conversion cancelled");
        }
    }

    private static void convert(List<Path> fbrPaths, RegistryAccess registryAccess, Minecraft mc, Path existingWorldTarget) throws Exception {

        List<Path> inputs = new ArrayList<>();
        for (Path p : fbrPaths) {
            if (p == null) continue;
            if (inputs.contains(p)) continue;
            if (!Files.exists(p)) {
                FbrToWorldMod.LOGGER.error("[FbrToWorld] File not found: {}", p);
                sendMessage(mc, "§c[FbrToWorld] File not found (skipping): " + p.getFileName());
                continue;
            }
            inputs.add(p);
        }

        if (inputs.isEmpty()) {
            sendMessage(mc, "§c[FbrToWorld] No usable recordings — nothing to convert.");
            progressFailed = true;
            progressResultMessage = "No usable recordings.";
            progressDone = true;
            return;
        }

        final int recordingCount = inputs.size();
        progressRecordingCount = recordingCount;

        if (recordingCount == 1) {
            sendMessage(mc, "§a[FbrToWorld] Opening " + inputs.get(0).getFileName() + " ...");
        } else {
            sendMessage(mc, "§a[FbrToWorld] Combining " + recordingCount + " recordings into one world...");
            for (int i = 0; i < recordingCount; i++) {
                sendMessage(mc, "§7[FbrToWorld]   " + (i + 1) + ". " + inputs.get(i).getFileName());
            }
            sendMessage(mc, "§7[FbrToWorld] Overlapping chunks: later recordings override earlier ones.");
        }

        var gamePacketCodec = GameProtocols.CLIENTBOUND_TEMPLATE
                .bind(RegistryFriendlyByteBuf.decorator(registryAccess))
                .codec();

        sendMessage(mc, "§a[FbrToWorld] Phase 1/3 & 2/3: Indexing chunks and scanning timelines...");

        List<Map<String, Map<Long, Integer>>> scans = new ArrayList<>();
        for (int r = 0; r < recordingCount; r++) {
            checkCancelled();
            progressRecording = r + 1;
            progressRecordingName = inputs.get(r).getFileName().toString();
            scans.add(FbrStartSpawn.scanRecording(inputs.get(r), r, recordingCount,
                    gamePacketCodec, registryAccess, mc));
        }

        Map<String, Set<Long>> merged = new LinkedHashMap<>();
        int scannedTotal = 0;
        for (int r = 0; r < recordingCount; r++) {
            for (var dimEntry : scans.get(r).entrySet()) {
                Set<Long> target = merged.computeIfAbsent(dimEntry.getKey(), k -> new LinkedHashSet<>());
                scannedTotal += dimEntry.getValue().size();
                target.addAll(dimEntry.getValue().keySet());
            }
        }

        int totalChunks = 0;
        for (var m : merged.values()) totalChunks += m.size();
        int overlapping = scannedTotal - totalChunks;

        sendMessage(mc, "§a[FbrToWorld] Scan done: " + totalChunks + " unique chunks across "
                + merged.size() + " dimension(s)"
                + (recordingCount > 1
                    ? " from " + recordingCount + " recordings (" + overlapping + " overlapping chunk(s) merged)"
                    : ""));

        if (merged.isEmpty()) {
            sendMessage(mc, "§c[FbrToWorld] No chunks found — nothing to export.");
            progressFailed = true;
            progressResultMessage = "No chunks found.";
            progressDone = true;
            return;
        }

        List<Map<Integer, String>> candidatesPerRecording = new ArrayList<>();
        for (int r = 0; r < recordingCount; r++) {
            Map<Integer, String> byIndex = new HashMap<>();
            for (var dimEntry : scans.get(r).entrySet()) {
                for (int chunkIndex : dimEntry.getValue().values()) {
                    byIndex.put(chunkIndex, dimEntry.getKey());
                }
            }
            candidatesPerRecording.add(byIndex);
        }

        if (recordingCount > 1) {
            for (int r = 0; r < recordingCount; r++) {
                Map<String, Map<Long, Integer>> scan = scans.get(r);
                int scannedHere = 0;
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
                int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                StringBuilder dims = new StringBuilder();
                for (var dimEntry : scan.entrySet()) {
                    scannedHere += dimEntry.getValue().size();
                    if (dims.length() > 0) dims.append(", ");
                    dims.append(dimEntry.getKey()).append(" x").append(dimEntry.getValue().size());
                    for (long posKey : dimEntry.getValue().keySet()) {
                        int cx = ChunkPos.getX(posKey);
                        int cz = ChunkPos.getZ(posKey);
                        if (cx < minX) minX = cx;
                        if (cx > maxX) maxX = cx;
                        if (cz < minZ) minZ = cz;
                        if (cz > maxZ) maxZ = cz;
                    }
                }
                int kept = candidatesPerRecording.get(r).size();
                String area = scannedHere == 0 ? "no chunks"
                        : "blocks X " + (minX * 16) + ".." + (maxX * 16 + 15)
                          + ", Z " + (minZ * 16) + ".." + (maxZ * 16 + 15);
                sendMessage(mc, "§7[FbrToWorld]   " + (r + 1) + ". " + inputs.get(r).getFileName()
                        + " → " + scannedHere + " chunks (" + kept + " candidate positions)");
                sendMessage(mc, "§8[FbrToWorld]      " + area
                        + " | dims: " + (dims.length() == 0 ? "none" : dims));
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Recording {} ({}): scanned={} kept={} area={} dims={}",
                        r + 1, inputs.get(r).getFileName(), scannedHere, kept, area, dims);
            }
        }

        boolean importMode = existingWorldTarget != null;
        Path worldPath;
        Path stagingRoot = null;

        if (importMode) {
            worldPath = existingWorldTarget;
            if (!Files.isDirectory(worldPath)) {
                sendMessage(mc, "§c[FbrToWorld] Target world not found: " + worldPath);
                progressFailed = true;
                progressResultMessage = "Target world not found.";
                progressDone = true;
                return;
            }
            stagingRoot = Files.createTempDirectory("fbr-import-");
            progressStagingPath = stagingRoot;
            progressWorldName = worldPath.getFileName().toString();
            sendMessage(mc, "§a[FbrToWorld] Importing into existing world: " + worldPath.getFileName());
        } else {
            String baseName = inputs.get(0).getFileName().toString()
                    .replaceAll("\\.[^.]+$", "")
                    .replaceAll("[\\\\/:*?\"<>|]", "_");
            if (recordingCount > 1) {
                baseName = baseName + " +" + (recordingCount - 1);
            }
            Path savesDir = mc.gameDirectory.toPath().resolve("saves");
            worldPath = savesDir.resolve(baseName);
            if (Files.exists(worldPath)) {
                int i = 1;
                while (Files.exists(savesDir.resolve(baseName + "_" + i))) i++;
                worldPath = savesDir.resolve(baseName + "_" + i);
            }
            Files.createDirectories(worldPath);
            progressWorldPath = worldPath;
            progressWorldName = worldPath.getFileName().toString();
            // level.dat is written once all chunks are in (see below), so the half-made world
            // doesn't show up in Singleplayer while it's still converting.
        }

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

        Path writeRoot = importMode ? stagingRoot : worldPath;

        Map<String, Path> dimPaths = new LinkedHashMap<>();
        for (String dimId : merged.keySet()) {
            Path dp = resolveDimPath(writeRoot, dimId);
            Files.createDirectories(dp.resolve("region"));
            Files.createDirectories(dp.resolve("entities"));
            dimPaths.put(dimId, dp);
        }

        sendMessage(mc, "§a[FbrToWorld] Phase 3/3: Writing chunks...");

        Map<String, Map<Long, RegionFileWriter>> regionWriters = new HashMap<>();
        Map<String, Map<Long, RegionFileWriter>> entityWriters = new HashMap<>();
        int written = 0, skipped = 0, keptTerrain = 0;
        int[] writtenPerRecording = new int[recordingCount];
        int[] skippedPerRecording = new int[recordingCount];
        int processed = 0;

        Map<String, Map<Long, Boolean>> placed = new HashMap<>();

        // Faster Convert: decoding, building and compressing chunks runs on several threads;
        // the results are then written to the region files in the original order, so overlap
        // rules (later recordings win, air-only copies don't erase terrain) are unchanged.
        int threads = workerThreadCount();
        progressThreads = threads;
        ExecutorService pool = newWorkerPool(threads);
        WriteContext ctx = new WriteContext(recordingCount, dimPaths, regionWriters, entityWriters, placed);
        sendMessage(mc, "§7[FbrToWorld] Fast convert: using " + threads + " CPU thread(s).");

        try {
            for (int r = 0; r < recordingCount; r++) {
                checkCancelled();
                Map<Integer, String> winnerIndexToDim = candidatesPerRecording.get(r);
                if (winnerIndexToDim.isEmpty()) continue;

                progressRecording = r + 1;
                progressRecordingName = inputs.get(r).getFileName().toString();
                String tag = recordingCount > 1 ? " [" + (r + 1) + "/" + recordingCount + "]" : "";
                List<PendingChunk> batch = new ArrayList<>(BATCH_SIZE);

                try (FileSystem zip = FileSystems.newFileSystem(inputs.get(r))) {
                    List<Path> cacheFiles = listCacheFiles(zip);
                    int globalIndex = 0;

                    for (Path cacheFile : cacheFiles) {
                        try (InputStream is = new BufferedInputStream(Files.newInputStream(cacheFile), 1 << 16)) {
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

                                if (processed % 16 == 0) {
                                    checkCancelled();
                                    String shortDim = dim.contains(":") ? dim.split(":")[1] : dim;
                                    setActionBar(mc, "Writing " + shortDim + tag, processed, scannedTotal);
                                }
                                processed++;

                                byte[] data = is.readNBytes(size);
                                if (data.length < size) break;

                                batch.add(new PendingChunk(idx, dim, data));
                                if (batch.size() >= BATCH_SIZE) {
                                    flushBatch(batch, r, pool, ctx, gamePacketCodec, registryAccess, ops, blockCodec, biomeCodec);
                                    batch.clear();
                                }
                            }
                        }
                    }
                }
                flushBatch(batch, r, pool, ctx, gamePacketCodec, registryAccess, ops, blockCodec, biomeCodec);
                batch.clear();
            }
        } finally {
            pool.shutdownNow();
            for (var m : regionWriters.values()) for (var w : m.values()) closeQuietly(w);
            for (var m : entityWriters.values()) for (var w : m.values()) closeQuietly(w);
        }

        written = ctx.written;
        skipped = ctx.skipped;
        keptTerrain = ctx.keptTerrain;
        writtenPerRecording = ctx.writtenPerRecording;
        skippedPerRecording = ctx.skippedPerRecording;

        clearActionBar(mc);

        if (importMode) {
            sendMessage(mc, "§a[FbrToWorld] Merging chunks into " + worldPath.getFileName() + "...");
            for (String dimId : merged.keySet()) {
                Path stagedDim = resolveDimPath(stagingRoot, dimId);
                Path realDim = resolveDimPath(worldPath, dimId);
                Files.createDirectories(realDim.resolve("region"));
                Files.createDirectories(realDim.resolve("entities"));
                mergeRegionDir(stagedDim.resolve("region"), realDim.resolve("region"));
                mergeRegionDir(stagedDim.resolve("entities"), realDim.resolve("entities"));
            }
            deleteRecursivelyQuiet(stagingRoot);
            progressStagingPath = null;
        }

        if (recordingCount > 1) {
            for (int r = 0; r < recordingCount; r++) {
                String line = "  " + (r + 1) + ". " + inputs.get(r).getFileName()
                        + " → wrote " + writtenPerRecording[r] + " chunks"
                        + (skippedPerRecording[r] > 0 ? " (" + skippedPerRecording[r] + " failed)" : "");
                sendMessage(mc, (writtenPerRecording[r] == 0 ? "§c" : "§7") + "[FbrToWorld]" + line);
                FbrToWorldMod.LOGGER.info("[FbrToWorld]{}", line);
            }
        }

        if (keptTerrain > 0) {
            sendMessage(mc, "§7[FbrToWorld] " + keptTerrain
                    + " air-only copies skipped so existing terrain wasn't erased.");
            FbrToWorldMod.LOGGER.info("[FbrToWorld] {} air-only chunk copies skipped", keptTerrain);
        }

        if (!importMode) {
            FbrStartSpawn.writeLevelDat(worldPath, worldPath.getFileName().toString());
        }

        String resultMsg = written + " chunks written"
                + (skipped > 0 ? " (" + skipped + " skipped)" : "")
                + " across " + merged.size() + " dimension(s)"
                + (recordingCount > 1 ? " from " + recordingCount + " recordings" : "")
                + " → saves/" + worldPath.getFileName();
        sendMessage(mc, "§a[FbrToWorld] Done! " + resultMsg);
        progressPhase = "Done";
        progressCurrent = written;
        progressTotal = totalChunks;
        progressResultMessage = resultMsg;
        progressDone = true;
    }

    private static int readVarIntBytes(byte[] data) {
        int value = 0;
        for (int i = 0, shift = 0; i < data.length && i < 5; i++, shift += 7) {
            value |= (data[i] & 0x7F) << shift;
            if ((data[i] & 0x80) == 0) break;
        }
        return value;
    }

    static Map<String, Map<Long, Integer>> scanRecording(
            Path fbrPath,
            int recordingIdx,
            int recordingCount,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> gamePacketCodec,
            RegistryAccess registryAccess,
            Minecraft mc) throws Exception {

        Map<String, Map<Long, Integer>> dimPosToWinnerIndex = new LinkedHashMap<>();
        String tag = recordingCount > 1 ? " [" + (recordingIdx + 1) + "/" + recordingCount + "]" : "";

        try (FileSystem zip = FileSystems.newFileSystem(fbrPath)) {

            Path cacheDir = zip.getPath("/level_chunk_caches");
            if (!Files.exists(cacheDir)) {
                sendMessage(mc, "§c[FbrToWorld] " + fbrPath.getFileName()
                        + ": no level_chunk_caches found — skipping (may be an older .fbr format).");
                return dimPosToWinnerIndex;
            }

            List<Path> cacheFiles = listCacheFiles(zip);
            int totalCacheFiles = cacheFiles.size();

            List<Path> flashbackFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(zip.getPath("/"))) {
                for (Path p : ds)
                    if (p.getFileName().toString().endsWith(".flashback")) flashbackFiles.add(p);
            }
            flashbackFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));
            int totalFbFiles = flashbackFiles.size();

            Map<Integer, int[]> indexToXZ = new HashMap<>();
            int totalPackets = 0;
            byte[] headBuf = new byte[16];

            for (int cfi = 0; cfi < totalCacheFiles; cfi++) {
                checkCancelled();
                setActionBar(mc, "Indexing cache" + tag, cfi + 1, totalCacheFiles);
                try (InputStream is = new BufferedInputStream(Files.newInputStream(cacheFiles.get(cfi)), 1 << 16)) {
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

            FbrToWorldMod.LOGGER.info("[FbrToWorld] {}: indexed {} of {} cache entries",
                    fbrPath.getFileName(), indexToXZ.size(), totalPackets);

            String[] currentDim = {"minecraft:overworld"};
            final int MAGIC = 0xD780E884;
            // Faster Convert: packet types already seen that aren't login/respawn are skipped
            // instead of fully decoded (a packet id always means the same packet type).
            boolean[] notDimensionPacket = new boolean[1024];

            for (int fbi = 0; fbi < totalFbFiles; fbi++) {
                checkCancelled();
                setActionBar(mc, "Scanning timeline" + tag, fbi + 1, totalFbFiles);
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

                int snapSize = fbuf.readInt();
                byte[] snapBytes = new byte[snapSize];
                fbuf.readBytes(snapBytes);
                FriendlyByteBuf snapBuf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(snapBytes));
                scanActions(snapBuf, gamePacketId, chunkCachedId,
                        gamePacketCodec, registryAccess, indexToXZ,
                        currentDim, dimPosToWinnerIndex, notDimensionPacket);

                scanActions(fbuf, gamePacketId, chunkCachedId,
                        gamePacketCodec, registryAccess, indexToXZ,
                        currentDim, dimPosToWinnerIndex, notDimensionPacket);
            }
        }

        return dimPosToWinnerIndex;
    }

    private static List<Path> listCacheFiles(FileSystem zip) throws IOException {
        List<Path> cacheFiles = new ArrayList<>();
        Path cacheDir = zip.getPath("/level_chunk_caches");
        if (!Files.exists(cacheDir)) return cacheFiles;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir)) {
            for (Path p : ds) cacheFiles.add(p);
        }
        cacheFiles.sort(Comparator.comparingInt(p -> cacheFileOrder(p)));
        return cacheFiles;
    }

    private static int cacheFileOrder(Path p) {
        String name = p.getFileName().toString();
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException e) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c >= '0' && c <= '9') digits.append(c);
            }
            if (digits.isEmpty()) return Integer.MAX_VALUE;
            try {
                return Integer.parseInt(digits.toString());
            } catch (NumberFormatException e2) {
                return Integer.MAX_VALUE;
            }
        }
    }

    private static void closeQuietly(RegionFileWriter writer) {
        try {
            writer.close();
        } catch (Exception e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to close region file: {}", e.getMessage());
        }
    }

    private static void scanActions(
            FriendlyByteBuf buf,
            int gamePacketId,
            int chunkCachedId,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
            RegistryAccess registryAccess,
            Map<Integer, int[]> indexToXZ,
            String[] currentDim,
            Map<String, Map<Long, Integer>> dimPosToWinnerIndex,
            boolean[] notDimensionPacket) {

        while (buf.readableBytes() > 0) {
            int actionId = buf.readVarInt();
            int dataSize = buf.readInt();

            if (actionId == gamePacketId) {
                byte[] actionData = new byte[dataSize];
                buf.readBytes(actionData);
                int packetId = readVarIntBytes(actionData);
                if (packetId >= 0 && packetId < notDimensionPacket.length && notDimensionPacket[packetId]) {
                    continue;
                }
                var actionBuf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(actionData), registryAccess);
                try {
                    var pkt = codec.decode(actionBuf);
                    String dimStr = null;
                    if (pkt instanceof ClientboundLoginPacket   lp) dimStr = lp.commonPlayerSpawnInfo().dimension().toString();
                    else if (pkt instanceof ClientboundRespawnPacket rp) dimStr = rp.commonPlayerSpawnInfo().dimension().toString();
                    else if (packetId >= 0 && packetId < notDimensionPacket.length) notDimensionPacket[packetId] = true;
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

    @SuppressWarnings("unchecked")
    private static CompoundTag buildChunkNbt(
            ClientboundLevelChunkWithLightPacket packet,
            com.mojang.serialization.DynamicOps<Tag> ops,
            com.mojang.serialization.Codec<PalettedContainer<BlockState>> blockCodec,
            com.mojang.serialization.Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
            RegistryAccess registryAccess,
            String dimension,
            int[] blockCountOut) throws Exception {

        int cx = packet.getX();
        int cz = packet.getZ();
        var chunkData = packet.getChunkData();

        byte[] rawBuffer = ((ClientboundLevelChunkPacketDataAccessor)(Object) chunkData).fbr$getBuffer();

        var rfbb = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(rawBuffer), registryAccess);

        int minSectionY;
        int sectionCount;
        if ("minecraft:the_nether".equals(dimension) || "minecraft:the_end".equals(dimension)) {
            minSectionY  = 0;
            sectionCount = 16;
        } else {
            minSectionY  = -4;
            sectionCount = 24;
        }
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

            int nonAirInSection = rfbb.readShort();
            if (nonAirInSection > 0) blockCountOut[0] += nonAirInSection;

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
        chunkData.getBlockEntitiesTagsConsumer(cx, cz).accept((blockPos, beType, nbt) -> {
            CompoundTag beTag = nbt != null ? nbt.copy() : new CompoundTag();
            beTag.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(beType).toString());
            beTag.putInt("x", blockPos.getX());
            beTag.putInt("y", blockPos.getY());
            beTag.putInt("z", blockPos.getZ());
            beTag.putByte("keepPacked", (byte) 0);
            blockEntities.add(beTag);
        });

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

    static void writeLevelDat(Path worldFolder, String worldName) throws IOException {
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
        for (var entry : com.moulberry.fbrtoworld.config.FbrGameRuleConfig.load().entrySet()) {
            String value = entry.getValue();
            if (value.equals("true") || value.equals("false")) {
                gameRules.putByte(entry.getKey(), (byte) (value.equals("true") ? 1 : 0));
            } else {
                try {
                    gameRules.putInt(entry.getKey(), Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    gameRules.putByte(entry.getKey(), (byte) 1);
                }
            }
        }
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

        CompoundTag nether = new CompoundTag();
        nether.putString("type", "minecraft:the_nether");
        CompoundTag netherGen = new CompoundTag();
        netherGen.putString("type", "minecraft:flat");
        CompoundTag netherSettings = new CompoundTag();
        netherSettings.put("layers", new ListTag());
        netherSettings.putString("biome", "minecraft:nether_wastes");
        netherSettings.putByte("features", (byte) 0);
        netherSettings.putByte("lakes", (byte) 0);
        netherGen.put("settings", netherSettings);
        nether.put("generator", netherGen);
        dimensions.put("minecraft:the_nether", nether);

        CompoundTag end = new CompoundTag();
        end.putString("type", "minecraft:the_end");
        CompoundTag endGen = new CompoundTag();
        endGen.putString("type", "minecraft:flat");
        CompoundTag endSettings = new CompoundTag();
        endSettings.put("layers", new ListTag());
        endSettings.putString("biome", "minecraft:the_end");
        endSettings.putByte("features", (byte) 0);
        endSettings.putByte("lakes", (byte) 0);
        endGen.put("settings", endSettings);
        end.put("generator", endGen);
        dimensions.put("minecraft:the_end", end);

        worldGenSettings.put("dimensions", dimensions);
        data.put("WorldGenSettings", worldGenSettings);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldFolder.resolve("level.dat"));
        Files.write(worldFolder.resolve("session.lock"),
                ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());
    }

    static void sendMessage(Minecraft mc, String message) {
        FbrToWorldMod.LOGGER.info(message);
        mc.execute(() -> {
            if (mc.gui != null) mc.gui.getChat().addMessage(Component.literal(message));
        });
    }

    private static void setActionBar(Minecraft mc, String stage, int done, int total) {
        int clamped = Math.min(done, total);
        progressPhase = stage;
        progressCurrent = clamped;
        progressTotal = total;

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
