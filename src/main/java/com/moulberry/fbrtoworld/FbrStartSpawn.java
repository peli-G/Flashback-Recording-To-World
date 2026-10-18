package com.moulberry.fbrtoworld;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Spawns the player where the Flashback recording started instead of at 0,0.
 *
 * Every Flashback recording starts its first chunk (c0.flashback) with a snapshot that holds
 * the login packet (current dimension) followed by a "flashback:action/create_local_player"
 * action (UUID, x, y, z, pitch, yaw, ...). We read that position from the first recording and
 * write it into the new world's level.dat as both the world spawn and the saved player
 * position, so you load in exactly where the recording began.
 *
 * Only used for newly created worlds (Convert / Combine). Import into an existing world
 * never touches that world's level.dat.
 */
public final class FbrStartSpawn {

    private static final int MAGIC = 0xD780E884;

    /** Where the first recording started. x, y, z, yaw, pitch + dimension id. */
    public record StartPos(double x, double y, double z, float yaw, float pitch, String dimension) {}

    /** Captured from the first recording during the scan; consumed when level.dat is written. */
    private static volatile StartPos pending = null;

    private static volatile Minecraft pendingMc = null;

    private FbrStartSpawn() {}

    // ------------------------------------------------------------------------------------
    // Drop-in wrappers: FbrWorldConverter.convert() calls these instead of its own
    // scanRecording(...) / writeLevelDat(...). They do the original work, plus the spawn fix.
    // ------------------------------------------------------------------------------------

    static Map<String, Map<Long, Integer>> scanRecording(
            Path fbrPath, int recordingIdx, int recordingCount,
            net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
            RegistryAccess registryAccess, Minecraft mc) throws Exception {
        if (recordingIdx == 0) {
            pendingMc = mc;
            capture(fbrPath, codec, registryAccess);
        }
        return FbrWorldConverter.scanRecording(fbrPath, recordingIdx, recordingCount, codec, registryAccess, mc);
    }

    static void writeLevelDat(Path worldFolder, String worldName) throws IOException {
        FbrWorldConverter.writeLevelDat(worldFolder, worldName);
        apply(worldFolder, pendingMc);
    }

    // ------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------

    /** Reads the start position of the first recording (resets any previous value). */
    public static void capture(Path fbrPath,
                               net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
                               RegistryAccess registryAccess) {
        pending = null;
        try {
            pending = read(fbrPath, codec, registryAccess);
            if (pending != null) {
                FbrToWorldMod.LOGGER.info("[FbrToWorld] Recording start: {} {} {} in {}",
                        pending.x(), pending.y(), pending.z(), pending.dimension());
            } else {
                FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't find the recording's start position in {}", fbrPath.getFileName());
            }
        } catch (Throwable t) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to read recording start position: {}", t.toString());
        }
    }

    /** Call right after the new world's level.dat has been written. */
    public static void apply(Path worldFolder, Minecraft mc) {
        StartPos start = pending;
        pending = null;
        if (start == null) return;
        if (!isVanillaDimension(start.dimension())) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Recording started in custom dimension {} - keeping default spawn", start.dimension());
            return;
        }
        try {
            patchLevelDat(worldFolder.resolve("level.dat"), start);
            String where = (int) Math.floor(start.x()) + ", " + (int) Math.floor(start.y()) + ", " + (int) Math.floor(start.z());
            String dimName = start.dimension().substring(start.dimension().indexOf(':') + 1);
            FbrToWorldMod.LOGGER.info("[FbrToWorld] Spawn set to recording start {} ({})", where, dimName);
            if (mc != null) {
                FbrWorldConverter.sendMessage(mc, "§a[FbrToWorld] Spawn set to where the recording started: §f"
                        + where + " §7(" + dimName + ")");
            }
        } catch (Throwable t) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't set spawn to the recording start: {}", t.toString());
        }
    }

    // ------------------------------------------------------------------------------------
    // Reading the start position from the recording
    // ------------------------------------------------------------------------------------

    public static StartPos read(Path fbrPath,
                                net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
                                RegistryAccess registryAccess) throws IOException {
        try (FileSystem zip = FileSystems.newFileSystem(fbrPath)) {
            List<Path> chunkFiles = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(zip.getPath("/"))) {
                for (Path p : ds) if (p.getFileName().toString().endsWith(".flashback")) chunkFiles.add(p);
            }
            chunkFiles.sort(Comparator.comparingLong(FbrStartSpawn::chunkNumber)
                    .thenComparing(p -> p.getFileName().toString()));

            // c0 should always have it; fall back to later chunks just in case.
            for (Path chunkFile : chunkFiles) {
                StartPos pos = readFromChunk(Files.readAllBytes(chunkFile), codec, registryAccess);
                if (pos != null) return pos;
            }
        }
        return null;
    }

    private static long chunkNumber(Path p) {
        String name = p.getFileName().toString();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.isEmpty()) return Long.MAX_VALUE;
        try { return Long.parseLong(digits.toString()); } catch (NumberFormatException e) { return Long.MAX_VALUE; }
    }

    private static StartPos readFromChunk(byte[] bytes,
                                          net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
                                          RegistryAccess registryAccess) {
        ByteBuffer buf = ByteBuffer.wrap(bytes); // big-endian, same as Netty
        if (buf.remaining() < 4 || buf.getInt() != MAGIC) return null;

        int actionCount = readVarInt(buf);
        int gamePacketId = -1, createLocalPlayerId = -1;
        for (int i = 0; i < actionCount; i++) {
            String name = readUtf(buf);
            if ("flashback:action/game_packet".equals(name)) gamePacketId = i;
            if ("flashback:action/create_local_player".equals(name)) createLocalPlayerId = i;
        }
        if (createLocalPlayerId < 0) return null;

        // Snapshot first, then the timeline - the snapshot holds the starting state.
        int snapSize = buf.getInt();
        int snapEnd = buf.position() + snapSize;
        String dim = "minecraft:overworld";

        for (int pass = 0; pass < 2; pass++) {
            int end = pass == 0 ? snapEnd : buf.limit();
            while (buf.position() < end) {
                int actionId = readVarInt(buf);
                int dataSize = buf.getInt();
                int dataStart = buf.position();

                if (actionId == gamePacketId && codec != null) {
                    String d = dimensionFromPacket(bytes, dataStart, dataSize, codec, registryAccess);
                    if (d != null) dim = d;
                } else if (actionId == createLocalPlayerId && dataSize >= 16 + 24 + 8) {
                    buf.position(dataStart + 16);      // skip UUID
                    double x = buf.getDouble();
                    double y = buf.getDouble();
                    double z = buf.getDouble();
                    float pitch = buf.getFloat();      // xRot
                    float yaw   = buf.getFloat();      // yRot
                    if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
                        return new StartPos(x, y, z, Float.isFinite(yaw) ? yaw : 0f,
                                Float.isFinite(pitch) ? pitch : 0f, dim);
                    }
                }
                buf.position(dataStart + dataSize);
            }
            buf.position(snapEnd);
        }
        return null;
    }

    private static String dimensionFromPacket(byte[] bytes, int off, int len,
                                              net.minecraft.network.codec.StreamCodec<io.netty.buffer.ByteBuf, net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> codec,
                                              RegistryAccess registryAccess) {
        try {
            byte[] data = Arrays.copyOfRange(bytes, off, off + len);
            var pkt = codec.decode(new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), registryAccess));
            String dimStr = null;
            if (pkt instanceof ClientboundLoginPacket lp) dimStr = lp.commonPlayerSpawnInfo().dimension().toString();
            else if (pkt instanceof ClientboundRespawnPacket rp) dimStr = rp.commonPlayerSpawnInfo().dimension().toString();
            if (dimStr == null) return null;
            // ResourceKey.toString() = "ResourceKey[minecraft:dimension / minecraft:overworld]"
            int slash = dimStr.indexOf('/');
            return slash >= 0 ? dimStr.substring(slash + 2, dimStr.length() - 1).trim() : "minecraft:overworld";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readVarInt(ByteBuffer buf) {
        int value = 0, shift = 0;
        byte b;
        do {
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
            if (shift > 35) throw new IllegalStateException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static String readUtf(ByteBuffer buf) {
        int len = readVarInt(buf);
        byte[] s = new byte[len];
        buf.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }

    private static boolean isVanillaDimension(String dim) {
        return "minecraft:overworld".equals(dim) || "minecraft:the_nether".equals(dim) || "minecraft:the_end".equals(dim);
    }

    // ------------------------------------------------------------------------------------
    // level.dat patching (plain-Java NBT so it doesn't depend on MC's NBT API)
    // ------------------------------------------------------------------------------------

    private static void patchLevelDat(Path levelDat, StartPos start) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(levelDat))))) {
            if (in.readByte() != Nbt.COMPOUND) throw new IOException("level.dat root is not a compound");
            in.readUTF();
            root = Nbt.readCompound(in);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) ((Nbt.Tag) root.get("Data")).value;

        int bx = (int) Math.floor(start.x());
        int by = (int) Math.floor(start.y());
        int bz = (int) Math.floor(start.z());

        // World spawn / respawn point -> recording start.
        Map<String, Object> spawn = new LinkedHashMap<>();
        spawn.put("pos", new Nbt.Tag(Nbt.INT_ARRAY, new int[]{bx, by, bz}));
        spawn.put("pitch", new Nbt.Tag(Nbt.FLOAT, start.pitch()));
        spawn.put("yaw", new Nbt.Tag(Nbt.FLOAT, start.yaw()));
        spawn.put("dimension", new Nbt.Tag(Nbt.STRING, start.dimension()));
        data.put("spawn", new Nbt.Tag(Nbt.COMPOUND, spawn));

        // Saved singleplayer player -> exact recording start (no random spawn-radius offset,
        // works in the Nether/End too).
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("Pos", new Nbt.Tag(Nbt.LIST, new Nbt.ListValue(Nbt.DOUBLE,
                List.of(start.x(), start.y(), start.z()))));
        player.put("Rotation", new Nbt.Tag(Nbt.LIST, new Nbt.ListValue(Nbt.FLOAT,
                List.of(start.yaw(), start.pitch()))));
        player.put("Dimension", new Nbt.Tag(Nbt.STRING, start.dimension()));
        Object gameType = data.get("GameType");
        int gt = gameType instanceof Nbt.Tag t && t.value instanceof Integer i ? i : 1;
        player.put("playerGameType", new Nbt.Tag(Nbt.INT, gt));
        data.put("Player", new Nbt.Tag(Nbt.COMPOUND, player));

        Path tmp = levelDat.resolveSibling("level.dat_fbrtmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeByte(Nbt.COMPOUND);
            out.writeUTF("");
            Nbt.writeCompound(out, root);
        }
        Files.move(tmp, levelDat, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Minimal NBT reader/writer that round-trips every tag type. */
    static final class Nbt {
        static final byte END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
                BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

        static final class Tag {
            final byte type;
            final Object value;
            Tag(byte type, Object value) { this.type = type; this.value = value; }
        }

        record ListValue(byte elementType, List<?> values) {}

        static Map<String, Object> readCompound(DataInput in) throws IOException {
            Map<String, Object> map = new LinkedHashMap<>();
            while (true) {
                byte type = in.readByte();
                if (type == END) return map;
                String name = in.readUTF();
                map.put(name, new Tag(type, readPayload(in, type)));
            }
        }

        static Object readPayload(DataInput in, byte type) throws IOException {
            switch (type) {
                case BYTE: return in.readByte();
                case SHORT: return in.readShort();
                case INT: return in.readInt();
                case LONG: return in.readLong();
                case FLOAT: return in.readFloat();
                case DOUBLE: return in.readDouble();
                case BYTE_ARRAY: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
                case STRING: return in.readUTF();
                case LIST: {
                    byte et = in.readByte();
                    int n = in.readInt();
                    List<Object> list = new ArrayList<>(Math.max(n, 0));
                    for (int i = 0; i < n; i++) list.add(readPayload(in, et));
                    return new ListValue(et, list);
                }
                case COMPOUND: return readCompound(in);
                case INT_ARRAY: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
                case LONG_ARRAY: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
                default: throw new IOException("Unknown NBT tag type " + type);
            }
        }

        @SuppressWarnings("unchecked")
        static void writeCompound(DataOutput out, Map<String, Object> map) throws IOException {
            for (var e : map.entrySet()) {
                Tag t = (Tag) e.getValue();
                out.writeByte(t.type);
                out.writeUTF(e.getKey());
                writePayload(out, t.type, t.value);
            }
            out.writeByte(END);
        }

        @SuppressWarnings("unchecked")
        static void writePayload(DataOutput out, byte type, Object v) throws IOException {
            switch (type) {
                case BYTE -> out.writeByte((Byte) v);
                case SHORT -> out.writeShort((Short) v);
                case INT -> out.writeInt((Integer) v);
                case LONG -> out.writeLong((Long) v);
                case FLOAT -> out.writeFloat(((Number) v).floatValue());
                case DOUBLE -> out.writeDouble(((Number) v).doubleValue());
                case BYTE_ARRAY -> { byte[] a = (byte[]) v; out.writeInt(a.length); out.write(a); }
                case STRING -> out.writeUTF((String) v);
                case LIST -> {
                    ListValue lv = (ListValue) v;
                    byte et = lv.values().isEmpty() && lv.elementType() == END ? END : lv.elementType();
                    out.writeByte(et);
                    out.writeInt(lv.values().size());
                    for (Object o : lv.values()) writePayload(out, et, o);
                }
                case COMPOUND -> writeCompound(out, (Map<String, Object>) v);
                case INT_ARRAY -> { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); }
                case LONG_ARRAY -> { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); }
                default -> throw new IOException("Unknown NBT tag type " + type);
            }
        }
    }
}
