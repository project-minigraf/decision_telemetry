# decision-telemetry: context for Claude Code

Decision telemetry, meant to support every language that has Minigraf bindings. The Java code in `java/` is a proof of concept: a javac plugin records which way each `if`, ternary and `switch` went, with the values that drove it. Runs are stored one per Minigraf `.graph` file (or `.jsonl`), and the CLI diffs two runs to find where they diverged. Part of the Minigraf ecosystem (project-minigraf).

## Layout

- `java/`: the Java proof of concept, a Maven build (`cd java && mvn verify`). Its modules:
- `java/trail-runtime`: annotations (`@TraceDecisions`, `@DecisionRun`, `@Redact`), recorder (`Decisions`, `Run`, `Frame`), sinks, `RunDiff`, `WhySlice`, CLI (`cli/TrailCli`). No required dependencies. Minigraf is reached by reflection in `minigraf/Engines.java`.
- `java/trail-javac`: the plugin (`-Xplugin:DecisionTelemetry`). `Instrumenter` rewrites trees right after parsing. `CaptureNames` picks the variables a condition reads, from syntax only. `ModuleOpener` opens jdk.compiler internals the way Lombok does.
- `java/trail-demo`: checkout with a planted cache race. `mvn -pl trail-demo exec:java`.
- `docs/SCHEMA.md`: the storage and JSON contract, version 1. Language-neutral. Change it only with a version bump.

## Decisions already made (don't revisit without a reason)

- Compile-time plugin, not a runtime agent: it sees statements, condition text and variable names.
- The plugin never wraps a condition. It records at the start of each arm (and adds an `else` if missing). Wrapping breaks pattern-variable scoping and definite assignment.
- Values are rendered to stable strings at record time. Objects show as `<Type>` unless `trail.toStringPackages` allows `toString()`.
- Keep policy is decided at run end: errors and anomalies always kept, healthy runs sampled.
- Frames store temporal_reasoning-compatible code idents (`:function/<slug(path::name)>`); keep `CodeIdents` byte-for-byte in sync with `_canonical_ident`/`_code_ident` in temporal_reasoning's `mcp_server.py`.
- Agent API direction: MCP server over the same queries first, GraphQL later if a UI needs it. Every CLI command already has a stable `--json` output.
- Scope: keep this out of Minigraf core. It is a consumer and a showcase.

## Verified vs not verified

Verified in the cloud session with plain `javac` (Maven Central was blocked there): all runtime and plugin tests, the demo, and a real Minigraf round trip through the Python binding (same Rust FFI as minigraf-jvm).

Verified locally on JDK 17 (2026-09-29): `mvn install` in `java/` passes (the JDK 21 switch-pattern test is skipped there), the demo runs and diffs fast vs slow, and the CLI reads the `.graph` runs. Minigraf comes from `io.github.project-minigraf:minigraf-jvm:2.0.2` (class `uniffi.minigraf_ffi.MiniGrafDb`, opened through `Companion.open`); `MinigrafRoundTripTest` passes against it.

Verified on GitHub CI (2026-10-19, JDK 17, 21 and 25): `mvn install` with all tests, including the plugin on JDK 25 and the JDK 21+ switch-pattern test; the demo writes `.graph` runs through minigraf-jvm; and the CLI reads them, in text and `--json`.

## Next steps

1. Dogfood on a real project with `-Xplugin:"DecisionTelemetry packages=<its root package>"`.
2. MCP server exposing runs, show, diff, why and query.
3. Lower recording overhead (about 130 ns per recorded event now): ring buffer per run, lazy value rendering.

## Style

Plain, short sentences in docs and messages. No em-dashes.
