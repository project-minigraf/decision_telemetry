package io.github.adityamukho.trail.demo;

import io.github.adityamukho.trail.DecisionRun;
import io.github.adityamukho.trail.Decisions;
import io.github.adityamukho.trail.TraceDecisions;
import io.github.adityamukho.trail.demo.Model.FraudSuspected;
import io.github.adityamukho.trail.demo.Model.Order;
import io.github.adityamukho.trail.demo.Model.Receipt;
import io.github.adityamukho.trail.demo.Model.Tier;
import io.github.adityamukho.trail.demo.Services.CustomerCache;
import io.github.adityamukho.trail.demo.Services.Fraud;
import io.github.adityamukho.trail.demo.Services.Loyalty;
import io.github.adityamukho.trail.demo.Services.Pricing;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A checkout that fans work out to a thread pool.
 *
 * <p>The planted bug: the loyalty refresh warms the customer cache in the background, and the
 * checkout reads the tier after a fixed amount of other work. When the loyalty system is slow, the
 * read wins the race, sees a cold cache, and the customer silently loses their discount.
 */
final class Checkout implements AutoCloseable {
    final CustomerCache cache = new CustomerCache();
    private final Loyalty loyalty = new Loyalty(cache);
    private final Fraud fraud = new Fraud();
    private final Pricing pricing = new Pricing();
    // Wrapping the pool carries the current run into every task.
    private final ExecutorService pool = Decisions.wrap(Executors.newFixedThreadPool(4));

    @DecisionRun("checkout")
    Receipt handle(Order order, int loyaltyLatencyMillis) throws Exception {
        Future<?> warm = pool.submit(() -> {
            loyalty.refresh(order.customerId(), loyaltyLatencyMillis);
            return null;
        });
        Future<Integer> risk = pool.submit(() -> fraud.score(order));

        long subtotal = pricing.subtotal(order);
        Thread.sleep(25); // other work: address checks, stock reservation...
        Tier tier = cache.tierOf(order.customerId());
        long discount = pricing.discount(tier, subtotal);
        long shipping = pricing.shipping(order.country(), subtotal - discount);

        int score = risk.get();
        blocked: if (score >= 70) {
            throw new FraudSuspected(order.id(), score);
        }
        String route = route(score);
        warm.get();
        return new Receipt(order.id(), subtotal, discount, shipping, route);
    }

    @TraceDecisions
    String route(int score) {
        return score >= 40 ? "manual-review" : "auto";
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
