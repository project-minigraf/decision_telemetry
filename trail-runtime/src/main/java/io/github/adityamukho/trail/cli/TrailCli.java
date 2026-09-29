package io.github.adityamukho.trail.cli;

import io.github.adityamukho.trail.Json;
import io.github.adityamukho.trail.RunDiff;
import io.github.adityamukho.trail.RunFiles;
import io.github.adityamukho.trail.RunJson;
import io.github.adityamukho.trail.RunRecord;
import io.github.adityamukho.trail.Views;
import io.github.adityamukho.trail.WhySlice;
import io.github.adityamukho.trail.minigraf.DatalogEngine;
import io.github.adityamukho.trail.minigraf.Engines;
import io.github.adityamukho.trail.minigraf.MinigrafRunStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Command line for reading decision trails. Every command takes {@code --json} for a stable,
 * machine-readable result, so scripts and agents can use the same commands people do.
 */
public final class TrailCli {
    private static final String USAGE = String.join("\n",
            "usage: trail <command> [args] [--json]",
            "",
            "  runs <dir>                        list stored runs, newest first",
            "  show <run-file> [--depth N]       print a run as a call tree with its decisions",
            "  diff <baseline> <candidate> [--all]",
            "                                    find where two runs of the same path diverged",
            "  why <run-file> <node-id>          show the decisions that led to a node",
            "  query <run.graph> <datalog>       run Datalog on a stored run (rules: ancestor, decision)",
            "",
            "Run files are .graph (Minigraf, needs minigraf-jvm) or .jsonl.");

    private TrailCli() {
    }

    public static void main(String[] args) {
        int code;
        try {
            code = run(args, System.out, System.err);
        } catch (IllegalArgumentException | IOException | IllegalStateException e) {
            System.err.println("trail: " + e.getMessage());
            code = 2;
        }
        System.exit(code);
    }

    static int run(String[] argv, PrintStream out, PrintStream err) throws IOException {
        List<String> args = new ArrayList<>(List.of(argv));
        boolean json = args.remove("--json");
        boolean all = args.remove("--all");
        int depth = Integer.MAX_VALUE;
        int d = args.indexOf("--depth");
        if (d >= 0) {
            if (d + 1 >= args.size()) {
                throw new IllegalArgumentException("--depth needs a number");
            }
            depth = Integer.parseInt(args.get(d + 1));
            args.remove(d + 1);
            args.remove(d);
        }
        if (args.isEmpty()) {
            err.println(USAGE);
            return 2;
        }
        String cmd = args.remove(0);
        switch (cmd) {
            case "runs":
                need(args, 1);
                return runs(Path.of(args.get(0)), json, out);
            case "show": {
                need(args, 1);
                RunRecord r = RunFiles.load(Path.of(args.get(0)));
                out.println(json ? Json.write(Views.showJson(r)) : Views.showText(r, depth));
                return 0;
            }
            case "diff": {
                need(args, 2);
                RunDiff.Result res = RunDiff.compare(RunFiles.load(Path.of(args.get(0))),
                        RunFiles.load(Path.of(args.get(1))));
                out.println(json ? Json.write(Views.diffJson(res)) : Views.diffText(res, all));
                return res.first() == null ? 0 : 1;
            }
            case "why": {
                need(args, 2);
                RunRecord r = RunFiles.load(Path.of(args.get(0)));
                WhySlice.Result w = WhySlice.of(r, Long.parseLong(args.get(1)));
                out.println(json ? Json.write(Views.whyJson(w)) : Views.whyText(w));
                return 0;
            }
            case "query": {
                need(args, 2);
                try (DatalogEngine db = Engines.open(args.get(0))) {
                    MinigrafRunStore.defineRules(db);
                    out.println(db.execute(args.get(1)));
                }
                return 0;
            }
            case "help":
            case "--help":
            case "-h":
                out.println(USAGE);
                return 0;
            default:
                err.println("trail: unknown command '" + cmd + "'\n" + USAGE);
                return 2;
        }
    }

    private static void need(List<String> args, int n) {
        if (args.size() < n) {
            throw new IllegalArgumentException("missing arguments\n" + USAGE);
        }
    }

    private static int runs(Path dir, boolean json, PrintStream out) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.toString().endsWith(".graph") || p.toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
        List<Object> rows = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (Path f : files) {
            RunRecord r;
            try {
                r = RunFiles.load(f);
            } catch (IOException | RuntimeException e) {
                text.append(f.getFileName()).append("  (unreadable: ").append(e.getMessage()).append(")\n");
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(RunJson.header(r));
            row.put("file", f.toString());
            rows.add(row);
            text.append(String.format("%-7s %-30s %s  %s%n", r.status(), r.name(), f.getFileName(),
                    r.anomalies().isEmpty() ? "" : "anomalies: " + r.anomalies()));
        }
        out.print(json ? Json.write(rows) + "\n" : text);
        return 0;
    }
}
