package io.github.adityamukho.trail.minigraf;

import io.github.adityamukho.trail.CodeIdents;
import io.github.adityamukho.trail.Json;
import io.github.adityamukho.trail.Node;
import io.github.adityamukho.trail.RunRecord;
import io.github.adityamukho.trail.RunJson;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stores one run per Minigraf {@code .graph} file, and reads it back.
 *
 * <p>The schema is documented in {@code docs/SCHEMA.md}. It is the contract for any other
 * instrumenter or reader, so change it only with a schema version bump.
 */
public final class MinigrafRunStore {
    /** Facts per transaction. Small batches keep each transaction well inside Minigraf's limits. */
    static final int BATCH = 100;
    /** Longest string stored in one fact. File-backed Minigraf caps a fact at 4080 bytes. */
    static final int MAX_STRING = 1000;

    /**
     * Rules loaded before any ad hoc query. Rules are not persisted in the file, so each
     * session defines them again.
     */
    public static final List<String> RULES = List.of(
            "(rule [(ancestor ?a ?d) [?d :node/parent ?a]])",
            "(rule [(ancestor ?a ?d) [?d :node/parent ?m] (ancestor ?a ?m)])",
            "(rule [(decision ?b) [?b :node/kind \"if\"]])",
            "(rule [(decision ?b) [?b :node/kind \"ternary\"]])",
            "(rule [(decision ?b) [?b :node/kind \"switch\"]])");

    private MinigrafRunStore() {
    }

    // ---------------------------------------------------------------- write

    public static void write(RunRecord run, Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.deleteIfExists(file);
        try (DatalogEngine db = Engines.open(file.toString())) {
            List<String> facts = facts(run);
            for (int i = 0; i < facts.size(); i += BATCH) {
                List<String> chunk = facts.subList(i, Math.min(facts.size(), i + BATCH));
                String result = db.execute("(transact [" + String.join(" ", chunk) + "])");
                checkOk(result);
            }
            db.checkpoint();
        }
    }

    static List<String> facts(RunRecord run) {
        List<String> f = new ArrayList<>();
        fact(f, ":run", ":run/schema", RunJson.SCHEMA_VERSION);
        fact(f, ":run", ":run/id", run.id());
        fact(f, ":run", ":run/name", run.name());
        fact(f, ":run", ":run/code-version", run.codeVersion());
        fact(f, ":run", ":run/started-at-ms", run.startedAtMillis());
        fact(f, ":run", ":run/status", run.status());
        fact(f, ":run", ":run/error", run.error());
        fact(f, ":run", ":run/dropped", run.dropped());
        for (int i = 0; i < run.anomalies().size(); i++) {
            String a = ":anomaly-" + i;
            fact(f, a, ":anomaly/idx", i);
            fact(f, a, ":anomaly/text", run.anomalies().get(i));
        }
        for (Node n : run.nodes()) {
            String e = ":n-" + n.id();
            fact(f, e, ":node/id", n.id());
            fact(f, e, ":node/parent-id", n.parent());
            if (n.parent() != 0) {
                f.add("[" + e + " :node/parent :n-" + n.parent() + "]");
            }
            fact(f, e, ":node/kind", n.kind());
            fact(f, e, ":node/site", n.site());
            fact(f, e, ":node/file", n.file());
            fact(f, e, ":node/line", n.line());
            fact(f, e, ":node/thread", n.thread());
            fact(f, e, ":node/async", n.async());
            fact(f, e, ":node/start-ns", n.startNanos());
            if (n.isFrame()) {
                fact(f, e, ":node/code-ident", CodeIdents.forNode(n));
                fact(f, e, ":node/duration-ns", n.durationNanos());
                fact(f, e, ":node/status", n.status());
                fact(f, e, ":node/error", n.error());
            } else {
                fact(f, e, ":node/text", n.text());
                fact(f, e, ":node/outcome", n.outcome());
            }
            List<Node.Input> in = n.inputs();
            for (int k = 0; k < in.size(); k++) {
                String ie = ":i-" + n.id() + "-" + k;
                f.add("[" + ie + " :input/node " + e + "]");
                fact(f, ie, ":input/node-id", n.id());
                fact(f, ie, ":input/idx", k);
                fact(f, ie, ":input/name", in.get(k).name());
                fact(f, ie, ":input/value", in.get(k).value());
                String num = numeric(in.get(k).value());
                if (num != null) {
                    f.add("[" + ie + " :input/num " + num + "]");
                }
            }
        }
        return f;
    }

    private static void fact(List<String> f, String e, String a, Object v) {
        if (v == null) {
            return;
        }
        f.add("[" + e + " " + a + " " + edn(v) + "]");
    }

    static String edn(Object v) {
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            return v.toString();
        }
        return string(v.toString());
    }

    static String string(String s) {
        if (s.length() > MAX_STRING) {
            s = s.substring(0, MAX_STRING) + "...";
        }
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c < 0x20 ? ' ' : c);
            }
        }
        return b.append('"').toString();
    }

    /** The EDN number for a captured value, or null when it is not a plain number. */
    static String numeric(String value) {
        if (value == null || value.isEmpty() || value.length() > 40) {
            return null;
        }
        char c = value.charAt(0);
        if (!(c == '-' || (c >= '0' && c <= '9'))) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(value);
            if (d.scale() <= 0) {
                return d.toBigIntegerExact().bitLength() < 64 ? d.toBigIntegerExact().toString() : null;
            }
            String plain = d.toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static void checkOk(String json) {
        Object o = Json.parse(json);
        if (o instanceof Map<?, ?> m && m.containsKey("error")) {
            throw new IllegalStateException("Minigraf error: " + m.get("error"));
        }
    }

    // ---------------------------------------------------------------- read

    public static RunRecord read(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new IOException("No such file: " + file);
        }
        try (DatalogEngine db = Engines.open(file.toString())) {
            return read(db);
        }
    }

    static RunRecord read(DatalogEngine db) {
        Map<String, Object> run = new HashMap<>();
        for (List<Object> row : rows(db, "(query [:find ?a ?v :where [:run ?a ?v]])")) {
            run.put(String.valueOf(row.get(0)), row.get(1));
        }
        TreeMap<Long, String> anomalies = new TreeMap<>();
        for (List<Object> row : rows(db, "(query [:find ?i ?t :where [?a :anomaly/idx ?i] [?a :anomaly/text ?t]])")) {
            anomalies.put(num(row.get(0)), String.valueOf(row.get(1)));
        }

        Map<Long, Map<String, Object>> nodes = new TreeMap<>();
        for (String attr : List.of("parent-id", "kind", "site", "file", "line", "thread", "async", "start-ns",
                "duration-ns", "status", "error", "text", "outcome")) {
            String q = "(query [:find ?id ?v :where [?n :node/id ?id] [?n :node/" + attr + " ?v]])";
            for (List<Object> row : rows(db, q)) {
                nodes.computeIfAbsent(num(row.get(0)), k -> new HashMap<>()).put(attr, row.get(1));
            }
        }
        Map<Long, TreeMap<Long, Node.Input>> inputs = new HashMap<>();
        String iq = "(query [:find ?nid ?idx ?name ?val :where [?i :input/node-id ?nid] [?i :input/idx ?idx]"
                + " [?i :input/name ?name] [?i :input/value ?val]])";
        for (List<Object> row : rows(db, iq)) {
            inputs.computeIfAbsent(num(row.get(0)), k -> new TreeMap<>())
                    .put(num(row.get(1)), new Node.Input(String.valueOf(row.get(2)), String.valueOf(row.get(3))));
        }

        List<Node> list = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> e : nodes.entrySet()) {
            Map<String, Object> m = e.getValue();
            TreeMap<Long, Node.Input> in = inputs.get(e.getKey());
            Object dur = m.get("duration-ns");
            list.add(Node.restore(e.getKey(), num(m.get("parent-id")), str(m.get("kind")), str(m.get("site")),
                    str(m.get("file")), (int) num(m.get("line")), str(m.get("thread")),
                    Boolean.TRUE.equals(m.get("async")), num(m.get("start-ns")),
                    m.get("text") == null ? "" : str(m.get("text")), str(m.get("outcome")),
                    in == null ? List.of() : new ArrayList<>(in.values()), dur == null ? -1 : num(dur),
                    str(m.get("status")), str(m.get("error"))));
        }
        return new RunRecord(str(run.get(":run/id")), str(run.get(":run/name")), str(run.get(":run/code-version")),
                num(run.get(":run/started-at-ms")), str(run.get(":run/status")), str(run.get(":run/error")),
                num(run.get(":run/dropped")), new ArrayList<>(anomalies.values()), list);
    }

    /** Runs a query and returns its result rows. */
    @SuppressWarnings("unchecked")
    public static List<List<Object>> rows(DatalogEngine db, String query) {
        Object o = Json.parse(db.execute(query));
        if (!(o instanceof Map<?, ?> m)) {
            throw new IllegalStateException("Unexpected Minigraf result: " + o);
        }
        if (m.containsKey("error")) {
            throw new IllegalStateException("Minigraf error: " + m.get("error"));
        }
        Object r = m.get("results");
        return r == null ? List.of() : (List<List<Object>>) r;
    }

    /** Loads {@link #RULES} into an open database. */
    public static void defineRules(DatalogEngine db) {
        for (String r : RULES) {
            checkOk(db.execute(r));
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
