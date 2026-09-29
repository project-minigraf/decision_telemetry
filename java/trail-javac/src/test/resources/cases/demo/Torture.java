package demo;

import io.github.adityamukho.trail.DecisionRun;
import io.github.adityamukho.trail.Redact;
import io.github.adityamukho.trail.TraceDecisions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Java constructs the plugin must rewrite without changing what the program does. */
@TraceDecisions
public class Torture {
    enum Tier { GOLD, SILVER }

    sealed interface Shape permits Circle, Square {
    }

    record Circle(double r) implements Shape {
        @TraceDecisions
        String size() {
            return r > 1 ? "big" : "small";
        }
    }

    record Square(double s) implements Shape {
    }

    interface Greeter {
        @TraceDecisions
        default String greet(String name) {
            if (name == null) {
                return "hi";
            }
            return "hi " + name;
        }
    }

    static final int MAX = 3;
    int count = 2;

    Torture() {
        if (count > 0) {
            count++;
        }
    }

    String patterns(Object o) {
        if (!(o instanceof String s)) {
            return "not string";
        }
        if (o instanceof String t && t.length() > 2) {
            return t.toUpperCase();
        }
        return s;
    }

    int definite(int x) {
        final int y;
        if (x > 0) y = 1; else y = 2;
        int z;
        if (x > 5 && (z = x * 2) > 11) {
            return z + y;
        }
        return y;
    }

    double area(Shape sh) {
        return switch (sh) {
            case Circle c -> Math.PI * c.r() * c.r();
            case Square q -> q.s() * q.s();
        };
    }

    String tier(Tier t, int total, @Redact String email) {
        discount: if (t == Tier.GOLD && total > 100 && !email.isEmpty()) {
            return "big";
        }
        switch (t) {
            case GOLD:
                return "gold";
            default:
                break;
        }
        int code = total % 3;
        String r = switch (code) { case 0 -> "zero"; case 1 -> "one"; default -> "many"; };
        char ch = total > 5 ? 'a' : 'b';
        short sh = total > 5 ? (short) 1 : 2;
        Function<Integer, Integer> f = total > 5 ? x -> x + 1 : x -> x - 1;
        String label = total > MAX ? r : "small";
        Integer boxed = total > 1 ? Integer.valueOf(total) : null;
        if (java.util.Objects.isNull(email) || UUID.randomUUID() == null || Math.max(total, 1) < 0) {
            return "x";
        }
        if (this.count > 1 && count < 10) {
            label = label + ch + sh + f.apply(1) + boxed;
        }
        return label;
    }

    String lambdas(List<Integer> xs) {
        StringBuilder b = new StringBuilder();
        xs.forEach(x -> {
            if (x > 1) {
                b.append(x);
            }
        });
        Runnable anon = new Runnable() {
            @Override
            public void run() {
                if (count > 0) {
                    count--;
                }
            }
        };
        anon.run();
        if (true) {
            count++;
        }
        String s = null;
        if (s == null) s = "a";
        else if (s.isEmpty()) s = "b";
        else s = "c";
        found: if (b.length() > 0) {
            if (count > 100) {
                break found;
            }
            b.append('!');
        }
        return b + s;
    }

    <T extends Comparable<T>> T max(T a, T b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    int varargs(String... parts) {
        if (parts.length == 0) {
            return -1;
        }
        return parts.length;
    }

    int loopy(int n) throws java.io.IOException {
        int acc = 0;
        outer:
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 2; j++) {
                if (j == 1) {
                    continue outer;
                }
                if (i % 2 == 0) acc += i; else continue;
            }
        }
        try (java.io.StringReader rd = new java.io.StringReader("x")) {
            if (rd.read() < 0) {
                throw new java.io.IOException("empty");
            }
        }
        return acc;
    }

    int fails(int n) {
        if (n > 0) {
            throw new IllegalStateException("n=" + n);
        }
        return n;
    }

    @DecisionRun("torture")
    public static String run() throws Exception {
        Torture t = new Torture();
        StringBuilder b = new StringBuilder();
        b.append(t.patterns("hello")).append(t.patterns(3)).append(t.definite(7)).append(t.definite(-1));
        b.append(t.area(new Circle(1))).append(t.area(new Square(2))).append(new Circle(2).size());
        b.append(t.tier(Tier.GOLD, 150, "a@b.c")).append(t.tier(Tier.SILVER, 7, "x"));
        b.append(t.lambdas(new ArrayList<>(List.of(1, 2, 3))));
        b.append(t.max("pear", "apple")).append(t.varargs("a", "b")).append(t.varargs());
        b.append(t.loopy(5));
        b.append(new Greeter() { }.greet(null));
        try {
            t.fails(2);
        } catch (IllegalStateException e) {
            b.append(e.getMessage());
        }
        return b.toString();
    }
}
