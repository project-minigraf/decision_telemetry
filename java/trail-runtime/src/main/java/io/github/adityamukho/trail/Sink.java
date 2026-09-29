package io.github.adityamukho.trail;

import java.io.IOException;

/** Receives kept runs. Called on a single background thread, never on application threads. */
public interface Sink extends AutoCloseable {
    void write(RunRecord run) throws IOException;

    @Override
    default void close() throws IOException {
    }
}
