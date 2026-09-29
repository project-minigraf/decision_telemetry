package io.github.adityamukho.trail.demo;

import java.util.List;

/** Plain data for the demo shop. */
final class Model {
    private Model() {
    }

    enum Tier { BASIC, SILVER, GOLD }

    record Line(String sku, int qty, long unitCents) {
    }

    record Order(String id, String customerId, String country, List<Line> lines) {
    }

    record Receipt(String orderId, long subtotalCents, long discountCents, long shippingCents, String route) {
        long totalCents() {
            return subtotalCents - discountCents + shippingCents;
        }
    }

    static final class FraudSuspected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FraudSuspected(String orderId, int score) {
            super("order " + orderId + " scored " + score);
        }
    }
}
