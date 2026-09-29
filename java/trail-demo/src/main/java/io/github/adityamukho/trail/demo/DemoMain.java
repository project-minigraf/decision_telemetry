package io.github.adityamukho.trail.demo;

import io.github.adityamukho.trail.Decisions;
import io.github.adityamukho.trail.RunDiff;
import io.github.adityamukho.trail.RunFiles;
import io.github.adityamukho.trail.RunRecord;
import io.github.adityamukho.trail.TrailConfig;
import io.github.adityamukho.trail.Views;
import io.github.adityamukho.trail.demo.Model.Line;
import io.github.adityamukho.trail.demo.Model.Order;
import io.github.adityamukho.trail.demo.Model.Receipt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Runs the same checkout twice: once with a fast loyalty system, once with a slow one. The totals
 * differ. Then it diffs the two runs to show where and why.
 */
public final class DemoMain {
    private DemoMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "decision-telemetry-demo");
        Decisions.configure(Decisions.config().toBuilder().dir(dir).sample(1.0).codeVersion("demo").build());

        Order order = new Order("o-1001", "cust-42", "IN",
                List.of(new Line("book", 2, 4_500), new Line("pen", 5, 900)));

        long startedAt = System.currentTimeMillis();
        Receipt fast;
        Receipt slow;
        try (Checkout c = new Checkout()) {
            fast = c.handle(order, 5);
            c.cache.clear();
            slow = c.handle(order, 80);
            c.cache.clear();
            try {
                c.handle(new Order("o-1002", "cust-7", "XX", List.of(new Line("lamp", 1, 3_000))), 5);
            } catch (IllegalArgumentException expected) {
                System.out.println("third checkout failed as planted: " + expected.getMessage());
            }
        }
        System.out.println("fast loyalty: total " + fast.totalCents() + " (discount " + fast.discountCents() + ")");
        System.out.println("slow loyalty: total " + slow.totalCents() + " (discount " + slow.discountCents() + ")");

        if (!Decisions.flush(Duration.ofSeconds(30))) {
            throw new IllegalStateException("runs were not written in time");
        }
        List<Path> runs;
        try (Stream<Path> s = Files.list(dir)) {
            runs = s.filter(p -> p.getFileName().toString().contains("-checkout-"))
                    .filter(p -> p.toString().endsWith(".graph") || p.toString().endsWith(".jsonl"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        System.out.println("\nstored runs:");
        runs.forEach(p -> System.out.println("  " + p));
        // File names only go down to the second, so order this invocation's runs by start time.
        // The first two are the fast and slow checkouts.
        String ext = runs.get(0).toString().endsWith(".graph") ? ".graph" : ".jsonl";
        List<RunRecord> ours = new ArrayList<>();
        Map<RunRecord, Path> files = new IdentityHashMap<>();
        for (Path p : runs) {
            if (p.toString().endsWith(ext)) {
                RunRecord r = RunFiles.load(p);
                if (r.startedAtMillis() >= startedAt) {
                    ours.add(r);
                    files.put(r, p);
                }
            }
        }
        ours.sort(Comparator.comparingLong(RunRecord::startedAtMillis));
        RunRecord baseline = ours.get(0);
        RunRecord candidate = ours.get(1);
        System.out.println("\n$ trail diff " + files.get(baseline).getFileName() + " "
                + files.get(candidate).getFileName() + "\n");
        System.out.println(Views.diffText(RunDiff.compare(baseline, candidate), false));
    }
}
