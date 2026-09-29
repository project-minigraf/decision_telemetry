package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One recorded event in a run: a method frame or a branch.
 *
 * <p>Node ids are assigned in recording order, so a lower id always happened earlier in the run.
 * Instances are only mutated while the run is live, and are read after the run has ended.
 */
public final class Node {
    /** A captured input: a variable or parameter name and its rendered value. */
    public record Input(String name, String value) {
    }

    final long id;
    final long parent;
    final String kind;
    final String site;
    final String file;
    final int line;
    final String thread;
    final boolean async;
    final long startNanos;
    final String text;
    final List<Input> inputs;
    volatile String outcome;
    volatile long durationNanos = -1;
    volatile String status = "ok";
    volatile String error;

    Node(long id, long parent, String kind, String site, String file, int line, String thread, boolean async,
         long startNanos, String text, String outcome, List<Input> inputs) {
        this.id = id;
        this.parent = parent;
        this.kind = kind;
        this.site = site;
        this.file = file;
        this.line = line;
        this.thread = thread;
        this.async = async;
        this.startNanos = startNanos;
        this.text = text;
        this.outcome = outcome;
        this.inputs = inputs == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(inputs));
    }

    public long id() { return id; }

    /** Id of the enclosing frame, or 0 for the run's root frame. */
    public long parent() { return parent; }

    /** {@code frame}, {@code if}, {@code ternary} or {@code switch}. */
    public String kind() { return kind; }

    /** Stable site id, for example {@code com.acme.Pricing#quote(Order)/if[discount]}. */
    public String site() { return site; }

    /** Source file relative to its source root, for example {@code com/acme/Pricing.java}. */
    public String file() { return file; }

    public int line() { return line; }

    public String thread() { return thread; }

    /** True when this frame started on a different thread from its parent. */
    public boolean async() { return async; }

    /** Start time relative to the run start. */
    public long startNanos() { return startNanos; }

    /** Condition source text for branches, empty for frames. */
    public String text() { return text; }

    /** {@code true}/{@code false} for if and ternary, the selector value for switch, null for frames. */
    public String outcome() { return outcome; }

    public List<Input> inputs() { return inputs; }

    /** Frame duration, or -1 for branches and unfinished frames. */
    public long durationNanos() { return durationNanos; }

    /** {@code ok} or {@code error}. */
    public String status() { return status; }

    /** Exception class that escaped the frame, if any. */
    public String error() { return error; }

    public boolean isFrame() { return Site.FRAME.equals(kind); }

    /** Builds a node when reading a stored run. */
    public static Node restore(long id, long parent, String kind, String site, String file, int line,
                               String thread,
                               boolean async, long startNanos, String text, String outcome,
                               List<Input> inputs, long durationNanos, String status, String error) {
        Node n = new Node(id, parent, kind, site, file, line, thread, async, startNanos, text, outcome, inputs);
        n.durationNanos = durationNanos;
        n.status = status == null ? "ok" : status;
        n.error = error;
        return n;
    }
}
