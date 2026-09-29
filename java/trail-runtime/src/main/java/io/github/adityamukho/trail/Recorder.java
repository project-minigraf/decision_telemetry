package io.github.adityamukho.trail;

import io.github.adityamukho.trail.minigraf.MinigrafSink;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes kept runs off the application's threads. One writer thread, a bounded queue, and a
 * counter for runs lost when the queue is full.
 */
final class Recorder {
    private static final Logger LOG = Logger.getLogger("io.github.adityamukho.trail");
    private static final Recorder INSTANCE = new Recorder();

    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService timer;
    private final AtomicLong lostRuns = new AtomicLong();
    private volatile Sink configured;
    private volatile Sink override;

    private Recorder() {
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
                r -> daemon(r, "decision-telemetry-writer"));
        timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "decision-telemetry-timer"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flush(Duration.ofSeconds(5));
            closeQuietly(configured);
        }, "decision-telemetry-shutdown"));
    }

    static Recorder get() {
        return INSTANCE;
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void reconfigure(TrailConfig cfg) {
        Sink old;
        synchronized (this) {
            old = configured;
            configured = null;
        }
        if (old != null) {
            writer.execute(() -> closeQuietly(old));
        }
    }

    void overrideSink(Sink sink) {
        override = sink;
    }

    void scheduleForcedFinish(Run run, long delayMillis) {
        timer.schedule(run::forceFinish, delayMillis, TimeUnit.MILLISECONDS);
    }

    void submit(RunRecord record) {
        try {
            writer.execute(() -> {
                try {
                    sink().write(record);
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "decision-telemetry: could not write run " + record.id(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            long n = lostRuns.incrementAndGet();
            if (n == 1 || n % 1000 == 0) {
                LOG.warning("decision-telemetry: writer queue full, " + n + " runs lost so far");
            }
        }
    }

    boolean flush(Duration timeout) {
        CountDownLatch done = new CountDownLatch(1);
        try {
            writer.execute(done::countDown);
            return done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Sink sink() {
        Sink o = override;
        if (o != null) {
            return o;
        }
        Sink s = configured;
        if (s == null) {
            synchronized (this) {
                s = configured;
                if (s == null) {
                    s = create(Decisions.config());
                    configured = s;
                }
            }
        }
        return s;
    }

    static Sink create(TrailConfig cfg) {
        switch (cfg.sink()) {
            case "none":
                return run -> { };
            case "jsonl":
                return new JsonLinesSink(cfg.dir());
            case "minigraf":
                return new MinigrafSink(cfg.dir());
            case "both":
                return composite(List.of(new MinigrafSink(cfg.dir()), new JsonLinesSink(cfg.dir())));
            case "auto":
            default:
                return MinigrafSink.available() ? new MinigrafSink(cfg.dir()) : new JsonLinesSink(cfg.dir());
        }
    }

    private static Sink composite(List<Sink> sinks) {
        return new Sink() {
            @Override
            public void write(RunRecord run) throws IOException {
                List<IOException> errors = new ArrayList<>();
                for (Sink s : sinks) {
                    try {
                        s.write(run);
                    } catch (IOException e) {
                        errors.add(e);
                    }
                }
                if (!errors.isEmpty()) {
                    throw errors.get(0);
                }
            }

            @Override
            public void close() {
                sinks.forEach(Recorder::closeQuietly);
            }
        };
    }

    private static void closeQuietly(Sink s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (Exception e) {
            LOG.log(Level.FINE, "decision-telemetry: error closing sink", e);
        }
    }
}
