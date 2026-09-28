package io.github.adityamukho.trail;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Keeps the value of a parameter or local variable out of the trail.
 *
 * <p>The name is still recorded, so a reader can see that the variable took part in a decision.
 * The value is replaced with {@code <redacted>} at compile time and never reaches the runtime.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.PARAMETER, ElementType.LOCAL_VARIABLE})
public @interface Redact {
}
