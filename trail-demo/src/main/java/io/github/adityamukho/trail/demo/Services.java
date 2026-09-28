package io.github.adityamukho.trail.demo;

import io.github.adityamukho.trail.TraceDecisions;
import io.github.adityamukho.trail.demo.Model.Line;
import io.github.adityamukho.trail.demo.Model.Order;
import io.github.adityamukho.trail.demo.Model.Tier;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The services a checkout calls. Written in the style of code nobody on the team knows well:
 * small methods, each with a few decisions.
 */
final class Services {
    private Services() {
    }

    /** Shared, eventually consistent customer data. The source of the planted bug. */
    static final class CustomerCache {
        private final Map<String, Tier> tiers = new ConcurrentHashMap<>();

        @TraceDecisions
        Tier tierOf(String customerId) {
            Tier t = tiers.get(customerId);
            return t != null ? t : Tier.BASIC;
        }

        void put(String customerId, Tier tier) {
            tiers.put(customerId, tier);
        }

        void clear() {
            tiers.clear();
        }
    }

    static final class Loyalty {
        private final CustomerCache cache;

        Loyalty(CustomerCache cache) {
            this.cache = cache;
        }

        /** Looks the customer up in a slow loyalty system, then warms the cache. */
        @TraceDecisions
        void refresh(String customerId, int latencyMillis) throws InterruptedException {
            Thread.sleep(latencyMillis);
            int points = Math.abs(customerId.hashCode()) % 5000;
            gold: if (points >= 2000) {
                cache.put(customerId, Tier.GOLD);
            } else if (points >= 500) {
                cache.put(customerId, Tier.SILVER);
            }
        }
    }

    static final class Fraud {
        @TraceDecisions
        int score(Order order) {
            int score = 10;
            foreign: if (!order.country().equals("IN")) {
                score += 25;
            }
            for (Line l : order.lines()) {
                bulk: if (l.qty() > 20) {
                    score += 30;
                }
            }
            return score;
        }
    }

    static final class Pricing {
        @TraceDecisions
        long subtotal(Order order) {
            long sum = 0;
            for (Line l : order.lines()) {
                sum += l.qty() * l.unitCents();
            }
            return sum;
        }

        @TraceDecisions
        long discount(Tier tier, long subtotal) {
            gold: if (tier == Tier.GOLD && subtotal >= 10_000) {
                return subtotal / 10;
            }
            silver: if (tier == Tier.SILVER) {
                return subtotal / 20;
            }
            return 0;
        }

        @TraceDecisions
        long shipping(String country, long subtotal) {
            long base = switch (country) {
                case "IN" -> 0;
                case "US", "GB" -> 1500;
                default -> throw new IllegalArgumentException("no shipping to " + country);
            };
            freeShipping: if (subtotal > 50_000) {
                return 0;
            }
            return base;
        }
    }
}
