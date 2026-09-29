package io.github.adityamukho.trail;

import org.junit.jupiter.api.Test;

import static io.github.adityamukho.trail.TestSupport.node;
import static io.github.adityamukho.trail.TestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunDiffTest {
    @Test
    void identicalRunsHaveNoDivergence() {
        RunRecord a = run("a", node(1, 0, "frame", "A#f()", null), node(2, 1, "if", "A#f()/if#1", "true", "x", "1"));
        RunRecord b = run("b", node(5, 0, "frame", "A#f()", null), node(9, 5, "if", "A#f()/if#1", "true", "x", "1"));
        assertNull(RunDiff.compare(a, b).first());
        assertEquals(0, RunDiff.compare(a, b).divergences().size());
    }

    @Test
    void findsFirstOutcomeChangeAndItsInputs() {
        RunRecord a = run("a",
                node(1, 0, "frame", "A#f()", null),
                node(2, 1, "if", "A#f()/if#1", "true", "x", "1"),
                node(3, 1, "frame", "A#g(int)", null, "n", "5"),
                node(4, 3, "if", "A#g(int)/if[big]", "true", "n", "5"),
                node(5, 1, "if", "A#f()/if#2", "true"));
        RunRecord b = run("b",
                node(1, 0, "frame", "A#f()", null),
                node(2, 1, "if", "A#f()/if#1", "true", "x", "1"),
                node(3, 1, "frame", "A#g(int)", null, "n", "2"),
                node(4, 3, "if", "A#g(int)/if[big]", "false", "n", "2"),
                node(5, 1, "if", "A#f()/if#2", "false"));
        RunDiff.Result r = RunDiff.compare(a, b);
        RunDiff.Divergence first = r.first();
        assertEquals(RunDiff.Kind.OUTCOME_CHANGED, first.kind());
        assertEquals("A#g(int)/if[big]", first.baseline().site());
        assertEquals(java.util.List.of("A#f()", "A#g(int)"), first.path());
        assertEquals("n", first.inputChanges().get(0).name());
        assertEquals(1, r.precedingInputChanges().size());
        assertEquals("A#g(int)", r.precedingInputChanges().get(0).baseline().site());
    }

    @Test
    void loopIterationsPairInOrder() {
        RunRecord a = run("a", node(1, 0, "frame", "A#f()", null),
                node(2, 1, "if", "A#f()/if#1", "true"), node(3, 1, "if", "A#f()/if#1", "false"));
        RunRecord b = run("b", node(1, 0, "frame", "A#f()", null),
                node(2, 1, "if", "A#f()/if#1", "false"), node(3, 1, "if", "A#f()/if#1", "true"));
        assertEquals(2, RunDiff.compare(a, b).divergences().size());
    }

    @Test
    void asyncFramesMatchByInputsWhateverTheirOrder() {
        RunRecord a = run("a", node(1, 0, "frame", "A#f()", null),
                node(2, 1, "frame", "A#task(int)", null, "i", "1"), node(3, 1, "frame", "A#task(int)", null, "i", "2"));
        RunRecord b = run("b", node(1, 0, "frame", "A#f()", null),
                node(2, 1, "frame", "A#task(int)", null, "i", "2"), node(3, 1, "frame", "A#task(int)", null, "i", "1"));
        assertEquals(0, RunDiff.compare(a, b).divergences().size());
    }

    @Test
    void reportsMissingAndExtraCalls() {
        RunRecord a = run("a", node(1, 0, "frame", "A#f()", null), node(2, 1, "frame", "A#retry()", null));
        RunRecord b = run("b", node(1, 0, "frame", "A#f()", null), node(2, 1, "frame", "A#fallback()", null));
        RunDiff.Result r = RunDiff.compare(a, b);
        assertEquals(2, r.divergences().size());
        java.util.Set<RunDiff.Kind> kinds = java.util.EnumSet.noneOf(RunDiff.Kind.class);
        r.divergences().forEach(d -> kinds.add(d.kind()));
        assertEquals(java.util.EnumSet.of(RunDiff.Kind.MISSING, RunDiff.Kind.EXTRA), kinds);
    }

    @Test
    void aFailureIsReportedAfterTheDecisionsInsideIt() {
        RunRecord a = run("a", node(1, 0, "frame", "A#f()", null), node(2, 1, "switch", "A#f()/switch#1", "IN"));
        Node failed = Node.restore(1, 0, "frame", "A#f()", "src/Test.java", 1, "main", false, 1, "", null,
                java.util.List.of(), 10, "error", "java.lang.IllegalArgumentException");
        RunRecord b = run("b", failed, node(2, 1, "switch", "A#f()/switch#1", "XX"));
        RunDiff.Result r = RunDiff.compare(a, b);
        assertEquals(RunDiff.Kind.OUTCOME_CHANGED, r.first().kind());
        assertEquals(RunDiff.Kind.STATUS_CHANGED, r.divergences().get(1).kind());
    }

    @Test
    void whySliceListsPriorDecisionsOnThePath() {
        RunRecord a = run("a",
                node(1, 0, "frame", "A#f()", null),
                node(2, 1, "if", "A#f()/if#1", "true"),
                node(3, 1, "frame", "A#g()", null),
                node(4, 3, "if", "A#g()/if#1", "false"),
                node(5, 1, "if", "A#f()/if#2", "true"));
        WhySlice.Result w = WhySlice.of(a, 4);
        assertEquals(2, w.steps().size());
        assertEquals(1, w.steps().get(0).priorDecisions().size());
        assertEquals("A#f()/if#1", w.steps().get(0).priorDecisions().get(0).site());
        // g made no decision before the target; f's later if#2 is not on the path
        assertEquals(0, w.steps().get(1).priorDecisions().size());
        assertEquals(4, w.target().id());
    }
}
