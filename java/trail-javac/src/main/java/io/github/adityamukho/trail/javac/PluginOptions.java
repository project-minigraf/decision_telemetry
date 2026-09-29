package io.github.adityamukho.trail.javac;

import java.util.ArrayList;
import java.util.List;

/** Parsed {@code -Xplugin:DecisionTelemetry ...} options. */
public final class PluginOptions {
    final List<String> packages;
    final boolean ternary;
    final boolean verbose;

    private PluginOptions(List<String> packages, boolean ternary, boolean verbose) {
        this.packages = packages;
        this.ternary = ternary;
        this.verbose = verbose;
    }

    static PluginOptions parse(String... args) {
        List<String> packages = new ArrayList<>();
        boolean ternary = true;
        boolean verbose = false;
        for (String raw : args) {
            for (String a : raw.trim().split("\\s+")) {
                if (a.isEmpty()) {
                    continue;
                }
                if (a.startsWith("packages=")) {
                    for (String p : a.substring("packages=".length()).split(",")) {
                        if (!p.isBlank()) {
                            packages.add(p.trim());
                        }
                    }
                } else if (a.equals("ternary=false")) {
                    ternary = false;
                } else if (a.equals("ternary=true")) {
                    ternary = true;
                } else if (a.equals("verbose")) {
                    verbose = true;
                } else {
                    throw new IllegalArgumentException(TrailPlugin.NAME + ": unknown option '" + a + "'");
                }
            }
        }
        return new PluginOptions(List.copyOf(packages), ternary, verbose);
    }

    boolean packageSelected(String pkg) {
        for (String p : packages) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }
}
