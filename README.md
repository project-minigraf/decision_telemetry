# decision-telemetry

Decision telemetry records which way your code went at each `if`, ternary and `switch`, and the values that drove each choice. It stores each run in a [Minigraf](https://github.com/project-minigraf/minigraf) file. Then it compares two runs and tells you where they parted ways, and why.

It is not tied to one language. Runs follow a language-neutral [schema](docs/SCHEMA.md), and the plan is to support every language that has Minigraf bindings. The Java code in [`java/`](java/) is a proof of concept.

It is built for code that nobody on the team knows well, such as large amounts of AI-generated code. An SRE should not need to read the code to find the decision that went wrong.

> Status: early spike (0.1.0-SNAPSHOT). Only the Java proof of concept exists so far. The API and file format may change.

## What it looks like

The Java demo runs the same checkout twice. The only difference is how fast a background loyalty lookup is. The totals differ, and nothing fails:

```
fast loyalty: total 12150 (discount 1350)
slow loyalty: total 13500 (discount 0)
```

`trail diff` on the two runs:

```
First divergence: ternary ...Services$CustomerCache#tierOf(String)/ternary#1 went true in baseline, false in candidate
  call path:
    io.github.adityamukho.trail.demo.Checkout#handle(Order,int)
    io.github.adityamukho.trail.demo.Services$CustomerCache#tierOf(String)
  baseline:  #10 ternary#1 (t != null) -> true {t=GOLD}  @java/trail-demo/src/main/java/.../Services.java:26
  candidate: #9 ternary#1 (t != null) -> false {t=null}  @java/trail-demo/src/main/java/.../Services.java:26
  changed t: GOLD -> null

Input changes before it (possible causes):
  #1 ...Checkout#handle(Order,int) {order=<Order>, loyaltyLatencyMillis=5}
    changed loyaltyLatencyMillis: 5 -> 80
  #2 ...Services$Loyalty#refresh(String,int) {customerId="cust-42", latencyMillis=5}
    changed latencyMillis: 5 -> 80
```

The cache lookup found nothing in the slow run, so the customer lost the gold discount. The loyalty latency is the likely cause. The loyalty work ran on a pool thread, and the trail followed it there.

## Using the Java proof of concept

### 1. Turn on the compiler plugin

Maven:

```xml
<dependency>
  <groupId>io.github.adityamukho</groupId>
  <artifactId>trail-runtime</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<!-- optional: store runs in Minigraf instead of JSON Lines -->
<dependency>
  <groupId>io.github.project-minigraf</groupId>
  <artifactId>minigraf-jvm</artifactId>
  <version>2.0.2</version>
  <scope>runtime</scope>
</dependency>

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <configuration>
    <annotationProcessorPaths>
      <path>
        <groupId>io.github.adityamukho</groupId>
        <artifactId>trail-javac</artifactId>
        <version>0.1.0-SNAPSHOT</version>
      </path>
    </annotationProcessorPaths>
    <compilerArgs>
      <arg>-Xplugin:DecisionTelemetry</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

Gradle:

```kotlin
dependencies {
    implementation("io.github.adityamukho:trail-runtime:0.1.0-SNAPSHOT")
    runtimeOnly("io.github.project-minigraf:minigraf-jvm:2.0.2")
    annotationProcessor("io.github.adityamukho:trail-javac:0.1.0-SNAPSHOT")
}
tasks.withType<JavaCompile> { options.compilerArgs.add("-Xplugin:DecisionTelemetry") }
```

### 2. Choose what to trace

Annotate code:

```java
@DecisionRun("checkout")            // a run starts here: a request, a job, a test
Receipt handle(Order order) { ... }

@TraceDecisions                     // on a method or a whole class
long discount(Tier tier, long subtotal) {
    gold: if (tier == Tier.GOLD && subtotal >= 10_000) {   // a label gives the branch a stable name
        return subtotal / 10;
    }
    return 0;
}

boolean login(String user, @Redact String password) { ... }   // the value never leaves the method
```

Or trace code you do not want to touch. This traces every method that makes a decision in these packages:

```
-Xplugin:DecisionTelemetry packages=com.acme.billing,com.acme.pricing
```

Other plugin options: `ternary=false` and `verbose`.

### 3. Carry runs across threads

Wrap executors so that tasks join the run that submitted them:

```java
ExecutorService pool = Decisions.wrap(Executors.newFixedThreadPool(8));
CompletableFuture.supplyAsync(Decisions.wrapSupplier(() -> work()), pool);
```

### 4. Decide which runs to keep

Every run is recorded in memory. When it ends, it is kept if any of these are true:

- an exception escaped a traced method;
- the code called `Decisions.anomaly("reason")`;
- it was picked by the sample rate (`trail.sample`, default 0).

Kept runs are written by one background thread, one file per run.

| Setting (`-Dtrail.x` or `TRAIL_X`) | Default | Meaning |
|---|---|---|
| `trail.enabled` | `true` | master switch |
| `trail.autoRun` | `true` | start a run at the outermost traced method if no `@DecisionRun` is active |
| `trail.sample` | `0.0` | share of healthy runs to keep |
| `trail.sink` | `auto` | `auto`, `minigraf`, `jsonl`, `both`, `none`. `auto` uses Minigraf if it is on the classpath |
| `trail.dir` | `./decision-telemetry` | where run files go |
| `trail.maxNodes` | `20000` | nodes kept per run |
| `trail.maxValueChars` | `200` | longest string value |
| `trail.toStringPackages` | none | package prefixes whose objects may be shown with `toString()` |
| `trail.asyncGraceMillis` | `5000` | how long a finished run waits for async work it started |
| `trail.codeVersion` | `unknown` | build or commit id stored with each run |

### 5. Read the runs

The runtime jar is also the CLI. Add `minigraf-jvm` to the class path to read `.graph` files.

```
trail runs <dir>                        list runs, newest first
trail show <run> [--depth N]            call tree with every decision
trail diff <baseline> <candidate>       where two runs diverged, and the likely causes
trail why <run> <node-id>               the decisions that led to a node
trail query <run.graph> '<datalog>'     ad hoc Datalog, with ancestor and decision rules
```

```
java -cp trail-runtime.jar:minigraf-jvm.jar io.github.adityamukho.trail.cli.TrailCli diff a.graph b.graph
```

Every command accepts `--json`. The JSON shapes are fixed and documented, so scripts and AI agents can use the same commands people use. `diff` exits with 1 when the runs diverged, so it also works in CI.

## How the Java instrumenter works

The plugin runs inside `javac`, right after parsing. It rewrites traced methods like this:

```java
// before
long discount(Tier tier, long subtotal) {
    gold: if (tier == Tier.GOLD && subtotal >= 10_000) { return subtotal / 10; }
    return 0;
}

// after (in effect)
long discount(Tier tier, long subtotal) {
    final Frame f = Decisions.enter("<site>", tier, subtotal);
    try {
        gold: if (tier == Tier.GOLD && subtotal >= 10_000) {
            Decisions.yes("<site>", tier, subtotal);
            return subtotal / 10;
        } else {
            Decisions.no("<site>", tier, subtotal);
        }
        return 0;
    } catch (final Throwable e) {
        f.fail(e);
        throw e;
    } finally {
        f.exit();
    }
}
```

A few choices matter:

- **The condition is never touched.** Java's flow rules depend on the condition being a direct part of the `if`. Pattern variables (`if (!(o instanceof Foo f)) return; use(f);`) and definite assignment would break if it were wrapped in a call. So the plugin records at the start of each arm instead.
- **Values are read after the condition runs**, from the variables the condition reads. Pattern variables and variables assigned inside the condition are left out, because they are not always readable there.
- **Values are rendered right away** into short, stable strings. Objects show as `<TypeName>` unless you allow `toString()`, so personal data does not leak by accident and diffs do not fill with noise.
- **Branch names are stable.** A branch is named by its source label if it has one, or by its position in the method. File paths are relative to the git root.
- **Frames link to code history.** Each frame stores the same `:function/...` ident that [temporal_reasoning](https://github.com/project-minigraf/temporal_reasoning) uses for code entities, so a trail can be joined to the commits and agent sessions that changed that function.

Constructors, local classes and anonymous classes are not traced. `while` and `for` conditions are not traced (the `if`s inside them are).

The plugin uses `jdk.compiler` internals, like Lombok does. It opens them at startup. On newer JDKs this prints a warning about `sun.misc.Unsafe`. To avoid it, pass these to javac: `-J--add-exports=jdk.compiler/com.sun.tools.javac.<pkg>=ALL-UNNAMED` for `api`, `code`, `file`, `tree` and `util`.

## Storage

Each kept run is one Minigraf `.graph` file (or one `.jsonl` file). One file per run fits Minigraf's single-file design: runs stay small, they can be copied and attached to a ticket, and old runs are deleted by deleting files.

The format is a language-neutral contract, so other instrumenters and readers can share it. See [docs/SCHEMA.md](docs/SCHEMA.md).

## Overhead

Measured for the Java proof of concept on one machine with a small method (one frame and two decisions per call). These are early numbers, not tuned yet:

| Case | Cost per call |
|---|---|
| plain code | about 1 ns |
| traced, no run active | about 20 ns |
| traced, inside a run | about 400 ns (about 130 ns per recorded event) |

Every run is recorded in full and the keep decision is made at the end, so failures are always captured. That also means every traced request pays the recording cost. Keep tracing to the code you are investigating, or turn it off with `trail.enabled=false`.

## Limits of the Java proof of concept

- Name capture works from syntax, before types are known. It skips names that look like types (`Tier.GOLD`, `UUID.randomUUID()`). A lower-case class name used as a receiver would be taken for a variable and fail to compile. Rename it, or trace that method by hand.
- Ternaries with numeric literals, primitive casts, lambdas or method references as operands are not traced, since routing them through a generic call could change their type.
- Work handed to threads you do not wrap is not part of the run.
- `ForkJoinPool.commonPool()` is not wrapped for you. Pass a wrapped executor to `CompletableFuture`.
- JDK 17 or newer.

## Building

The Java proof of concept lives in `java/`:

```
cd java
mvn install                      # build, test and install all modules (the demo needs them installed)
mvn -pl trail-demo exec:java     # run the demo; runs go to trail-demo/target/decision-telemetry-demo
```

## What's next

- An MCP server over the same queries (`runs`, `show`, `diff`, `why`, `query`), so incident-response agents can use trails with no glue code.
- An IntelliJ plugin that reads the schema and shows decision outcomes next to the code, for any language that writes the format.
- Instrumenters for the other languages that have Minigraf bindings, all writing the same schema.
- Lower overhead: a ring buffer per run and lazy value rendering.
