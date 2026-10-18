package com.moulberry.fbrtoworld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plain-Java NBT writer/reader (no Minecraft classes). Value types:
 * Map (compound, insertion order kept), List (list), String, Byte, Short, Integer, Long,
 * Float, Double, byte[], int[], long[], and {@link RawCompound} (an already-serialized
 * compound payload, used for block entities).
 */
final class FbrNbtIO {

    private FbrNbtIO() {}

    /** A compound whose payload bytes are already serialized (everything after the name). */
    record RawCompound(byte[] payload) {}

    // ---------------------------------------------------------------- writing

    /** Serializes a root compound the same way NbtIo.write does (type 10, empty name, payload). */
    static byte[] writeRoot(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16384);
        DataOutputStream out = new DataOutputStream(baos);
        out.writeByte(10);
        out.writeUTF("");
        writeCompoundPayload(out, root);
        out.flush();
        return baos.toByteArray();
    }

    private static int typeOf(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map || v instanceof RawCompound) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("Unsupported NBT value " + (v == null ? "null" : v.getClass()));
    }

    @SuppressWarnings("unchecked")
    private static void writeCompoundPayload(DataOutputStream out, Map<String, Object> map) throws IOException {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            Object v = e.getValue();
            out.writeByte(typeOf(v));
            out.writeUTF(e.getKey());
            writePayload(out, v);
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        switch (typeOf(v)) {
            case 1 -> out.writeByte((Byte) v);
            case 2 -> out.writeShort((Short) v);
            case 3 -> out.writeInt((Integer) v);
            case 4 -> out.writeLong((Long) v);
            case 5 -> out.writeFloat((Float) v);
            case 6 -> out.writeDouble((Double) v);
            case 7 -> { byte[] a = (byte[]) v; out.writeInt(a.length); out.write(a); }
            case 8 -> out.writeUTF((String) v);
            case 9 -> {
                List<Object> list = (List<Object>) v;
                int elemType = list.isEmpty() ? 0 : typeOf(list.get(0));
                for (Object o : list) {
                    if (typeOf(o) != elemType) throw new IllegalArgumentException("Mixed list");
                }
                out.writeByte(elemType);
                out.writeInt(list.size());
                for (Object o : list) writePayload(out, o);
            }
            case 10 -> {
                if (v instanceof RawCompound raw) out.write(raw.payload());
                else writeCompoundPayload(out, (Map<String, Object>) v);
            }
            case 11 -> { int[] a = (int[]) v; out.writeInt(a.length); for (int x : a) out.writeInt(x); }
            case 12 -> { long[] a = (long[]) v; out.writeInt(a.length); for (long x : a) out.writeLong(x); }
            default -> throw new IllegalStateException();
        }
    }

    // ---------------------------------------------------------------- reading

    /** Reads bytes made by NbtIo.write / writeRoot: returns the root compound. */
    static Map<String, Object> readRoot(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        int type = in.readUnsignedByte();
        if (type != 10) throw new IOException("Root is not a compound: " + type);
        in.readUTF();
        return readCompound(in, 0);
    }

    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT too deep");
        Map<String, Object> map = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) return map;
            String name = in.readUTF();
            map.put(name, readPayload(in, type, depth + 1));
        }
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] a = new byte[in.readInt()]; in.readFully(a); return a; }
            case 8: return in.readUTF();
            case 9: {
                int elemType = in.readUnsignedByte();
                int n = in.readInt();
                List<Object> list = new ArrayList<>(Math.max(0, Math.min(n, 65536)));
                for (int i = 0; i < n; i++) list.add(readPayload(in, elemType, depth + 1));
                return list;
            }
            case 10: return readCompound(in, depth);
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Unknown NBT type " + type);
        }
    }

    /** Strips the root header (type + empty name) from NbtIo.write output, leaving the compound payload. */
    static byte[] compoundPayload(byte[] rootBytes) throws IOException {
        if (rootBytes.length < 4 || rootBytes[0] != 10 || rootBytes[1] != 0 || rootBytes[2] != 0) {
            throw new IOException("Unexpected root header");
        }
        return Arrays.copyOfRange(rootBytes, 3, rootBytes.length);
    }

    // ---------------------------------------------------------------- self-check comparison

    /**
     * Compares two chunk NBTs by meaning. Block/biome palettes may be in a different order
     * (both are valid), so each section's blocks and biomes are expanded and compared entry
     * by entry. Also checks that the palette/data sizes follow the rules Minecraft uses when
     * loading. Returns null when they match, otherwise a short description of the difference.
     */
    static String compareChunks(byte[] ours, byte[] original) {
        try {
            Map<String, Object> a = readRoot(ours);
            Map<String, Object> b = readRoot(original);
            return compareValues("", a, b);
        } catch (Exception e) {
            return "couldn't compare: " + e;
        }
    }

    @SuppressWarnings("unchecked")
    private static String compareValues(String path, Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a, mb = (Map<String, Object>) b;
            if (!ma.keySet().equals(mb.keySet())) return path + ": keys " + ma.keySet() + " vs " + mb.keySet();
            for (String k : ma.keySet()) {
                String sub = path + "/" + k;
                String diff;
                if ((k.equals("block_states") || k.equals("biomes")) && path.contains("sections[")) {
                    diff = compareContainers(sub, ma.get(k), mb.get(k), k.equals("block_states") ? 4096 : 64, k.equals("block_states"));
                } else {
                    diff = compareValues(sub, ma.get(k), mb.get(k));
                }
                if (diff != null) return diff;
            }
            return null;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a, lb = (List<Object>) b;
            if (la.size() != lb.size()) return path + ": list size " + la.size() + " vs " + lb.size();
            for (int i = 0; i < la.size(); i++) {
                String diff = compareValues(path + "[" + i + "]", la.get(i), lb.get(i));
                if (diff != null) return diff;
            }
            return null;
        }
        if (a instanceof byte[] x && b instanceof byte[] y) return Arrays.equals(x, y) ? null : path + ": byte array differs";
        if (a instanceof int[] x && b instanceof int[] y) return Arrays.equals(x, y) ? null : path + ": int array differs";
        if (a instanceof long[] x && b instanceof long[] y) return Arrays.equals(x, y) ? null : path + ": long array differs";
        if (a == null ? b == null : a.equals(b)) return null;
        return path + ": " + a + " vs " + b;
    }

    @SuppressWarnings("unchecked")
    private static String compareContainers(String path, Object a, Object b, int entries, boolean blocks) {
        if (!(a instanceof Map) || !(b instanceof Map)) return path + ": not a compound";
        String[] ea, eb;
        try {
            ea = expand((Map<String, Object>) a, entries, blocks);
        } catch (Exception e) {
            return path + ": ours invalid (" + e.getMessage() + ")";
        }
        try {
            eb = expand((Map<String, Object>) b, entries, blocks);
        } catch (Exception e) {
            return path + ": original didn't follow the expected rules (" + e.getMessage() + ")";
        }
        for (int i = 0; i < entries; i++) {
            if (!ea[i].equals(eb[i])) return path + "[" + i + "]: " + ea[i] + " vs " + eb[i];
        }
        return null;
    }

    /** Expands a saved palette container into one canonical string per entry. */
    @SuppressWarnings("unchecked")
    static String[] expand(Map<String, Object> container, int entries, boolean blocks) {
        Object pal = container.get("palette");
        if (!(pal instanceof List)) throw new IllegalStateException("no palette");
        List<Object> palette = (List<Object>) pal;
        if (palette.isEmpty()) throw new IllegalStateException("empty palette");
        String[] canon = new String[palette.size()];
        for (int i = 0; i < canon.length; i++) canon[i] = canonical(palette.get(i));
        String[] out = new String[entries];
        int bits = diskBits(palette.size(), blocks);
        if (bits == 0) {
            Arrays.fill(out, canon[0]);
            return out;
        }
        Object d = container.get("data");
        if (!(d instanceof long[] data)) throw new IllegalStateException("missing data");
        int perLong = 64 / bits;
        int expected = (entries + perLong - 1) / perLong;
        if (data.length != expected) throw new IllegalStateException("data length " + data.length + " != " + expected);
        long mask = (1L << bits) - 1;
        for (int i = 0; i < entries; i++) {
            int idx = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (idx >= canon.length) throw new IllegalStateException("index " + idx + " past palette " + canon.length);
            out[i] = canon[idx];
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String canonical(Object entry) {
        if (entry instanceof String s) return s;
        if (entry instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) entry;
            StringBuilder sb = new StringBuilder(String.valueOf(m.get("Name")));
            Object props = m.get("Properties");
            if (props instanceof Map) {
                sb.append(new TreeMap<>((Map<String, Object>) props));
            }
            return sb.toString();
        }
        return String.valueOf(entry);
    }

    // ---------------------------------------------------------------- palette size rules

    static int ceilLog2(int n) {
        return n <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    /** Bits Minecraft uses on disk for a palette of this size (blocks: at least 4 once there's more than one entry). */
    static int diskBits(int paletteSize, boolean blocks) {
        int b = ceilLog2(paletteSize);
        if (b == 0) return 0;
        if (blocks && b <= 4) return 4;
        return b;
    }
}
