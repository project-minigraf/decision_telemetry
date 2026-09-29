package io.github.adityamukho.trail;

import io.github.adityamukho.trail.minigraf.MinigrafRunStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** File naming and loading for stored runs. */
public final class RunFiles {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private RunFiles() {
    }

    /**
     * {@code <utc time>-<run name>-<first 8 chars of run id>}. Sorting by name sorts by time, and
     * runs of one kind are easy to find with a glob.
     */
    public static String baseName(RunRecord run) {
        String name = run.name().replaceAll("[^A-Za-z0-9._-]+", "_");
        if (name.length() > 80) {
            name = name.substring(name.length() - 80);
        }
        return STAMP.format(Instant.ofEpochMilli(run.startedAtMillis())) + "-" + name + "-"
                + run.id().substring(0, 8);
    }

    /** Loads a run from a {@code .jsonl} or {@code .graph} file. */
    public static RunRecord load(Path file) throws IOException {
        String n = file.getFileName().toString();
        if (n.endsWith(".jsonl")) {
            return RunJson.fromLines(Files.readAllLines(file, StandardCharsets.UTF_8));
        }
        if (n.endsWith(".graph")) {
            return MinigrafRunStore.read(file);
        }
        throw new IOException("Unknown run file type: " + file + " (expected .jsonl or .graph)");
    }
}
