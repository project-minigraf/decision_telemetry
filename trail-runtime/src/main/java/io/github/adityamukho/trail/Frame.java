package io.github.adityamukho.trail;

/**
 * One traced method call. Generated code holds a frame in a local variable and closes it in a
 * {@code finally} block. Application code does not use this class directly.
 */
public final class Frame {
    static final Frame NOOP = new Frame(null, null, null, false, 0, null);

    final Run run;
    final Node node;
    /** The frame to restore as current when this one exits. */
    final Frame previous;
    final boolean root;
    final long id;
    final String threadName;

    Frame(Run run, Node node, Frame previous, boolean root, long id, String threadName) {
        this.run = run;
        this.node = node;
        this.previous = previous;
        this.root = root;
        this.id = id;
        this.threadName = threadName;
    }

    /** Records that an exception escaped the method. The exception keeps propagating. */
    public void fail(Throwable t) {
        if (run == null) {
            return;
        }
        node.status = "error";
        node.error = t.getClass().getName();
        run.frameFailed();
        if (root) {
            run.rootFailed(t);
        }
    }

    /** Ends the frame. Always called, from a {@code finally} block. */
    public void exit() {
        if (run == null) {
            return;
        }
        node.durationNanos = run.relNanos() - node.startNanos;
        Decisions.restore(previous);
        if (root) {
            run.rootExited();
        }
    }
}
