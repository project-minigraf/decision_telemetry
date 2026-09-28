package io.github.adityamukho.trail;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Static facts about one instrumented place in the code.
 *
 * <p>The compiler plugin packs these into one string literal per site, so generated code passes a
 * single constant. The runtime parses each literal once and caches the result.
 */
final class Site {
    static final char SEP = '\u0001';

    /** Node kinds, as written to the trail. */
    static final String FRAME = "frame";
    static final String IF = "if";
    static final String TERNARY = "ternary";
    static final String SWITCH = "switch";

    private static final ConcurrentHashMap<String, Site> CACHE = new ConcurrentHashMap<>();

    final String kind;
    final boolean runBoundary;
    final String id;
    /** Source file, relative to the source root, for example {@code com/acme/Pricing.java}. */
    final String file;
    final int line;
    final String[] names;
    /** Condition text for branches; run name for run boundaries; empty otherwise. */
    final String text;

    private Site(String kind, boolean runBoundary, String id, String file, int line, String[] names,
                 String text) {
        this.kind = kind;
        this.runBoundary = runBoundary;
        this.id = id;
        this.file = file;
        this.line = line;
        this.names = names;
        this.text = text;
    }

    static Site of(String meta) {
        Site s = CACHE.get(meta);
        if (s == null) {
            s = parse(meta);
            Site prev = CACHE.putIfAbsent(meta, s);
            if (prev != null) {
                s = prev;
            }
        }
        return s;
    }

    /** Builds the literal the plugin embeds. Kept here so the format lives in one place. */
    static String encode(String kindCode, String id, String file, int line, String[] names, String text) {
        StringBuilder b = new StringBuilder();
        b.append(kindCode).append(SEP).append(id).append(SEP).append(file).append(SEP).append(line).append(SEP);
        b.append(String.join(",", names)).append(SEP).append(text == null ? "" : text);
        return b.toString();
    }

    private static Site parse(String meta) {
        String[] p = new String[6];
        int start = 0;
        for (int i = 0; i < 5; i++) {
            int end = meta.indexOf(SEP, start);
            if (end < 0) {
                throw new IllegalArgumentException("Malformed decision-trail site: " + meta);
            }
            p[i] = meta.substring(start, end);
            start = end + 1;
        }
        p[5] = meta.substring(start);
        String kind;
        boolean run = false;
        switch (p[0]) {
            case "R": kind = FRAME; run = true; break;
            case "F": kind = FRAME; break;
            case "I": kind = IF; break;
            case "T": kind = TERNARY; break;
            case "S": kind = SWITCH; break;
            default: throw new IllegalArgumentException("Unknown site kind: " + p[0]);
        }
        int line;
        try {
            line = Integer.parseInt(p[3]);
        } catch (NumberFormatException e) {
            line = -1;
        }
        String[] names = p[4].isEmpty() ? new String[0] : p[4].split(",");
        return new Site(kind, run, p[1], p[2], line, names, p[5]);
    }
}
