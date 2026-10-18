package com.moulberry.fbrtoworld;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Remembers which recordings have been put into a world (file "fbr-to-world-sources.txt"
 * in the world folder), so Import can skip recordings that are already in it.
 * A recording is identified by its file name + size.
 */
final class FbrSources {

    static final String FILE_NAME = "fbr-to-world-sources.txt";

    private FbrSources() {}

    static String key(Path recording) {
        long size;
        try {
            size = Files.size(recording);
        } catch (IOException e) {
            size = -1;
        }
        return recording.getFileName().toString() + "\t" + size;
    }

    static Set<String> read(Path world) {
        Set<String> keys = new HashSet<>();
        Path file = world.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) return keys;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.split("\t");
                if (parts.length >= 2) keys.add(parts[0] + "\t" + parts[1]);
            }
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't read {}: {}", file, e.getMessage());
        }
        return keys;
    }

    static void append(Path world, List<Path> recordings) {
        Path file = world.resolve(FILE_NAME);
        try {
            StringBuilder sb = new StringBuilder();
            if (!Files.exists(file)) {
                sb.append("# Recordings already added to this world by FBR to World (name<TAB>size<TAB>date added).\n");
                sb.append("# Delete a line (or this file) to let Import add that recording again.\n");
            }
            long now = System.currentTimeMillis();
            for (Path p : recordings) sb.append(key(p)).append('\t').append(now).append('\n');
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            FbrToWorldMod.LOGGER.warn("[FbrToWorld] Couldn't update {}: {}", file, e.getMessage());
        }
    }
}
