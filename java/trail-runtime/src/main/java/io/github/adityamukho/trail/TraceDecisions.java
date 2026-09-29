package io.github.adityamukho.trail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Traces the decisions made inside a method, or inside every method of a class.
 *
 * <p>The {@code trail-javac} compiler plugin rewrites each traced method at compile time. It records:
 * <ul>
 *   <li>a <b>frame</b> for the method call, with its arguments as inputs;</li>
 *   <li>a <b>branch</b> for every {@code if}, ternary and {@code switch} in the body, with the
 *       condition's source text, the outcome, and the values of the variables the condition reads.</li>
 * </ul>
 *
 * <p>Give a branch a stable name with a Java label: {@code discount: if (tier == GOLD) ...}.
 * Without a label the site is named by its position in the method (for example {@code if#2}).
 *
 * <p>This annotation does nothing unless the code is compiled with {@code -Xplugin:DecisionTelemetry}.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TraceDecisions {
}
