package io.github.adityamukho.trail;

import java.util.Locale;

/**
 * Code entity idents that match the ones
 * <a href="https://github.com/project-minigraf/temporal_reasoning">temporal_reasoning</a> mints
 * when it ingests a repository's history.
 *
 * <p>This must stay byte-for-byte equal to {@code _canonical_ident} and {@code _code_ident} in
 * temporal_reasoning's {@code mcp_server.py}: lowercase, every character outside
 * {@code [a-z0-9_-]} becomes {@code -}, no collapsing of repeated hyphens, then leading and
 * trailing hyphens are stripped. With equal idents, a trail can be joined to the commits and
 * agent sessions that last changed the code.
 */
public final class CodeIdents {
    private CodeIdents() {
    }

    /** {@code :function/<slug of "path::name">}, the same as temporal_reasoning's {@code _code_ident}. */
    public static String function(String repoRelativePath, String name) {
        return canonical("function", repoRelativePath + "::" + name);
    }

    /** The code entity ident of a frame node, or null for branches and unknown files. */
    public static String forNode(Node n) {
        if (!n.isFrame() || n.file() == null || n.file().isEmpty()) {
            return null;
        }
        String name = methodName(n.site());
        return name == null ? null : function(n.file(), name);
    }

    /** {@code com.acme.Pricing#quote(Order)} gives {@code quote}. */
    static String methodName(String site) {
        int hash = site.indexOf('#');
        if (hash < 0) {
            return null;
        }
        int end = site.indexOf('(', hash);
        int slash = site.indexOf('/', hash);
        if (end < 0 || (slash >= 0 && slash < end)) {
            end = slash >= 0 ? slash : site.length();
        }
        return site.substring(hash + 1, end);
    }

    static String canonical(String type, String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            b.append(ok ? c : '-');
        }
        int s = 0;
        int e = b.length();
        while (s < e && b.charAt(s) == '-') {
            s++;
        }
        while (e > s && b.charAt(e - 1) == '-') {
            e--;
        }
        return ":" + type + "/" + b.substring(s, e);
    }
}
