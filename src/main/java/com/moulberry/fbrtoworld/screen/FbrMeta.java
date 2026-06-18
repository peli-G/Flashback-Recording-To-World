package com.moulberry.fbrtoworld.screen;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Standalone parser for Flashback's metadata.json format (read-only subset —
 * only the fields our replay browser actually displays). Independent of any
 * Flashback classes so this mod works even when Flashback isn't installed.
 */
public class FbrMeta {
    public String name = "Unnamed";
    public String worldName = null;
    public int totalTicks = -1;
    public int dataVersion = 0;
    public int protocolVersion = 0;
    public String versionString = null;

    @Nullable
    public static FbrMeta fromJson(JsonObject meta) {
        if (!meta.has("uuid") || !meta.has("name")) return null;

        FbrMeta result = new FbrMeta();
        result.name = meta.get("name").getAsString();

        if (meta.has("world_name")) result.worldName = meta.get("world_name").getAsString();
        if (meta.has("total_ticks")) result.totalTicks = meta.get("total_ticks").getAsInt();
        if (meta.has("data_version")) result.dataVersion = meta.get("data_version").getAsInt();
        if (meta.has("protocol_version")) result.protocolVersion = meta.get("protocol_version").getAsInt();
        if (meta.has("version_string")) result.versionString = meta.get("version_string").getAsString();

        if (!meta.has("chunks")) return null; // not a valid Flashback recording

        return result;
    }
}
