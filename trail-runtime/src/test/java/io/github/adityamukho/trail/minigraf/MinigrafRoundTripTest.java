package io.github.adityamukho.trail.minigraf;

import io.github.adityamukho.trail.Node;
import io.github.adityamukho.trail.RunDiff;
import io.github.adityamukho.trail.RunRecord;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes a run to a real Minigraf file and reads it back. Runs only when an engine is available:
 * {@code minigraf-jvm} on the test classpath, or the Python bridge selected with
 * {@code -Dtrail.minigraf.engineFactory}.
 */
class MinigrafRoundTripTest {
    @Test
    void writesAndReadsBack() throws Exception {
        if (!Engines.available()) {
            System.out.println("SKIP MinigrafRoundTripTest: no Minigraf engine on this JVM");
            return;
        }
        List<Node> nodes = List.of(
                Node.restore(1, 0, "frame", "A#f(int)", "src/A.java", 3, "main", false, 10, "", null,
                        List.of(new Node.Input("n", "150")), 999, "ok", null),
                Node.restore(2, 1, "if", "A#f(int)/if[big]", "src/A.java", 4, "main", false, 20,
                        "n > 100 && s.equals(\"x\\y\")", "true",
                        List.of(new Node.Input("n", "150"), new Node.Input("s", "\"x\\\\y\"")), -1, "ok", null),
                Node.restore(3, 1, "frame", "A#g()", "src/A.java", 9, "pool-1", true, 30, "", null,
                        List.of(), 5, "error", "java.lang.IllegalStateException"));
        RunRecord run = new RunRecord("11111111-2222", "demo", "abc123", 1_700_000_000_000L, "error",
                "java.lang.IllegalStateException", 2, List.of("slow path"), nodes);
        Path dir = Files.createTempDirectory("trail-mg");
        Path file = dir.resolve("run.graph");
        MinigrafRunStore.write(run, file);
        RunRecord back = MinigrafRunStore.read(file);

        assertEquals(0, RunDiff.compare(run, back).divergences().size());
        assertEquals("abc123", back.codeVersion());
        assertEquals(2, back.dropped());
        assertEquals(List.of("slow path"), back.anomalies());
        assertEquals("n > 100 && s.equals(\"x\\y\")", back.node(2).text());
        assertTrue(back.node(3).async());
        assertEquals("java.lang.IllegalStateException", back.node(3).error());

        try (DatalogEngine db = Engines.open(file.toString())) {
            MinigrafRunStore.defineRules(db);
            List<List<Object>> rows = MinigrafRunStore.rows(db,
                    "(query [:find ?site :where [?t :node/id 2] (ancestor ?a ?t) [?a :node/site ?site]])");
            assertEquals("A#f(int)", rows.get(0).get(0));
            rows = MinigrafRunStore.rows(db, "(query [:find ?nid :where [?i :input/name \"n\"] [?i :input/num ?v]"
                    + " [(> ?v 100)] [?i :input/node-id ?nid]])");
            assertEquals(2, rows.size());
        }
    }
}
