package io.github.adityamukho.trail;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Renders runs, diffs and slices, as text for people and as JSON for tools and agents.
 * The JSON shapes are part of the public contract; see {@code docs/SCHEMA.md}.
 */
public final class Views {
    private Views() {
    }

    // ---------------------------------------------------------------- run tree

    public static String showText(RunRecord r, int maxDepth) {
        StringBuilder b = new StringBuilder();
        b.append(header(r)).append('\n');
        for (Node root : r.roots()) {
            tree(b, r, root, 0, maxDepth, null);
        }
        List<Node> orphans = new ArrayList<>();
        for (Node n : r.nodes()) {
            if (n.parent() != 0 && r.node(n.parent()) == null) {
                orphans.add(n);
            }
        }
        if (!orphans.isEmpty()) {
            b.append("(").append(orphans.size()).append(" nodes whose parent was dropped)\n");
        }
        return b.toString();
    }

    private static void tree(StringBuilder b, RunRecord r, Node n, int depth, int maxDepth, String parentThread) {
        indent(b, depth);
        b.append(line(n));
        if (parentThread != null && !parentThread.equals(n.thread())) {
            b.append("  [thread ").append(n.thread()).append(']');
        }
        b.append('\n');
        if (!n.isFrame()) {
            return;
        }
        List<Node> kids = r.children(n.id());
        if (depth + 1 > maxDepth && !kids.isEmpty()) {
            indent(b, depth + 1);
            b.append("... ").append(kids.size()).append(" more\n");
            return;
        }
        for (Node c : kids) {
            tree(b, r, c, depth + 1, maxDepth, n.thread());
        }
    }

    static String header(RunRecord r) {
        StringBuilder b = new StringBuilder();
        b.append("run ").append(r.name()).append("  ").append(r.id()).append('\n');
        b.append("  status ").append(r.status());
        if (r.error() != null) {
            b.append(" (").append(r.error()).append(')');
        }
        b.append("  started ").append(Instant.ofEpochMilli(r.startedAtMillis()));
        b.append("  code ").append(r.codeVersion()).append("  nodes ").append(r.nodes().size());
        if (r.dropped() > 0) {
            b.append("  dropped ").append(r.dropped());
        }
        for (String a : r.anomalies()) {
            b.append("\n  anomaly: ").append(a);
        }
        return b.toString();
    }

    /** One line for a node, such as {@code #12 if[discount] (tier == GOLD) -> true {tier=GOLD}}. */
    public static String line(Node n) {
        StringBuilder b = new StringBuilder();
        b.append('#').append(n.id()).append(' ');
        if (n.isFrame()) {
            b.append(n.site());
            b.append(inputs(n));
            if (n.durationNanos() >= 0) {
                b.append("  ").append(String.format("%.3f ms", n.durationNanos() / 1e6));
            }
            if ("error".equals(n.status())) {
                b.append("  !! threw ").append(n.error());
            }
        } else {
            String site = n.site();
            int slash = site.lastIndexOf('/');
            b.append(slash >= 0 ? site.substring(slash + 1) : site);
            b.append(" (").append(n.text()).append(") -> ").append(n.outcome());
            b.append(inputs(n));
        }
        if (n.line() > 0) {
            b.append("  @").append(n.file()).append(':').append(n.line());
        }
        return b.toString();
    }

    private static String inputs(Node n) {
        if (n.inputs().isEmpty()) {
            return "";
        }
        StringJoiner j = new StringJoiner(", ", " {", "}");
        for (Node.Input i : n.inputs()) {
            j.add(i.name() + "=" + i.value());
        }
        return j.toString();
    }

    private static void indent(StringBuilder b, int depth) {
        b.append("  ".repeat(depth));
    }

    public static Map<String, Object> showJson(RunRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run", RunJson.header(r));
        List<Object> nodes = new ArrayList<>();
        for (Node n : r.nodes()) {
            nodes.add(RunJson.node(n));
        }
        m.put("nodes", nodes);
        return m;
    }

    // ---------------------------------------------------------------- diff

    public static String diffText(RunDiff.Result d, boolean all) {
        StringBuilder b = new StringBuilder();
        b.append("baseline  ").append(d.baseline().name()).append("  ").append(d.baseline().id())
                .append("  status ").append(d.baseline().status()).append("  code ").append(d.baseline().codeVersion())
                .append('\n');
        b.append("candidate ").append(d.candidate().name()).append("  ").append(d.candidate().id())
                .append("  status ").append(d.candidate().status()).append("  code ").append(d.candidate().codeVersion())
                .append('\n');
        if (!d.baseline().codeVersion().equals(d.candidate().codeVersion())) {
            b.append("note: the runs come from different code versions\n");
        }
        RunDiff.Divergence first = d.first();
        if (first == null) {
            b.append("\nNo behavioural divergence. Every decision went the same way.\n");
        } else {
            b.append("\nFirst divergence: ").append(describe(first)).append('\n');
            b.append("  call path:\n");
            for (String s : first.path()) {
                b.append("    ").append(s).append('\n');
            }
            appendDetail(b, first, "  ");
            List<RunDiff.Divergence> causes = d.precedingInputChanges();
            if (!causes.isEmpty()) {
                b.append("\nInput changes before it (possible causes):\n");
                for (RunDiff.Divergence c : causes) {
                    b.append("  ").append(Views.line(c.baseline())).append('\n');
                    appendInputChanges(b, c, "    ");
                }
            }
        }
        long behavioural = d.divergences().stream().filter(x -> x.kind().behavioural()).count();
        b.append('\n').append(behavioural).append(" behavioural divergences, ")
                .append(d.divergences().size() - behavioural).append(" input-only changes\n");
        if (all) {
            b.append("\nAll differences:\n");
            for (RunDiff.Divergence x : d.divergences()) {
                b.append("  ").append(describe(x)).append('\n');
                appendDetail(b, x, "    ");
            }
        }
        return b.toString();
    }

    private static String describe(RunDiff.Divergence d) {
        Node n = d.node();
        String what = n.isFrame() ? "call " + n.site() : n.kind() + " " + n.site();
        switch (d.kind()) {
            case OUTCOME_CHANGED:
                return what + " went " + d.baseline().outcome() + " in baseline, " + d.candidate().outcome()
                        + " in candidate";
            case STATUS_CHANGED:
                return what + " was " + d.baseline().status() + " in baseline, " + d.candidate().status()
                        + (d.candidate().error() != null ? " (" + d.candidate().error() + ")" : "") + " in candidate";
            case MISSING:
                return what + " happened in baseline only";
            case EXTRA:
                return what + " happened in candidate only";
            default:
                return what + " saw different inputs";
        }
    }

    private static void appendDetail(StringBuilder b, RunDiff.Divergence d, String pad) {
        if (d.baseline() != null) {
            b.append(pad).append("baseline:  ").append(line(d.baseline())).append('\n');
        }
        if (d.candidate() != null) {
            b.append(pad).append("candidate: ").append(line(d.candidate())).append('\n');
        }
        appendInputChanges(b, d, pad);
    }

    private static void appendInputChanges(StringBuilder b, RunDiff.Divergence d, String pad) {
        for (RunDiff.InputChange c : d.inputChanges()) {
            b.append(pad).append("changed ").append(c.name()).append(": ").append(c.baseline()).append(" -> ")
                    .append(c.candidate()).append('\n');
        }
    }

    public static Map<String, Object> diffJson(RunDiff.Result d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("baseline", RunJson.header(d.baseline()));
        m.put("candidate", RunJson.header(d.candidate()));
        RunDiff.Divergence first = d.first();
        m.put("first", first == null ? null : divergenceJson(first));
        List<Object> causes = new ArrayList<>();
        for (RunDiff.Divergence c : d.precedingInputChanges()) {
            causes.add(divergenceJson(c));
        }
        m.put("precedingInputChanges", causes);
        List<Object> all = new ArrayList<>();
        for (RunDiff.Divergence x : d.divergences()) {
            all.add(divergenceJson(x));
        }
        m.put("divergences", all);
        return m;
    }

    private static Map<String, Object> divergenceJson(RunDiff.Divergence d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", d.kind().name());
        m.put("summary", describe(d));
        m.put("path", d.path());
        m.put("baseline", d.baseline() == null ? null : RunJson.node(d.baseline()));
        m.put("candidate", d.candidate() == null ? null : RunJson.node(d.candidate()));
        List<Object> changes = new ArrayList<>();
        for (RunDiff.InputChange c : d.inputChanges()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("name", c.name());
            cm.put("baseline", c.baseline());
            cm.put("candidate", c.candidate());
            changes.add(cm);
        }
        m.put("inputChanges", changes);
        return m;
    }

    // ---------------------------------------------------------------- why

    public static String whyText(WhySlice.Result w) {
        StringBuilder b = new StringBuilder();
        b.append("Why did the run reach ").append(line(w.target())).append("?\n\n");
        int depth = 0;
        for (WhySlice.Step s : w.steps()) {
            indent(b, depth);
            b.append(line(s.frame())).append('\n');
            if (s.omitted() > 0) {
                indent(b, depth + 1);
                b.append("(").append(s.omitted()).append(" earlier decisions)\n");
            }
            for (Node d : s.priorDecisions()) {
                indent(b, depth + 1);
                b.append(line(d)).append('\n');
            }
            depth++;
        }
        return b.toString();
    }

    public static Map<String, Object> whyJson(WhySlice.Result w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", RunJson.node(w.target()));
        List<Object> steps = new ArrayList<>();
        for (WhySlice.Step s : w.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("frame", RunJson.node(s.frame()));
            List<Object> prior = new ArrayList<>();
            for (Node d : s.priorDecisions()) {
                prior.add(RunJson.node(d));
            }
            sm.put("priorDecisions", prior);
            sm.put("omitted", s.omitted());
            steps.add(sm);
        }
        m.put("steps", steps);
        return m;
    }
}
