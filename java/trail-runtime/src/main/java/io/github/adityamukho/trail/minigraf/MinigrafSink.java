package io.github.adityamukho.trail.minigraf;

import io.github.adityamukho.trail.RunFiles;
import io.github.adityamukho.trail.RunRecord;
import io.github.adityamukho.trail.Sink;

import java.io.IOException;
import java.nio.file.Path;

/** Writes each kept run to its own Minigraf {@code .graph} file. */
public final class MinigrafSink implements Sink {
    private final Path dir;

    public MinigrafSink(Path dir) {
        this.dir = dir;
    }

    /** True when {@code minigraf-jvm} (or a configured engine factory) is available. */
    public static boolean available() {
        return Engines.available();
    }

    @Override
    public void write(RunRecord run) throws IOException {
        MinigrafRunStore.write(run, dir.resolve(RunFiles.baseName(run) + ".graph"));
    }
}
