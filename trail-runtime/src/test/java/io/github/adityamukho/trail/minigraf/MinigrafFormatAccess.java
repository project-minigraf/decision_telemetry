package io.github.adityamukho.trail.minigraf;

import io.github.adityamukho.trail.RunRecord;

import java.util.List;

/** Exposes package-private formatting helpers to tests in other packages. */
public final class MinigrafFormatAccess {
    private MinigrafFormatAccess() {
    }

    public static String string(String s) {
        return MinigrafRunStore.string(s);
    }

    public static String numeric(String s) {
        return MinigrafRunStore.numeric(s);
    }

    public static List<String> facts(RunRecord r) {
        return MinigrafRunStore.facts(r);
    }
}
