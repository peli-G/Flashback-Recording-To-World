package com.moulberry.fbrtoworld.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.moulberry.fbrtoworld.FbrToWorldMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists the gamerule defaults FBR writes into every converted world's level.dat.
 * Stored as a simple "rule name -> NBT-style value string" map in a JSON file under
 * .minecraft/config/, completely independent of any particular world's actual
 * GameRules instance (since conversion can happen before any world with these exact
 * settings exists).
 */
public class FbrGameRuleConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("fbr-to-world-gamerules.json");
    }

    /**
     * The mod's built-in defaults — these are what "Reset" restores, and what gets
     * used the very first time (before the user has ever customized anything).
     * Mirrors the same defaults already hardcoded in FbrWorldConverter#writeLevelDat.
     */
    public static Map<String, String> builtInDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("minecraft:advance_time", "false");
        m.put("minecraft:advance_weather", "false");
        m.put("minecraft:allow_entering_nether_using_portals", "true");
        m.put("minecraft:block_drops", "true");
        m.put("minecraft:block_explosion_drop_decay", "true");
        m.put("minecraft:command_block_output", "true");
        m.put("minecraft:command_blocks_work", "true");
        m.put("minecraft:drowning_damage", "true");
        m.put("minecraft:elytra_movement_check", "true");
        m.put("minecraft:ender_pearls_vanish_on_death", "true");
        m.put("minecraft:entity_drops", "true");
        m.put("minecraft:fall_damage", "true");
        m.put("minecraft:fire_damage", "true");
        m.put("minecraft:fire_spread_radius_around_player", "128");
        m.put("minecraft:forgive_dead_players", "true");
        m.put("minecraft:freeze_damage", "true");
        m.put("minecraft:global_sound_events", "true");
        m.put("minecraft:immediate_respawn", "false");
        m.put("minecraft:keep_inventory", "false");
        m.put("minecraft:limited_crafting", "false");
        m.put("minecraft:lava_source_conversion", "false");
        m.put("minecraft:locator_bar", "true");
        m.put("minecraft:log_admin_commands", "true");
        m.put("minecraft:max_block_modifications", "32768");
        m.put("minecraft:max_command_forks", "65536");
        m.put("minecraft:max_command_sequence_length", "65536");
        m.put("minecraft:max_entity_cramming", "24");
        m.put("minecraft:max_snow_accumulation_height", "1");
        m.put("minecraft:mob_drops", "true");
        m.put("minecraft:mob_explosion_drop_decay", "true");
        m.put("minecraft:mob_griefing", "true");
        m.put("minecraft:natural_health_regeneration", "true");
        m.put("minecraft:player_movement_check", "true");
        m.put("minecraft:players_nether_portal_creative_delay", "0");
        m.put("minecraft:players_nether_portal_default_delay", "80");
        m.put("minecraft:players_sleeping_percentage", "100");
        m.put("minecraft:projectiles_can_break_blocks", "true");
        m.put("minecraft:pvp", "true");
        m.put("minecraft:raids", "true");
        m.put("minecraft:random_tick_speed", "3");
        m.put("minecraft:reduced_debug_info", "false");
        m.put("minecraft:respawn_radius", "10");
        m.put("minecraft:send_command_feedback", "true");
        m.put("minecraft:show_advancement_messages", "true");
        m.put("minecraft:show_death_messages", "true");
        m.put("minecraft:spawn_mobs", "false");
        m.put("minecraft:spawn_monsters", "false");
        m.put("minecraft:spawn_patrols", "false");
        m.put("minecraft:spawn_phantoms", "false");
        m.put("minecraft:spawn_wandering_traders", "false");
        m.put("minecraft:spawn_wardens", "false");
        m.put("minecraft:spawner_blocks_work", "true");
        m.put("minecraft:spectators_generate_chunks", "true");
        m.put("minecraft:spread_vines", "true");
        m.put("minecraft:tnt_explodes", "true");
        m.put("minecraft:tnt_explosion_drop_decay", "false");
        m.put("minecraft:universal_anger", "false");
        m.put("minecraft:water_source_conversion", "true");
        return m;
    }

    /** Loads the user's saved gamerule defaults, falling back to built-in defaults for anything missing. */
    public static Map<String, String> load() {
        Map<String, String> result = new LinkedHashMap<>(builtInDefaults());
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                JsonObject obj = GSON.fromJson(json, JsonObject.class);
                if (obj != null) {
                    for (var entry : obj.entrySet()) {
                        result.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            } catch (Exception e) {
                FbrToWorldMod.LOGGER.warn("[FbrToWorld] Failed to load gamerule config, using defaults: {}", e.getMessage());
            }
        }
        return result;
    }

    public static void save(Map<String, String> rules) {
        try {
            Files.createDirectories(configPath().getParent());
            JsonObject obj = new JsonObject();
            for (var entry : rules.entrySet()) {
                obj.addProperty(entry.getKey(), entry.getValue());
            }
            Files.writeString(configPath(), GSON.toJson(obj));
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.error("[FbrToWorld] Failed to save gamerule config", e);
        }
    }
}
