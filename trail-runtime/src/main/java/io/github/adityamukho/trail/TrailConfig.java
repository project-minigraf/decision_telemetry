package io.github.adityamukho.trail;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runtime settings. Read from system properties ({@code trail.*}) or environment variables
 * ({@code TRAIL_*}) at first use, or set in code with {@link Decisions#configure(TrailConfig)}.
 *
 * <table>
 *   <caption>Settings</caption>
 *   <tr><th>Property</th><th>Default</th><th>Meaning</th></tr>
 *   <tr><td>trail.enabled</td><td>true</td><td>Master switch. When false, every call is a no-op.</td></tr>
 *   <tr><td>trail.autoRun</td><td>true</td><td>Start a run at the outermost traced method when no
 *       {@code @DecisionRun} is active.</td></tr>
 *   <tr><td>trail.sample</td><td>0.0</td><td>Fraction of healthy runs to keep. Failed runs and runs
 *       with anomalies are always kept.</td></tr>
 *   <tr><td>trail.sink</td><td>auto</td><td>{@code auto}, {@code minigraf}, {@code jsonl}, {@code both}
 *       or {@code none}. {@code auto} picks Minigraf when {@code minigraf-jvm} is on the classpath.</td></tr>
 *   <tr><td>trail.dir</td><td>./decision-trail</td><td>Where run files are written.</td></tr>
 *   <tr><td>trail.maxNodes</td><td>20000</td><td>Nodes kept per run. Extra nodes are counted, not stored.</td></tr>
 *   <tr><td>trail.maxValueChars</td><td>200</td><td>Longest captured string.</td></tr>
 *   <tr><td>trail.toStringPackages</td><td>(none)</td><td>Comma-separated package prefixes whose
 *       types may be captured with {@code toString()}.</td></tr>
 *   <tr><td>trail.asyncGraceMillis</td><td>5000</td><td>How long a finished run waits for async
 *       work it spawned before it is written anyway.</td></tr>
 *   <tr><td>trail.codeVersion</td><td>(unknown)</td><td>Build or commit id, stored with each run.</td></tr>
 * </table>
 */
public final class TrailConfig {
    private final boolean enabled;
    private final boolean autoRun;
    private final double sample;
    private final String sink;
    private final Path dir;
    private final int maxNodes;
    private final int maxValueChars;
    private final List<String> toStringPackages;
    private final long asyncGraceMillis;
    private final String codeVersion;

    private TrailConfig(Builder b) {
        this.enabled = b.enabled;
        this.autoRun = b.autoRun;
        this.sample = b.sample;
        this.sink = b.sink;
        this.dir = b.dir;
        this.maxNodes = b.maxNodes;
        this.maxValueChars = b.maxValueChars;
        this.toStringPackages = List.copyOf(b.toStringPackages);
        this.asyncGraceMillis = b.asyncGraceMillis;
        this.codeVersion = b.codeVersion;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Reads {@code trail.*} system properties, then {@code TRAIL_*} environment variables. */
    public static TrailConfig fromEnvironment() {
        Builder b = new Builder();
        b.enabled(Boolean.parseBoolean(get("enabled", "true")));
        b.autoRun(Boolean.parseBoolean(get("autoRun", "true")));
        b.sample(Double.parseDouble(get("sample", "0.0")));
        b.sink(get("sink", "auto"));
        b.dir(Path.of(get("dir", "decision-trail")));
        b.maxNodes(Integer.parseInt(get("maxNodes", "20000")));
        b.maxValueChars(Integer.parseInt(get("maxValueChars", "200")));
        String pk = get("toStringPackages", "");
        b.toStringPackages(pk.isBlank() ? List.of() : List.of(pk.trim().split("\\s*,\\s*")));
        b.asyncGraceMillis(Long.parseLong(get("asyncGraceMillis", "5000")));
        b.codeVersion(get("codeVersion", "unknown"));
        return b.build();
    }

    private static String get(String key, String def) {
        String v = System.getProperty("trail." + key);
        if (v == null) {
            String env = "TRAIL_" + key.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
            v = System.getenv(env);
        }
        return v == null ? def : v;
    }

    public boolean enabled() { return enabled; }

    public boolean autoRun() { return autoRun; }

    public double sample() { return sample; }

    public String sink() { return sink; }

    public Path dir() { return dir; }

    public int maxNodes() { return maxNodes; }

    public int maxValueChars() { return maxValueChars; }

    public long asyncGraceMillis() { return asyncGraceMillis; }

    public String codeVersion() { return codeVersion; }

    boolean allowsToString(Class<?> c) {
        if (toStringPackages.isEmpty()) {
            return false;
        }
        String n = c.getName();
        for (String p : toStringPackages) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    public Builder toBuilder() {
        return new Builder().enabled(enabled).autoRun(autoRun).sample(sample).sink(sink).dir(dir)
                .maxNodes(maxNodes).maxValueChars(maxValueChars).toStringPackages(toStringPackages)
                .asyncGraceMillis(asyncGraceMillis).codeVersion(codeVersion);
    }

    public static final class Builder {
        private boolean enabled = true;
        private boolean autoRun = true;
        private double sample = 0.0;
        private String sink = "auto";
        private Path dir = Path.of("decision-trail");
        private int maxNodes = 20_000;
        private int maxValueChars = 200;
        private List<String> toStringPackages = List.of();
        private long asyncGraceMillis = 5000;
        private String codeVersion = "unknown";

        public Builder enabled(boolean v) { enabled = v; return this; }

        public Builder autoRun(boolean v) { autoRun = v; return this; }

        public Builder sample(double v) {
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException("sample must be between 0 and 1");
            }
            sample = v;
            return this;
        }

        public Builder sink(String v) { sink = v.toLowerCase(Locale.ROOT); return this; }

        public Builder dir(Path v) { dir = v; return this; }

        public Builder maxNodes(int v) { maxNodes = v; return this; }

        public Builder maxValueChars(int v) { maxValueChars = v; return this; }

        public Builder toStringPackages(List<String> v) { toStringPackages = v; return this; }

        public Builder asyncGraceMillis(long v) { asyncGraceMillis = v; return this; }

        public Builder codeVersion(String v) { codeVersion = v; return this; }

        public TrailConfig build() { return new TrailConfig(this); }
    }
}
