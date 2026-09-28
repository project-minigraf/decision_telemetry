package io.github.adityamukho.trail.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.Context;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Rewrites each compilation unit right after it is parsed, before names and types are resolved.
 * Working at that stage means the inserted code is resolved and type-checked by javac like any
 * other code, and javac reports errors in it with normal positions.
 */
public final class InstrumentingListener implements TaskListener {
    private final Context context;
    private final PluginOptions options;
    private final Map<JCCompilationUnit, Boolean> done = new IdentityHashMap<>();
    private final RepoPaths repoPaths = new RepoPaths();

    public InstrumentingListener(JavacTask task, PluginOptions options) {
        this.context = ((BasicJavacTask) task).getContext();
        this.options = options;
    }

    @Override
    public void finished(TaskEvent e) {
        if (e.getKind() != TaskEvent.Kind.PARSE) {
            return;
        }
        JCCompilationUnit cu = (JCCompilationUnit) e.getCompilationUnit();
        if (done.put(cu, Boolean.TRUE) != null) {
            return;
        }
        Instrumenter.Result r = new Instrumenter(context, options, cu, repoPaths.relativePath(cu)).run();
        if (options.verbose && r.methods() > 0) {
            System.err.println(TrailPlugin.NAME + ": " + cu.getSourceFile().getName() + ": " + r.methods()
                    + " methods, " + r.branches() + " branches");
        }
    }
}
