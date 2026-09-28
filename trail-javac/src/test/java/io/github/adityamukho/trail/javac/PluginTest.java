package io.github.adityamukho.trail.javac;

import io.github.adityamukho.trail.Decisions;
import io.github.adityamukho.trail.Node;
import io.github.adityamukho.trail.RunRecord;
import io.github.adityamukho.trail.Sink;
import io.github.adityamukho.trail.TrailConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compiles test sources with and without the plugin, checks that behaviour is unchanged, and
 * checks what was recorded.
 */
class PluginTest {
    private final List<RunRecord> runs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Decisions.configure(TrailConfig.builder().sample(1.0).build());
        Decisions.setSink(new Sink() {
            @Override
            public void write(RunRecord run) {
                synchronized (runs) {
                    runs.add(run);
                }
            }
        });
    }

    @AfterEach
    void tearDown() {
        // Let this test's runs land in this test's sink before the next test installs its own.
        Decisions.flush(Duration.ofSeconds(10));
        Decisions.setSink(null);
    }

    @Test
    void instrumentedCodeBehavesLikePlainCode() throws Exception {
        String plain = runCase("demo.Torture", false);
        runs.clear();
        String traced = runCase("demo.Torture", true);
        assertEquals(plain, traced);
    }

    @Test
    void recordsTheExpectedDecisions() throws Exception {
        runCase("demo.Torture", true);
        Decisions.flush(Duration.ofSeconds(10));
        assertEquals(1, runs.size());
        RunRecord r = runs.get(0);
        assertEquals("torture", r.name());

        Node patternsIf = find(r, "demo.Torture#patterns(Object)/if#1", "\"hello\"");
        assertEquals("!(o instanceof String s)", patternsIf.text());
        assertEquals("false", patternsIf.outcome());
        assertEquals(List.of("o"), names(patternsIf));

        Node discount = find(r, "demo.Torture#tier(Tier,int,String)/if[discount]", "GOLD");
        assertEquals("true", discount.outcome());
        assertEquals("<redacted>", discount.inputs().get(2).value());

        Node sw = find(r, "demo.Torture#area(Shape)/switch#1", null);
        assertEquals("sh", sw.text());

        Node objectsIf = find(r, "demo.Torture#tier(Tier,int,String)/if#2", null);
        assertEquals(List.of("email", "total"), names(objectsIf));

        Node ternary = find(r, "demo.Torture#tier(Tier,int,String)/ternary#4", null);
        assertEquals("total > MAX", ternary.text());

        Node size = find(r, "demo.Torture$Circle#size()/ternary#1", null);
        assertEquals("true", size.outcome());

        Node greet = find(r, "demo.Torture$Greeter#greet(String)/if#1", null);
        assertEquals("true", greet.outcome());

        Node fails = firstFrame(r, "demo.Torture#fails(int)");
        assertEquals("error", fails.status());
        assertEquals("java.lang.IllegalStateException", fails.error());

        for (Node n : r.nodes()) {
            assertTrue(!n.site().contains("<init>"), "constructors must not be traced: " + n.site());
            assertTrue(!n.site().contains("$1#run"), "anonymous classes must not be traced: " + n.site());
        }
        assertEquals("ok", r.status());
    }

    @Test
    void packageModeTracesUnannotatedCode() throws Exception {
        Path src = Files.createTempDirectory("trail-src").resolve("plain/Calc.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, String.join("\n",
                "package plain;",
                "public class Calc {",
                "  public static int abs(int x) { if (x < 0) { return -x; } return x; }",
                "  public static int id(int x) { return x; }",
                "  public static String run() { return \"\" + abs(-4) + id(2); }",
                "}"));
        ClassLoader cl = compile(List.of(src), true, "packages=plain");
        assertEquals("42", cl.loadClass("plain.Calc").getMethod("run").invoke(null));
        Decisions.flush(Duration.ofSeconds(10));
        RunRecord r = runs.get(runs.size() - 1);
        assertEquals("plain.Calc#abs(int)", r.name());
        assertEquals(2, r.nodes().size());
        for (RunRecord x : runs) {
            assertTrue(!x.name().contains("#id("), "methods without decisions are skipped in package mode");
        }
    }

    // ---------------------------------------------------------------- helpers

    private String runCase(String className, boolean instrument) throws Exception {
        Path src = copyCase(className);
        ClassLoader cl = compile(List.of(src), instrument);
        return (String) cl.loadClass(className).getMethod("run").invoke(null);
    }

    private static Path copyCase(String className) throws Exception {
        String rel = className.replace('.', '/') + ".java";
        Path dir = Files.createTempDirectory("trail-case");
        Path out = dir.resolve(rel);
        Files.createDirectories(out.getParent());
        try (InputStream in = PluginTest.class.getResourceAsStream("/cases/" + rel)) {
            assertNotNull(in, "missing test case " + rel);
            Files.write(out, in.readAllBytes());
        }
        return out;
    }

    private static ClassLoader compile(List<Path> sources, boolean instrument, String... pluginArgs)
            throws Exception {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path out = Files.createTempDirectory("trail-out");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        List<String> opts = new ArrayList<>(List.of("-d", out.toString(), "-classpath", location(Decisions.class),
                "-proc:none"));
        if (instrument) {
            opts.remove("-proc:none");
            opts.addAll(List.of("-processorpath", location(TrailPlugin.class)));
            opts.add("-Xplugin:" + TrailPlugin.NAME + (pluginArgs.length == 0 ? "" : " " + String.join(" ", pluginArgs)));
        }
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sources);
            Boolean ok = javac.getTask(null, fm, diags, opts, null, units).call();
            if (!ok) {
                fail("compilation failed:\n" + diags.getDiagnostics().stream()
                        .map(Object::toString).collect(Collectors.joining("\n")));
            }
        }
        return new URLClassLoader(new URL[] {out.toUri().toURL()}, PluginTest.class.getClassLoader());
    }

    private static String location(Class<?> c) throws URISyntaxException {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Node find(RunRecord r, String site, String firstInput) {
        for (Node n : r.nodes()) {
            if (n.site().equals(site)
                    && (firstInput == null || (!n.inputs().isEmpty() && n.inputs().get(0).value().equals(firstInput)))) {
                return n;
            }
        }
        fail("no node " + site + " with first input " + firstInput + " in\n"
                + r.nodes().stream().map(Node::site).distinct().collect(Collectors.joining("\n")));
        return null;
    }

    private static Node firstFrame(RunRecord r, String site) {
        return find(r, site, null);
    }

    private static List<String> names(Node n) {
        return n.inputs().stream().map(Node.Input::name).toList();
    }
}
