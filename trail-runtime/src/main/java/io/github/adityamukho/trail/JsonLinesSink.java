package io.github.adityamukho.trail;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Writes each kept run to its own {@code .jsonl} file: a header line, then one line per node.
 * Needs nothing beyond the JDK, so it is the fallback when Minigraf is not on the classpath.
 */
public final class JsonLinesSink implements Sink {
    private final Path dir;

    public JsonLinesSink(Path dir) {
        this.dir = dir;
    }

    @Override
    public void write(RunRecord run) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(RunFiles.baseName(run) + ".jsonl");
        Path tmp = dir.resolve(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write(Json.write(RunJson.header(run)));
            w.newLine();
            for (Node n : run.nodes()) {
                w.write(Json.write(RunJson.node(n)));
                w.newLine();
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
