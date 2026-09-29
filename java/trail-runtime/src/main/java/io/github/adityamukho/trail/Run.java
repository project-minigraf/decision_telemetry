package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A live run. Collects nodes from any thread until the root frame and all async work it spawned
 * have finished, then hands itself to the {@link Recorder}.
 */
final class Run {
    final String id = UUID.randomUUID().toString();
    final String name;
    final TrailConfig cfg;
    final long startMillis = System.currentTimeMillis();
    final long startNanos = System.nanoTime();

    private final AtomicLong nextId = new AtomicLong(1);
    private final List<Node> nodes = new ArrayList<>();
    private final List<String> anomalies = new ArrayList<>();
    private final AtomicLong dropped = new AtomicLong();
    /** The root frame holds one permit; each wrapped async task holds one while it runs. */
    private final AtomicInteger pending = new AtomicInteger(1);
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean rootDone = new AtomicBoolean();
    private volatile boolean anyFrameError;
    private volatile String status = "ok";
    private volatile String error;

    Run(String name, TrailConfig cfg) {
        this.name = name;
        this.cfg = cfg;
    }

    long nextId() {
        return nextId.getAndIncrement();
    }

    long relNanos() {
        return System.nanoTime() - startNanos;
    }

    /** Stores a node, or counts it as dropped when the run is full or already written. */
    void add(Node n) {
        if (finished.get()) {
            dropped.incrementAndGet();
            return;
        }
        synchronized (nodes) {
            if (nodes.size() >= cfg.maxNodes()) {
                dropped.incrementAndGet();
                return;
            }
            nodes.add(n);
        }
    }

    void frameFailed() {
        anyFrameError = true;
    }

    void rootFailed(Throwable t) {
        status = "error";
        error = t.getClass().getName();
    }

    void anomaly(String reason) {
        synchronized (anomalies) {
            anomalies.add(reason);
        }
    }

    boolean acquire() {
        while (true) {
            int p = pending.get();
            if (p <= 0) {
                return false;
            }
            if (pending.compareAndSet(p, p + 1)) {
                return true;
            }
        }
    }

    void release() {
        if (pending.decrementAndGet() == 0) {
            finish();
        }
    }

    /** Called when the root frame exits. Waits for async work, but not forever. */
    void rootExited() {
        if (!rootDone.compareAndSet(false, true)) {
            return;
        }
        if (pending.get() > 1) {
            Recorder.get().scheduleForcedFinish(this, cfg.asyncGraceMillis());
        }
        release();
    }

    void forceFinish() {
        if (!finished.get()) {
            anomaly("async work still running after " + cfg.asyncGraceMillis() + " ms");
            finish();
        }
    }

    private void finish() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        boolean keep;
        synchronized (anomalies) {
            keep = "error".equals(status) || anyFrameError || !anomalies.isEmpty()
                    || (cfg.sample() > 0 && ThreadLocalRandom.current().nextDouble() < cfg.sample());
        }
        if (!keep) {
            return;
        }
        List<Node> copy;
        synchronized (nodes) {
            copy = new ArrayList<>(nodes);
        }
        List<String> an;
        synchronized (anomalies) {
            an = new ArrayList<>(anomalies);
        }
        Recorder.get().submit(new RunRecord(id, name, cfg.codeVersion(), startMillis, status, error,
                dropped.get(), an, copy));
    }
}
