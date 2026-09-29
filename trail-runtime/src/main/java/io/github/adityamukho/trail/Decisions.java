package io.github.adityamukho.trail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Entry points for decision tracing.
 *
 * <p>Application code uses the public helpers: {@link #wrap(ExecutorService)} and friends to carry
 * a run across threads, {@link #anomaly(String)} to force a run to be kept, and
 * {@link #configure(TrailConfig)} to set options in code.
 *
 * <p>The methods marked "generated code" are called by code that the {@code trail-javac} plugin
 * inserts. They are public only so that generated code can reach them.
 */
public final class Decisions {
    /** Placeholder the plugin passes instead of a {@link Redact redacted} value. */
    public static final Object REDACTED = new Object() {
        @Override
        public String toString() {
            return "<redacted>";
        }
    };

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static volatile TrailConfig config;

    private Decisions() {
    }

    // ---------------------------------------------------------------- configuration

    /** Replaces the configuration. Affects runs started after the call. */
    public static void configure(TrailConfig cfg) {
        config = cfg;
        Recorder.get().reconfigure(cfg);
    }

    public static TrailConfig config() {
        TrailConfig c = config;
        if (c == null) {
            synchronized (Decisions.class) {
                c = config;
                if (c == null) {
                    c = TrailConfig.fromEnvironment();
                    config = c;
                }
            }
        }
        return c;
    }

    /** Sends kept runs to this sink instead of the configured one. Pass null to go back. */
    public static void setSink(Sink sink) {
        Recorder.get().overrideSink(sink);
    }

    /** Waits until every kept run so far has been written. Returns false on timeout. */
    public static boolean flush(Duration timeout) {
        return Recorder.get().flush(timeout);
    }

    // ---------------------------------------------------------------- application helpers

    /** True while the current thread is inside a recording run. */
    public static boolean active() {
        Frame f = CURRENT.get();
        return f != null && f.run != null;
    }

    /** Id of the current run, or null. */
    public static String currentRunId() {
        Frame f = CURRENT.get();
        return f == null || f.run == null ? null : f.run.id;
    }

    /**
     * Marks the current run as worth keeping, whatever the sample rate. Use it where the code
     * notices something odd but carries on, such as a fallback path or a retry.
     */
    public static void anomaly(String reason) {
        Frame f = CURRENT.get();
        if (f != null && f.run != null) {
            f.run.anomaly(reason);
        }
    }

    /** Carries the current run into a task that runs later or on another thread. */
    public static Runnable wrap(Runnable task) {
        Frame anchor = CURRENT.get();
        if (anchor == null || anchor.run == null || !anchor.run.acquire()) {
            return task;
        }
        return () -> {
            Frame before = CURRENT.get();
            CURRENT.set(anchor);
            try {
                task.run();
            } finally {
                restore(before);
                anchor.run.release();
            }
        };
    }

    /** Carries the current run into a task that runs later or on another thread. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Frame anchor = CURRENT.get();
        if (anchor == null || anchor.run == null || !anchor.run.acquire()) {
            return task;
        }
        return () -> {
            Frame before = CURRENT.get();
            CURRENT.set(anchor);
            try {
                return task.call();
            } finally {
                restore(before);
                anchor.run.release();
            }
        };
    }

    /** Carries the current run into a supplier, for {@code CompletableFuture.supplyAsync}. */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Frame anchor = CURRENT.get();
        if (anchor == null || anchor.run == null || !anchor.run.acquire()) {
            return task;
        }
        return () -> {
            Frame before = CURRENT.get();
            CURRENT.set(anchor);
            try {
                return task.get();
            } finally {
                restore(before);
                anchor.run.release();
            }
        };
    }

    /** An executor that carries the submitting thread's run into every task. */
    public static Executor wrap(Executor executor) {
        return command -> executor.execute(wrap(command));
    }

    /**
     * An executor service that carries the submitting thread's run into every task.
     * Shutting down the wrapper shuts down the delegate.
     */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                delegate.execute(wrap(command));
            }

            @Override
            public void shutdown() {
                delegate.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return delegate.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return delegate.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return delegate.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return delegate.awaitTermination(timeout, unit);
            }
        };
    }

    // ---------------------------------------------------------------- generated code

    /** Generated code: entry to a traced method. */
    public static Frame enter(String meta, Object... args) {
        Frame parent = CURRENT.get();
        boolean inRun = parent != null && parent.run != null;
        TrailConfig cfg = inRun ? parent.run.cfg : config();
        if (!cfg.enabled()) {
            return Frame.NOOP;
        }
        Site site = Site.of(meta);
        Run run;
        boolean root;
        if (inRun) {
            run = parent.run;
            root = false;
        } else if (site.runBoundary || cfg.autoRun()) {
            String name = site.runBoundary && !site.text.isEmpty() ? site.text : site.id;
            run = new Run(name, cfg);
            root = true;
            parent = null;
        } else {
            return Frame.NOOP;
        }
        String thread = Thread.currentThread().getName();
        long id = run.nextId();
        boolean async = parent != null && !parent.threadName.equals(thread);
        Node node = new Node(id, parent == null ? 0 : parent.id, Site.FRAME, site.id, site.file, site.line,
                thread, async, run.relNanos(), "", null, inputs(site, args, cfg));
        run.add(node);
        Frame f = new Frame(run, node, CURRENT.get(), root, id, thread);
        CURRENT.set(f);
        return f;
    }

    /** Generated code: the then-arm of an {@code if} was taken. */
    public static void yes(String meta, Object... values) {
        branch(meta, "true", values);
    }

    /** Generated code: the else-arm of an {@code if} was taken. */
    public static void no(String meta, Object... values) {
        branch(meta, "false", values);
    }

    /** Generated code: the true operand of a ternary was chosen. Returns the operand. */
    public static <T> T tv(String meta, T operand, Object... values) {
        branch(meta, "true", values);
        return operand;
    }

    /** Generated code: the false operand of a ternary was chosen. Returns the operand. */
    public static <T> T fv(String meta, T operand, Object... values) {
        branch(meta, "false", values);
        return operand;
    }

    /** Generated code: a {@code switch} selector. Returns the selector unchanged. */
    public static <T> T choose(String meta, T selector) {
        Frame f = CURRENT.get();
        if (f == null || f.run == null) {
            return selector;
        }
        Run run = f.run;
        Site site = Site.of(meta);
        String outcome = selector == REDACTED ? "<redacted>" : Capture.render(selector, run.cfg);
        run.add(new Node(run.nextId(), f.id, Site.SWITCH, site.id, site.file, site.line,
                Thread.currentThread().getName(), false, run.relNanos(), site.text, outcome, List.of()));
        return selector;
    }

    /** Generated code: a {@code switch} on a {@link Redact redacted} variable. Records no value. */
    public static <T> T chooseRedacted(String meta, T selector) {
        choose(meta, REDACTED);
        return selector;
    }

    // ---------------------------------------------------------------- internals

    static void restore(Frame previous) {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    private static void branch(String meta, String outcome, Object[] values) {
        Frame f = CURRENT.get();
        if (f == null || f.run == null) {
            return;
        }
        Run run = f.run;
        Site site = Site.of(meta);
        run.add(new Node(run.nextId(), f.id, site.kind, site.id, site.file, site.line,
                Thread.currentThread().getName(), false, run.relNanos(), site.text, outcome,
                inputs(site, values, run.cfg)));
    }

    private static List<Node.Input> inputs(Site site, Object[] values, TrailConfig cfg) {
        int n = Math.min(site.names.length, values == null ? 0 : values.length);
        if (n == 0) {
            return List.of();
        }
        List<Node.Input> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Node.Input(site.names[i], Capture.render(values[i], cfg)));
        }
        return out;
    }

    /** For tests: forget per-thread state. */
    static void resetThread() {
        CURRENT.remove();
    }
}
