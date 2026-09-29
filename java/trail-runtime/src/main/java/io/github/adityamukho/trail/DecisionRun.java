package io.github.adityamukho.trail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as the start of a run, such as a request handler or a job.
 *
 * <p>A run collects every decision made while the method executes, including work handed to
 * executors wrapped with {@link Decisions#wrap(java.util.concurrent.ExecutorService)}. When the
 * run ends, the keep policy decides whether to write it to the sink. Failed runs are always kept.
 *
 * <p>A method with this annotation is also traced, as if it had {@link TraceDecisions}.
 * If a run is already active, the method is traced as a normal frame inside that run.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface DecisionRun {
    /** Run name, used to group runs of the same kind. Defaults to the method name. */
    String value() default "";
}
