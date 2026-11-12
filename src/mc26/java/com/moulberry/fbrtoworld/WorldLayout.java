package com.moulberry.fbrtoworld;

import com.moulberry.fbrtoworld.config.FbrGameRuleConfig;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

final class WorldLayout {

    private static final int DATA_VERSION = SharedConstants.getCurrentVersion().dataVersion().version();
    private static final String VERSION_NAME = SharedConstants.getCurrentVersion().name();

    private WorldLayout() {}

    static Path dimensionPath(Path root, String dimId) {
        String[] parts = dimId.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : parts[0];
        return root.resolve("dimensions").resolve(namespace).resolve(path);
    }

    private static Path legacyDimensionPath(Path root, String dimId) {
        return switch (dimId) {
            case "minecraft:overworld"  -> root;
            case "minecraft:the_nether" -> root.resolve("DIM-1");
            case "minecraft:the_end"    -> root.resolve("DIM1");
            default -> dimensionPath(root, dimId);
        };
    }

    static boolean isLegacyLayout(Path world) {
        if (Files.isDirectory(world.resolve("dimensions").resolve("minecraft").resolve("overworld"))) {
            return false;
        }
        if (Files.isDirectory(world.resolve("region"))
                || Files.isDirectory(world.resolve("DIM-1"))
                || Files.isDirectory(world.resolve("DIM1"))) {
            return true;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(world.resolve("level.dat"), NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompoundOrEmpty("Data");
            return !data.contains("singleplayer_uuid") && data.getCompound("Player").isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    static Path importDimensionPath(Path world, String dimId) {
        return isLegacyLayout(world) ? legacyDimensionPath(world, dimId) : dimensionPath(world, dimId);
    }

    static String importNotice(Path world) {
        if (!isLegacyLayout(world)) return null;
        return "§e[FbrToWorld] " + world.getFileName()
                + " is still in the old (pre-26.1) world format - chunks were added the old way."
                + " Minecraft will upgrade it when you open it.";
    }

    static void writeLevelData(Path worldFolder, String worldName) throws IOException {
        CompoundTag data = new CompoundTag();

        ListTag brands = new ListTag();
        brands.add(StringTag.valueOf("fabric"));
        data.put("ServerBrands", brands);
        data.putBoolean("WasModded", true);

        CompoundTag version = new CompoundTag();
        version.putString("Name", VERSION_NAME);
        version.putInt("Id", DATA_VERSION);
        version.putBoolean("Snapshot", !SharedConstants.getCurrentVersion().stable());
        version.putString("Series", SharedConstants.getCurrentVersion().dataVersion().series());
        data.put("Version", version);
        data.putInt("DataVersion", DATA_VERSION);

        data.putInt("GameType", 1);
        CompoundTag spawn = new CompoundTag();
        spawn.putString("dimension", "minecraft:overworld");
        spawn.put("pos", new IntArrayTag(new int[]{8, -64, 8}));
        spawn.putFloat("yaw", 0.0f);
        spawn.putFloat("pitch", 0.0f);
        data.put("spawn", spawn);

        data.putLong("Time", 0L);
        data.putLong("LastPlayed", System.currentTimeMillis());
        data.putString("LevelName", worldName);
        data.putInt("version", 19133);
        data.putBoolean("allowCommands", true);
        data.putBoolean("initialized", true);

        CompoundTag difficulty = new CompoundTag();
        difficulty.putString("difficulty", "normal");
        difficulty.putBoolean("hardcore", false);
        difficulty.putBoolean("locked", false);
        data.put("difficulty_settings", difficulty);

        CompoundTag dataPacks = new CompoundTag();
        ListTag enabled = new ListTag();
        enabled.add(StringTag.valueOf("vanilla"));
        dataPacks.put("Enabled", enabled);
        dataPacks.put("Disabled", new ListTag());
        data.put("DataPacks", dataPacks);

        CompoundTag root = new CompoundTag();
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldFolder.resolve("level.dat"));
        Files.write(worldFolder.resolve("session.lock"),
                ByteBuffer.allocate(8).putLong(System.currentTimeMillis()).array());

        writeSavedData(worldFolder, "world_gen_settings", buildWorldGenSettings());
        writeSavedData(worldFolder, "game_rules", buildGameRules());

        CompoundTag weather = new CompoundTag();
        weather.putInt("clear_weather_time", 0);
        weather.putInt("rain_time", 0);
        weather.putInt("thunder_time", 0);
        weather.putBoolean("raining", false);
        weather.putBoolean("thundering", false);
        writeSavedData(worldFolder, "weather", weather);
    }

    private static void writeSavedData(Path world, String id, CompoundTag contents) throws IOException {
        CompoundTag full = new CompoundTag();
        full.put("data", contents);
        full.putInt("DataVersion", DATA_VERSION);
        Path file = world.resolve("data").resolve("minecraft").resolve(id + ".dat");
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(full, file);
    }

    private static CompoundTag buildWorldGenSettings() {
        CompoundTag settings = new CompoundTag();
        settings.putLong("seed", 0L);
        settings.putBoolean("generate_structures", false);
        settings.putBoolean("bonus_chest", false);

        CompoundTag dimensions = new CompoundTag();
        dimensions.put("minecraft:overworld", voidDimension("minecraft:overworld", "minecraft:plains"));
        dimensions.put("minecraft:the_nether", voidDimension("minecraft:the_nether", "minecraft:nether_wastes"));
        dimensions.put("minecraft:the_end", voidDimension("minecraft:the_end", "minecraft:the_end"));
        settings.put("dimensions", dimensions);
        return settings;
    }

    private static CompoundTag voidDimension(String type, String biome) {
        CompoundTag flat = new CompoundTag();
        flat.put("layers", new ListTag());
        flat.putString("biome", biome);
        flat.putBoolean("features", false);
        flat.putBoolean("lakes", false);

        CompoundTag generator = new CompoundTag();
        generator.putString("type", "minecraft:flat");
        generator.put("settings", flat);

        CompoundTag stem = new CompoundTag();
        stem.putString("type", type);
        stem.put("generator", generator);
        return stem;
    }

    private static CompoundTag buildGameRules() {
        Map<String, String> configured = FbrGameRuleConfig.load();
        CompoundTag rules = new CompoundTag();
        for (GameRule<?> rule : BuiltInRegistries.GAME_RULE) {
            Identifier key = BuiltInRegistries.GAME_RULE.getKey(rule);
            if (key == null) continue;
            String raw = configured.get(key.toString());
            if (raw == null) raw = configured.get("minecraft:" + rule.id());
            if (raw != null) putRule(rules, rule, key.toString(), raw);
        }
        return rules;
    }

    private static <T> void putRule(CompoundTag rules, GameRule<T> rule, String key, String raw) {
        rule.deserialize(raw).result()
                .flatMap(value -> rule.valueCodec().encodeStart(NbtOps.INSTANCE, value).result())
                .ifPresent(tag -> rules.put(key, tag));
    }

    static void writePlayerStart(Path worldFolder, Map<String, Object> levelData, FbrStartSpawn.StartPos start, Minecraft mc) throws IOException {
        Object gameType = levelData.get("GameType");
        int gt = gameType instanceof FbrStartSpawn.Nbt.Tag t && t.value instanceof Integer i ? i : 1;

        UUID owner = ownerUuid(mc);

        CompoundTag player = new CompoundTag();
        player.putInt("DataVersion", DATA_VERSION);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(start.x()));
        pos.add(DoubleTag.valueOf(start.y()));
        pos.add(DoubleTag.valueOf(start.z()));
        player.put("Pos", pos);
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(start.yaw()));
        rotation.add(FloatTag.valueOf(start.pitch()));
        player.put("Rotation", rotation);
        player.putString("Dimension", start.dimension());
        player.putInt("playerGameType", gt);

        Path dir = worldFolder.resolve("players").resolve("data");
        Files.createDirectories(dir);
        Path file = dir.resolve(owner + ".dat");
        Path tmp = dir.resolve(owner + ".dat_fbrtmp");
        NbtIo.writeCompressed(player, tmp);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);

        levelData.remove("Player");
        levelData.put("singleplayer_uuid", new FbrStartSpawn.Nbt.Tag(FbrStartSpawn.Nbt.INT_ARRAY, UUIDUtil.uuidToIntArray(owner)));
    }

    private static UUID ownerUuid(Minecraft mc) {
        try {
            Minecraft client = mc != null ? mc : Minecraft.getInstance();
            if (client != null && client.getUser() != null && client.getUser().getProfileId() != null) {
                return client.getUser().getProfileId();
            }
        } catch (Throwable ignored) {
        }
        return UUID.randomUUID();
    }
}
