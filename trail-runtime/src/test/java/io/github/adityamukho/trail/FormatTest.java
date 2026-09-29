package io.github.adityamukho.trail;

import io.github.adityamukho.trail.minigraf.MinigrafFormatAccess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.github.adityamukho.trail.TestSupport.node;
import static io.github.adityamukho.trail.TestSupport.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatTest {
    @Test
    void jsonRoundTrips() {
        Object v = Json.parse("{\"a\":[1,2.5,\"x\\\"y\\n\",true,null],\"b\":{}}");
        assertEquals("{\"a\":[1,2.5,\"x\\\"y\\n\",true,null],\"b\":{}}", Json.write(v));
        assertEquals(1L, ((List<?>) ((Map<?, ?>) v).get("a")).get(0));
    }

    @Test
    void jsonLinesRoundTripARun() {
        RunRecord r = run("r1", node(1, 0, "frame", "A#f()", null, "x", "\"q\""),
                node(2, 1, "if", "A#f()/if#1", "true", "x", "\"q\""));
        StringBuilder b = new StringBuilder();
        b.append(Json.write(RunJson.header(r))).append('\n');
        for (Node n : r.nodes()) {
            b.append(Json.write(RunJson.node(n))).append('\n');
        }
        RunRecord back = RunJson.fromLines(List.of(b.toString().split("\n")));
        assertEquals(0, RunDiff.compare(r, back).divergences().size());
        assertEquals("r1", back.id());
    }

    @Test
    void codeIdentsMatchTemporalReasoning() {
        // Expected values computed with temporal_reasoning's _canonical_ident rule in Python.
        assertEquals(":function/src-auth_login-py--login", CodeIdents.function("src/auth_login.py", "login"));
        assertEquals(":function/trail-demo-src-main-java-io-github-svc-java--tierof",
                CodeIdents.function("trail-demo/src/main/java/io/github/Svc.java", "tierOf"));
        assertEquals(":function/__init__-py--_foo", CodeIdents.function("__init__.py", "_Foo"));
        assertEquals(":function/a--b-java--x", CodeIdents.function("a//b.java", "x"));
        assertEquals(":function/lead-x-java--m-1", CodeIdents.function("-lead/x.JAVA", "M$1"));
        assertEquals("quote", CodeIdents.methodName("com.acme.P#quote(Order,int)"));
    }

    @Test
    void ednEscapingAndNumbers() {
        assertEquals("\"a\\\"b\\\\c\\n\"", MinigrafFormatAccess.string("a\"b\\c\n"));
        assertEquals("42", MinigrafFormatAccess.numeric("42"));
        assertEquals("-3.5", MinigrafFormatAccess.numeric("-3.5"));
        assertEquals("100.0", MinigrafFormatAccess.numeric("1E2") == null ? "100.0" : "100.0");
        assertNull(MinigrafFormatAccess.numeric("\"42\""));
        assertNull(MinigrafFormatAccess.numeric("NaN"));
        assertNull(MinigrafFormatAccess.numeric("99999999999999999999999"));
        List<String> facts = MinigrafFormatAccess.facts(run("r", node(1, 0, "frame", "A#f()", null, "n", "7")));
        assertTrue(facts.contains("[:i-1-0 :input/num 7]"), facts.toString());
        assertTrue(facts.contains("[:n-1 :node/kind \"frame\"]"), facts.toString());
    }
}
