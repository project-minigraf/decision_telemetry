package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON shape of runs and nodes. The JSONL sink and every {@code --json} CLI output use it,
 * so tools and agents see one shape. See {@code docs/SCHEMA.md}.
 */
public final class RunJson {
    public static final int SCHEMA_VERSION = 1;

    private RunJson() {
    }

    public static Map<String, Object> header(RunRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "run");
        m.put("schema", SCHEMA_VERSION);
        m.put("id", r.id());
        m.put("name", r.name());
        m.put("codeVersion", r.codeVersion());
        m.put("startedAtMillis", r.startedAtMillis());
        m.put("status", r.status());
        m.put("error", r.error());
        m.put("dropped", r.dropped());
        m.put("anomalies", r.anomalies());
        m.put("nodeCount", r.nodes().size());
        return m;
    }

    public static Map<String, Object> node(Node n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "node");
        m.put("id", n.id());
        m.put("parent", n.parent());
        m.put("kind", n.kind());
        m.put("site", n.site());
        m.put("file", n.file());
        m.put("line", n.line());
        m.put("thread", n.thread());
        m.put("async", n.async());
        m.put("startNanos", n.startNanos());
        if (n.isFrame()) {
            m.put("codeIdent", CodeIdents.forNode(n));
            m.put("durationNanos", n.durationNanos());
            m.put("status", n.status());
            m.put("error", n.error());
        } else {
            m.put("text", n.text());
            m.put("outcome", n.outcome());
        }
        List<Object> in = new ArrayList<>();
        for (Node.Input i : n.inputs()) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("name", i.name());
            im.put("value", i.value());
            in.add(im);
        }
        m.put("inputs", in);
        return m;
    }

    /** Reads a run from JSON lines: one header line, then one line per node. */
    @SuppressWarnings("unchecked")
    public static RunRecord fromLines(List<String> lines) {
        Map<String, Object> header = null;
        List<Node> nodes = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) Json.parse(line);
            if ("run".equals(m.get("type"))) {
                header = m;
            } else {
                nodes.add(nodeFrom(m));
            }
        }
        if (header == null) {
            throw new IllegalArgumentException("No run header line found");
        }
        List<String> anomalies = new ArrayList<>();
        Object an = header.get("anomalies");
        if (an instanceof List<?> l) {
            for (Object o : l) {
                anomalies.add(String.valueOf(o));
            }
        }
        return new RunRecord(str(header.get("id")), str(header.get("name")), str(header.get("codeVersion")),
                num(header.get("startedAtMillis")), str(header.get("status")), str(header.get("error")),
                num(header.get("dropped")), anomalies, nodes);
    }

    @SuppressWarnings("unchecked")
    private static Node nodeFrom(Map<String, Object> m) {
        List<Node.Input> inputs = new ArrayList<>();
        Object in = m.get("inputs");
        if (in instanceof List<?> l) {
            for (Object o : l) {
                Map<String, Object> im = (Map<String, Object>) o;
                inputs.add(new Node.Input(str(im.get("name")), str(im.get("value"))));
            }
        }
        Object dur = m.get("durationNanos");
        return Node.restore(num(m.get("id")), num(m.get("parent")), str(m.get("kind")), str(m.get("site")),
                str(m.get("file")), (int) num(m.get("line")), str(m.get("thread")),
                Boolean.TRUE.equals(m.get("async")), num(m.get("startNanos")), strOr(m.get("text"), ""),
                str(m.get("outcome")), inputs, dur == null ? -1 : num(dur), str(m.get("status")),
                str(m.get("error")));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String strOr(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
