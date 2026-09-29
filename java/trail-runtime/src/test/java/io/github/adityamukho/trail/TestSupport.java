package io.github.adityamukho.trail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Helpers shared by runtime tests. */
final class TestSupport {
    private TestSupport() {
    }

    /** Collects written runs in memory. */
    static final class MemorySink implements Sink {
        final List<RunRecord> runs = new ArrayList<>();

        @Override
        public synchronized void write(RunRecord run) {
            runs.add(run);
        }

        /** Waits for pending writes, then takes what was written. Must not hold the lock while waiting. */
        List<RunRecord> drain() {
            Decisions.flush(Duration.ofSeconds(10));
            synchronized (this) {
                List<RunRecord> out = new ArrayList<>(runs);
                runs.clear();
                return out;
            }
        }
    }

    /** Builds the literal the plugin would embed for a site. */
    static String meta(String kind, String id, String names, String text) {
        return Site.encode(kind, id, "src/Test.java", 7, names.isEmpty() ? new String[0] : names.split(","), text);
    }

    static Node node(long id, long parent, String kind, String site, String outcome, String... inputs) {
        List<Node.Input> in = new ArrayList<>();
        for (int i = 0; i + 1 < inputs.length; i += 2) {
            in.add(new Node.Input(inputs[i], inputs[i + 1]));
        }
        return Node.restore(id, parent, kind, site, "src/Test.java", 1, "main", false, id, "cond", outcome, in,
                kind.equals("frame") ? 10 : -1, "ok", null);
    }

    static RunRecord run(String id, Node... nodes) {
        return new RunRecord(id, "test", "v1", 0, "ok", null, 0, List.of(), List.of(nodes));
    }
}
