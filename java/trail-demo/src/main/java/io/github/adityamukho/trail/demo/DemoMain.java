package io.github.adityamukho.trail.demo;

import io.github.adityamukho.trail.Decisions;
import io.github.adityamukho.trail.RunDiff;
import io.github.adityamukho.trail.RunFiles;
import io.github.adityamukho.trail.TrailConfig;
import io.github.adityamukho.trail.Views;
import io.github.adityamukho.trail.demo.Model.Line;
import io.github.adityamukho.trail.demo.Model.Order;
import io.github.adityamukho.trail.demo.Model.Receipt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
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
        String ext = runs.get(0).toString().endsWith(".graph") ? ".graph" : ".jsonl";
        List<Path> same = runs.stream().filter(p -> p.toString().endsWith(ext)).toList();
        System.out.println("\n$ trail diff " + same.get(0).getFileName() + " " + same.get(1).getFileName() + "\n");
        System.out.println(Views.diffText(RunDiff.compare(RunFiles.load(same.get(0)), RunFiles.load(same.get(1))), false));
    }
}
