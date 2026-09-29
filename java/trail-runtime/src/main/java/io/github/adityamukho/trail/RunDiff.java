package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares two runs of the same code path and finds where they part ways.
 *
 * <p>Nodes are matched by position in the call tree, not by id or time, because ids and timing
 * differ between runs. Under each matched frame, children are grouped by site. Branches pair up
 * in the order they happened, since order inside a frame matters (loop iterations, retries).
 * Frames pair up by equal inputs first and then in order, so async tasks that finish in a
 * different order still match.
 */
public final class RunDiff {
    public enum Kind {
        /** A branch went the other way. */
        OUTCOME_CHANGED,
        /** A frame failed in one run and not the other. */
        STATUS_CHANGED,
        /** The baseline made a decision or call that the candidate did not. */
        MISSING,
        /** The candidate made a decision or call that the baseline did not. */
        EXTRA,
        /** Same outcome, different input values. Often the cause of a later divergence. */
        INPUTS_CHANGED;

        boolean behavioural() {
            return this != INPUTS_CHANGED;
        }
    }

    public record InputChange(String name, String baseline, String candidate) {
    }

    /**
     * One difference. {@code path} is the chain of frame sites from the root down to the node.
     * {@code order} places the divergence in the baseline's timeline.
     */
    public record Divergence(Kind kind, Node baseline, Node candidate, List<String> path,
                             List<InputChange> inputChanges, double order) {
        public Node node() {
            return baseline != null ? baseline : candidate;
        }
    }

    public record Result(RunRecord baseline, RunRecord candidate, List<Divergence> divergences) {
        /** The earliest divergence that changed behaviour, or null when the runs behaved the same. */
        public Divergence first() {
            for (Divergence d : divergences) {
                if (d.kind().behavioural()) {
                    return d;
                }
            }
            return null;
        }

        /** Input changes recorded before the first behavioural divergence: likely causes. */
        public List<Divergence> precedingInputChanges() {
            Divergence f = first();
            List<Divergence> out = new ArrayList<>();
            for (Divergence d : divergences) {
                if (d.kind() == Kind.INPUTS_CHANGED && (f == null || d.order() <= f.order())) {
                    out.add(d);
                }
            }
            return out;
        }
    }

    private final List<Divergence> out = new ArrayList<>();
    private final RunRecord a;
    private final RunRecord b;

    private RunDiff(RunRecord a, RunRecord b) {
        this.a = a;
        this.b = b;
    }

    public static Result compare(RunRecord baseline, RunRecord candidate) {
        RunDiff d = new RunDiff(baseline, candidate);
        d.align(baseline.roots(), candidate.roots(), List.of(), 0);
        d.out.sort(Comparator.comparingDouble(Divergence::order));
        return new Result(baseline, candidate, List.copyOf(d.out));
    }

    private void align(List<Node> as, List<Node> bs, List<String> path, double parentOrder) {
        Map<String, List<Node>> ga = group(as);
        Map<String, List<Node>> gb = group(bs);
        List<String> keys = new ArrayList<>(ga.keySet());
        for (String k : gb.keySet()) {
            if (!ga.containsKey(k)) {
                keys.add(k);
            }
        }
        for (String key : keys) {
            List<Node> la = ga.getOrDefault(key, List.of());
            List<Node> lb = gb.getOrDefault(key, List.of());
            boolean frames = key.startsWith(Site.FRAME + "|");
            List<Node[]> pairs = frames ? pairFrames(la, lb) : pairInOrder(la, lb);
            for (Node[] p : pairs) {
                if (p[0] == null) {
                    out.add(new Divergence(Kind.EXTRA, null, p[1], path, List.of(), extraOrder(p[1], la, parentOrder)));
                } else if (p[1] == null) {
                    out.add(new Divergence(Kind.MISSING, p[0], null, path, List.of(), p[0].id()));
                } else {
                    compareNodes(p[0], p[1], path);
                }
            }
        }
    }

    /** Places an extra candidate node just after its nearest baseline sibling of the same site. */
    private static double extraOrder(Node extra, List<Node> baselineSiblings, double parentOrder) {
        if (baselineSiblings.isEmpty()) {
            return parentOrder + 0.5;
        }
        return baselineSiblings.get(baselineSiblings.size() - 1).id() + 0.5;
    }

    private void compareNodes(Node x, Node y, List<String> path) {
        List<InputChange> changes = inputChanges(x, y);
        if (x.isFrame()) {
            if (!Objects.equals(x.status(), y.status())) {
                // A frame fails because of what happened inside it, so order it after its subtree.
                out.add(new Divergence(Kind.STATUS_CHANGED, x, y, path, changes, lastIdUnder(a, x) + 0.25));
            } else if (!changes.isEmpty()) {
                out.add(new Divergence(Kind.INPUTS_CHANGED, x, y, path, changes, x.id()));
            }
            List<String> sub = new ArrayList<>(path);
            sub.add(x.site());
            align(a.children(x.id()), b.children(y.id()), List.copyOf(sub), x.id());
        } else if (!Objects.equals(x.outcome(), y.outcome())) {
            out.add(new Divergence(Kind.OUTCOME_CHANGED, x, y, path, changes, x.id()));
        } else if (!changes.isEmpty()) {
            out.add(new Divergence(Kind.INPUTS_CHANGED, x, y, path, changes, x.id()));
        }
    }

    private static long lastIdUnder(RunRecord r, Node n) {
        long max = n.id();
        for (Node c : r.children(n.id())) {
            max = Math.max(max, c.isFrame() ? lastIdUnder(r, c) : c.id());
        }
        return max;
    }

    private static List<InputChange> inputChanges(Node x, Node y) {
        Map<String, String> mx = inputs(x);
        Map<String, String> my = inputs(y);
        List<InputChange> changes = new ArrayList<>();
        for (Map.Entry<String, String> e : mx.entrySet()) {
            String other = my.get(e.getKey());
            if (!Objects.equals(e.getValue(), other)) {
                changes.add(new InputChange(e.getKey(), e.getValue(), other));
            }
        }
        for (Map.Entry<String, String> e : my.entrySet()) {
            if (!mx.containsKey(e.getKey())) {
                changes.add(new InputChange(e.getKey(), null, e.getValue()));
            }
        }
        return changes;
    }

    private static Map<String, String> inputs(Node n) {
        Map<String, String> m = new LinkedHashMap<>();
        for (Node.Input i : n.inputs()) {
            m.put(i.name(), i.value());
        }
        return m;
    }

    private static Map<String, List<Node>> group(List<Node> nodes) {
        Map<String, List<Node>> g = new LinkedHashMap<>();
        for (Node n : nodes) {
            g.computeIfAbsent(n.kind() + "|" + n.site(), k -> new ArrayList<>()).add(n);
        }
        return g;
    }

    private static List<Node[]> pairInOrder(List<Node> la, List<Node> lb) {
        List<Node[]> pairs = new ArrayList<>();
        int n = Math.max(la.size(), lb.size());
        for (int i = 0; i < n; i++) {
            pairs.add(new Node[] {i < la.size() ? la.get(i) : null, i < lb.size() ? lb.get(i) : null});
        }
        return pairs;
    }

    private static List<Node[]> pairFrames(List<Node> la, List<Node> lb) {
        Node[] matchOf = new Node[la.size()];
        boolean[] used = new boolean[lb.size()];
        for (int i = 0; i < la.size(); i++) {
            for (int j = 0; j < lb.size(); j++) {
                if (!used[j] && la.get(i).inputs().equals(lb.get(j).inputs())) {
                    matchOf[i] = lb.get(j);
                    used[j] = true;
                    break;
                }
            }
        }
        int j = 0;
        for (int i = 0; i < la.size(); i++) {
            if (matchOf[i] != null) {
                continue;
            }
            while (j < lb.size() && used[j]) {
                j++;
            }
            if (j < lb.size()) {
                matchOf[i] = lb.get(j);
                used[j] = true;
            }
        }
        List<Node[]> pairs = new ArrayList<>();
        for (int i = 0; i < la.size(); i++) {
            pairs.add(new Node[] {la.get(i), matchOf[i]});
        }
        for (int k = 0; k < lb.size(); k++) {
            if (!used[k]) {
                pairs.add(new Node[] {null, lb.get(k)});
            }
        }
        return pairs;
    }
}
