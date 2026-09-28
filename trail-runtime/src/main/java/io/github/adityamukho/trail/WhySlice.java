package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answers "how did the code get here?" for one node.
 *
 * <p>The slice is the chain of frames from the root down to the node. For each frame it lists the
 * decisions that frame made before control moved to the next step on the chain, with their inputs.
 * Those are the decisions that could have sent execution elsewhere.
 */
public final class WhySlice {
    /** Most recent prior decisions kept per frame. */
    static final int MAX_PRIOR = 12;

    public record Step(Node frame, List<Node> priorDecisions, int omitted) {
    }

    public record Result(Node target, List<Step> steps) {
    }

    private WhySlice() {
    }

    public static Result of(RunRecord run, long nodeId) {
        Node target = run.node(nodeId);
        if (target == null) {
            throw new IllegalArgumentException("Run " + run.id() + " has no node " + nodeId);
        }
        List<Node> chain = new ArrayList<>();
        Node n = target;
        while (n != null) {
            chain.add(n);
            n = n.parent() == 0 ? null : run.node(n.parent());
        }
        Collections.reverse(chain);

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            Node frame = chain.get(i);
            if (!frame.isFrame()) {
                continue;
            }
            long before = i + 1 < chain.size() ? chain.get(i + 1).id() : Long.MAX_VALUE;
            List<Node> prior = new ArrayList<>();
            for (Node c : run.children(frame.id())) {
                if (!c.isFrame() && c.id() < before) {
                    prior.add(c);
                }
            }
            int omitted = Math.max(0, prior.size() - MAX_PRIOR);
            steps.add(new Step(frame, List.copyOf(prior.subList(omitted, prior.size())), omitted));
        }
        return new Result(target, steps);
    }
}
