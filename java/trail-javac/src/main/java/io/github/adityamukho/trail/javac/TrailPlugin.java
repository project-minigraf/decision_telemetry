package io.github.adityamukho.trail.javac;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskListener;

/**
 * javac plugin that inserts decision tracing into traced methods.
 *
 * <p>Enable it with {@code -Xplugin:DecisionTelemetry}, with this jar on the processor path.
 * Options follow the plugin name, separated by spaces:
 * <ul>
 *   <li>{@code packages=com.acme,org.foo}: trace every method that makes a decision in these
 *       packages, without annotations. Useful for code nobody wants to edit.</li>
 *   <li>{@code ternary=false}: do not trace ternary expressions.</li>
 *   <li>{@code verbose}: print a line per instrumented file.</li>
 * </ul>
 *
 * <p>The plugin rewrites javac's syntax trees, which are internal to the JDK. It opens the
 * packages it needs at startup. If your build already passes {@code --add-exports} for
 * {@code jdk.compiler}, that step is skipped.
 */
public final class TrailPlugin implements Plugin {
    static final String NAME = "DecisionTelemetry";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        PluginOptions options = PluginOptions.parse(args);
        ModuleOpener.openCompilerInternals();
        try {
            // Loaded by name so that no javac internal class is linked before the packages are open.
            Class<?> c = Class.forName("io.github.adityamukho.trail.javac.InstrumentingListener");
            TaskListener listener = (TaskListener) c.getConstructor(JavacTask.class, PluginOptions.class)
                    .newInstance(task, options);
            task.addTaskListener(listener);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(NAME + ": could not start", e);
        }
    }
}
