package io.github.adityamukho.trail;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a live value into a short, stable string.
 *
 * <p>Values are rendered at the moment of the decision, so later mutation does not change the
 * record. Rendering must be stable across runs, or diffs fill with noise. So objects are shown by
 * type and size, never by identity hash. Calling {@code toString()} on your own types is opt-in,
 * because it can leak personal data or be slow.
 */
final class Capture {
    private Capture() {
    }

    static String render(Object v, TrailConfig cfg) {
        if (v == null) {
            return "null";
        }
        if (v == Decisions.REDACTED) {
            return "<redacted>";
        }
        if (v instanceof Boolean || v instanceof Number || v instanceof Character) {
            return v.toString();
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof CharSequence s) {
            return quote(truncate(s.toString(), cfg.maxValueChars()));
        }
        if (v instanceof Optional<?> o) {
            return o.map(x -> "Optional[" + render(x, cfg) + "]").orElse("Optional.empty");
        }
        Class<?> c = v.getClass();
        if (c.isArray()) {
            return "<" + c.getComponentType().getSimpleName() + "[] len=" + Array.getLength(v) + ">";
        }
        if (v instanceof Collection<?> col) {
            return "<" + c.getSimpleName() + " size=" + col.size() + ">";
        }
        if (v instanceof Map<?, ?> m) {
            return "<" + c.getSimpleName() + " size=" + m.size() + ">";
        }
        if (cfg.allowsToString(c)) {
            try {
                return truncate(String.valueOf(v), cfg.maxValueChars());
            } catch (RuntimeException e) {
                return "<" + c.getSimpleName() + " toString failed>";
            }
        }
        return "<" + c.getSimpleName() + ">";
    }

    private static String truncate(String s, int max) {
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(" + s.length() + " chars)";
    }

    private static String quote(String s) {
        return '"' + s + '"';
    }
}
