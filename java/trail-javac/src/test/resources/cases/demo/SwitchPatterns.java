package demo;

import io.github.adityamukho.trail.DecisionRun;
import io.github.adityamukho.trail.TraceDecisions;

/** Pattern matching in switch, which needs JDK 21. Kept apart from Torture so that runs on JDK 17. */
@TraceDecisions
public class SwitchPatterns {
    sealed interface Shape permits Circle, Square {
    }

    record Circle(double r) implements Shape {
    }

    record Square(double s) implements Shape {
    }

    double area(Shape sh) {
        return switch (sh) {
            case Circle c -> Math.PI * c.r() * c.r();
            case Square q -> q.s() * q.s();
        };
    }

    @DecisionRun("switch-patterns")
    public static String run() {
        SwitchPatterns p = new SwitchPatterns();
        return "" + p.area(new Circle(1)) + p.area(new Square(2));
    }
}
