package io.github.adityamukho.trail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.adityamukho.trail.TestSupport.meta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the runtime the way generated code does. */
class DecisionsTest {
    private final TestSupport.MemorySink sink = new TestSupport.MemorySink();

    @BeforeEach
    void setUp() {
        Decisions.configure(TrailConfig.builder().sample(1.0).asyncGraceMillis(2000).build());
        Decisions.setSink(sink);
        Decisions.resetThread();
    }

    @AfterEach
    void tearDown() {
        Decisions.flush(java.time.Duration.ofSeconds(10));
        Decisions.setSink(null);
        Decisions.resetThread();
    }

    private static final String RUN = meta("R", "demo.A#handle(int)", "x", "handle-run");
    private static final String CALL = meta("F", "demo.A#helper(int)", "y", "");
    private static final String IF = meta("I", "demo.A#helper(int)/if[big]", "y", "y > 10");
    private static final String SW = meta("S", "demo.A#helper(int)/switch#1", "", "y % 2");

    private int helper(int y) {
        Frame f = Decisions.enter(CALL, y);
        try {
            Decisions.choose(SW, y % 2);
            if (y > 10) {
                Decisions.yes(IF, y);
                return 1;
            } else {
                Decisions.no(IF, y);
                return 0;
            }
        } finally {
            f.exit();
        }
    }

    @Test
    void recordsFramesBranchesAndInputs() {
        Frame f = Decisions.enter(RUN, 3);
        try {
            helper(12);
            helper(3);
        } finally {
            f.exit();
        }
        List<RunRecord> runs = sink.drain();
        assertEquals(1, runs.size());
        RunRecord r = runs.get(0);
        assertEquals("handle-run", r.name());
        assertEquals(7, r.nodes().size());
        Node root = r.roots().get(0);
        assertEquals("demo.A#handle(int)", root.site());
        assertEquals("3", root.inputs().get(0).value());
        List<Node> calls = r.children(root.id());
        assertEquals(2, calls.size());
        List<Node> first = r.children(calls.get(0).id());
        assertEquals("switch", first.get(0).kind());
        assertEquals("0", first.get(0).outcome());
        assertEquals("true", first.get(1).outcome());
        assertEquals("y > 10", first.get(1).text());
        assertEquals("false", r.children(calls.get(1).id()).get(1).outcome());
        assertTrue(root.durationNanos() >= 0);
    }

    @Test
    void autoRunStartsAtOutermostTracedMethod() {
        helper(1);
        List<RunRecord> runs = sink.drain();
        assertEquals(1, runs.size());
        assertEquals("demo.A#helper(int)", runs.get(0).name());
    }

    @Test
    void noRunMeansNoRecording() {
        Decisions.configure(TrailConfig.builder().autoRun(false).sample(1.0).build());
        Decisions.setSink(sink);
        assertEquals(1, helper(20));
        assertFalse(Decisions.active());
        assertEquals(0, sink.drain().size());
    }

    @Test
    void healthyRunsAreDroppedWhenNotSampled() {
        Decisions.configure(TrailConfig.builder().sample(0.0).build());
        Decisions.setSink(sink);
        helper(1);
        assertEquals(0, sink.drain().size());
    }

    @Test
    void failedRunsAreAlwaysKept() {
        Decisions.configure(TrailConfig.builder().sample(0.0).build());
        Decisions.setSink(sink);
        Frame f = Decisions.enter(RUN, 1);
        try {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException e) {
            f.fail(e);
        } finally {
            f.exit();
        }
        List<RunRecord> runs = sink.drain();
        assertEquals(1, runs.size());
        assertEquals("error", runs.get(0).status());
        assertEquals("java.lang.IllegalStateException", runs.get(0).error());
    }

    @Test
    void anomaliesForceKeep() {
        Decisions.configure(TrailConfig.builder().sample(0.0).build());
        Decisions.setSink(sink);
        Frame f = Decisions.enter(RUN, 1);
        try {
            Decisions.anomaly("fell back to cache");
        } finally {
            f.exit();
        }
        List<RunRecord> runs = sink.drain();
        assertEquals(1, runs.size());
        assertEquals(List.of("fell back to cache"), runs.get(0).anomalies());
    }

    @Test
    void wrappedExecutorCarriesTheRunAcrossThreads() throws Exception {
        ExecutorService pool = Decisions.wrap(Executors.newFixedThreadPool(2));
        try {
            Frame f = Decisions.enter(RUN, 1);
            Future<Integer> a;
            Future<Integer> b;
            try {
                a = pool.submit(() -> helper(50));
                b = pool.submit(() -> helper(2));
                a.get();
                b.get();
            } finally {
                f.exit();
            }
        } finally {
            pool.shutdown();
        }
        List<RunRecord> runs = sink.drain();
        assertEquals(1, runs.size());
        RunRecord r = runs.get(0);
        Node root = r.roots().get(0);
        List<Node> calls = r.children(root.id());
        assertEquals(2, calls.size());
        for (Node c : calls) {
            assertTrue(c.async(), "task frames should be marked async");
            assertFalse(c.thread().equals(root.thread()));
        }
    }

    @Test
    void runWaitsForAsyncWorkThatOutlivesTheRoot() throws Exception {
        ExecutorService pool = Decisions.wrap(Executors.newSingleThreadExecutor());
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            Frame f = Decisions.enter(RUN, 1);
            Future<Integer> later;
            try {
                later = pool.submit(() -> {
                    go.await();
                    return helper(99);
                });
            } finally {
                f.exit();
            }
            assertEquals(0, sink.drain().size());
            go.countDown();
            later.get();
        } finally {
            pool.shutdown();
        }
        // The task releases the run just after its future completes, so give it a moment.
        List<RunRecord> runs = sink.drain();
        for (int i = 0; i < 50 && runs.isEmpty(); i++) {
            Thread.sleep(20);
            runs = sink.drain();
        }
        assertEquals(1, runs.size());
        assertEquals(4, runs.get(0).nodes().size());
    }

    @Test
    void redactedValuesNeverReachTheRecord() {
        Frame f = Decisions.enter(meta("R", "demo.A#login(String)", "password", "login"), Decisions.REDACTED);
        f.exit();
        assertEquals("<redacted>", sink.drain().get(0).roots().get(0).inputs().get(0).value());
    }

    @Test
    void nodeLimitCountsDrops() {
        Decisions.configure(TrailConfig.builder().sample(1.0).maxNodes(3).build());
        Decisions.setSink(sink);
        Frame f = Decisions.enter(RUN, 1);
        try {
            for (int i = 0; i < 5; i++) {
                Decisions.yes(IF, i);
            }
        } finally {
            f.exit();
        }
        RunRecord r = sink.drain().get(0);
        assertEquals(3, r.nodes().size());
        assertEquals(3, r.dropped());
    }
}
