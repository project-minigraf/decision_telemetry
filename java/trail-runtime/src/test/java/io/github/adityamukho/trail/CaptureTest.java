package io.github.adityamukho.trail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureTest {
    private final TrailConfig cfg = TrailConfig.builder().maxValueChars(5).build();

    enum Color { RED }

    record Secret(String value) {
    }

    @Test
    void rendersStableValues() {
        assertEquals("null", Capture.render(null, cfg));
        assertEquals("42", Capture.render(42, cfg));
        assertEquals("2.5", Capture.render(2.5, cfg));
        assertEquals("true", Capture.render(true, cfg));
        assertEquals("RED", Capture.render(Color.RED, cfg));
        assertEquals("\"abc\"", Capture.render("abc", cfg));
        assertEquals("\"abcde...(7 chars)\"", Capture.render("abcdefg", cfg));
        assertEquals("<int[] len=3>", Capture.render(new int[3], cfg));
        assertEquals("Optional[1]", Capture.render(Optional.of(1), cfg));
        assertEquals("<redacted>", Capture.render(Decisions.REDACTED, cfg));
    }

    @Test
    void objectsAreShownByTypeUnlessAllowed() {
        assertEquals("<Secret>", Capture.render(new Secret("pin"), cfg));
        assertEquals("<ArrayList size=2>", Capture.render(new java.util.ArrayList<>(List.of(1, 2)), cfg));
        assertEquals("<HashMap size=0>", Capture.render(new java.util.HashMap<>(Map.of()), cfg));
        TrailConfig open = TrailConfig.builder().toStringPackages(List.of("io.github.adityamukho")).build();
        assertEquals("Secret[value=pin]", Capture.render(new Secret("pin"), open));
    }
}
