package io.github.adityamukho.trail.minigraf;

/**
 * The part of Minigraf the trail uses: run a Datalog command, get JSON back.
 *
 * <p>The runtime talks to {@code minigraf-jvm} through reflection, so it compiles and runs without
 * that jar. This interface is the seam.
 */
public interface DatalogEngine extends AutoCloseable {
    /** Runs one command ({@code transact}, {@code query}, {@code rule} ...) and returns its JSON result. */
    String execute(String datalog);

    /** Flushes to disk. */
    void checkpoint();

    @Override
    void close();
}
