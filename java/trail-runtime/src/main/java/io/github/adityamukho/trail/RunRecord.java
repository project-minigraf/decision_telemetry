package io.github.adityamukho.trail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A finished run: its header and all its nodes, ordered by id.
 */
public final class RunRecord {
    private final String id;
    private final String name;
    private final String codeVersion;
    private final long startedAtMillis;
    private final String status;
    private final String error;
    private final long dropped;
    private final List<String> anomalies;
    private final List<Node> nodes;
    private final Map<Long, Node> byId = new HashMap<>();
    private final Map<Long, List<Node>> children = new HashMap<>();

    public RunRecord(String id, String name, String codeVersion, long startedAtMillis, String status,
                     String error, long dropped, List<String> anomalies, List<Node> nodes) {
        this.id = id;
        this.name = name;
        this.codeVersion = codeVersion;
        this.startedAtMillis = startedAtMillis;
        this.status = status;
        this.error = error;
        this.dropped = dropped;
        this.anomalies = List.copyOf(anomalies);
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparingLong(Node::id));
        this.nodes = Collections.unmodifiableList(sorted);
        for (Node n : sorted) {
            byId.put(n.id(), n);
            children.computeIfAbsent(n.parent(), k -> new ArrayList<>()).add(n);
        }
    }

    public String id() { return id; }

    public String name() { return name; }

    public String codeVersion() { return codeVersion; }

    public long startedAtMillis() { return startedAtMillis; }

    /** {@code ok} or {@code error}. */
    public String status() { return status; }

    public String error() { return error; }

    /** Nodes not recorded because the run hit the node limit. */
    public long dropped() { return dropped; }

    public List<String> anomalies() { return anomalies; }

    public List<Node> nodes() { return nodes; }

    public Node node(long id) { return byId.get(id); }

    /** Direct children of a node, ordered by id. Pass 0 for the root frames. */
    public List<Node> children(long parentId) {
        return children.getOrDefault(parentId, List.of());
    }

    public List<Node> roots() { return children(0); }
}
