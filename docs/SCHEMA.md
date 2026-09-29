# decision-telemetry schema, version 1

This is the contract between instrumenters (today: the Java proof of concept) and readers (the CLI, a future MCP server, a future IDE plugin, anything else). It does not depend on Java. An instrumenter for any language with Minigraf bindings can write the same files, and every reader will work on them.

Change it only with a version bump. The version is stored in every run as `:run/schema` (Minigraf) or `"schema"` (JSON).

## Concepts

A **run** is one unit of work, such as a request, a job or a test. It holds **nodes**.

A node is either:

- a **frame**: one call of a traced function, with its arguments as inputs, or
- a **branch**: one decision, with its kind (`if`, `ternary`, `switch`), the condition's source text, the outcome, and the values of the variables the condition read.

Every node has a parent frame, except the run's root frame. Node ids count up from 1 in the order nodes were recorded, so a lower id always happened earlier. Timing and ids differ between runs of the same code, so readers compare runs by **site** and position, never by id.

## Sites

A site is a stable name for a place in the code. It must stay the same across builds unless that code changes.

```
frame:   <type>#<function>(<param types>)          com.acme.Pricing#quote(Order,int)
branch:  <frame site>/<kind>[<label>]               com.acme.Pricing#quote(Order,int)/if[discount]
         <frame site>/<kind>#<ordinal>              com.acme.Pricing#quote(Order,int)/if#2
```

- Param types have generics removed.
- Nested types use `$`: `com.acme.Outer$Inner#run()`.
- A branch with a source label (`discount: if (...)`) is named by the label. Labels survive edits, so prefer them for decisions you care about.
- Otherwise the ordinal counts branches of that kind in source order within the function, starting at 1. Skipped branches still count, so ordinals stay stable.

Other languages should keep the same shape. Use `.` for the namespace path and `#` before the function name.

## Source location

Every node carries `file` and `line`. `file` is relative to the repository root (the nearest directory with `.git`), for example `src/main/java/com/acme/Pricing.java`. If there is no repository, it is relative to the source root (`com/acme/Pricing.java`). IDE plugins use this to map a node to code.

## Code idents

Frames also carry a **code ident**: the same ident [temporal_reasoning](https://github.com/project-minigraf/temporal_reasoning) mints for a function when it ingests git history. With it, a trail can be joined to the commits and agent sessions that last changed the code.

```
code ident = ":function/" + slug(file + "::" + function name)
slug(s)    = lowercase s; replace every character outside [a-z0-9_-] with "-";
             do not collapse repeated "-"; strip leading and trailing "-"
```

For example, `src/auth_login.py` and `login` give `:function/src-auth_login-py--login`.

## Values

Inputs are stored as display strings, rendered when the decision happened:

| Value | Stored as |
|---|---|
| null | `null` |
| number, boolean, char | its literal, for example `42`, `2.5`, `true` |
| enum | the constant name, for example `GOLD` |
| string | quoted and cut to a maximum length: `"abc"` |
| array | `<int[] len=3>` |
| collection or map | `<ArrayList size=2>` |
| optional | `Optional[...]` or `Optional.empty` |
| redacted | `<redacted>` |
| any other object | `<TypeName>`, or its `toString()` only when the user allows that type |

Values must be stable across runs, or diffs fill with noise. Never store identity hashes, addresses or timestamps as values.

Branch outcomes are `true` or `false` for `if` and `ternary`. For `switch` the outcome is the rendered selector value.

## Minigraf storage (`.graph`)

One run per file. File names are `<UTC yyyyMMdd-HHmmss>-<run name>-<first 8 chars of run id>.graph`.

Entities are keywords: `:run`, `:n-<node id>`, `:i-<node id>-<input index>`, `:anomaly-<index>`. Minigraf returns its own entity ids in query results, so every entity also carries its numeric id as a plain attribute. Join on those, not on entity ids.

### Run (`:run`)

| Attribute | Type | Notes |
|---|---|---|
| `:run/schema` | int | 1 |
| `:run/id` | string | UUID |
| `:run/name` | string | from `@DecisionRun("name")`, or the root site |
| `:run/code-version` | string | build or commit id |
| `:run/started-at-ms` | int | epoch milliseconds |
| `:run/status` | string | `ok` or `error` |
| `:run/error` | string | exception type, only when status is `error` |
| `:run/dropped` | int | nodes not stored because the run hit its limit |

### Anomaly (`:anomaly-<k>`)

| Attribute | Type |
|---|---|
| `:anomaly/idx` | int |
| `:anomaly/text` | string |

### Node (`:n-<id>`)

| Attribute | Type | Notes |
|---|---|---|
| `:node/id` | int | |
| `:node/parent-id` | int | 0 for the root frame |
| `:node/parent` | ref | to the parent node; absent on the root |
| `:node/kind` | string | `frame`, `if`, `ternary`, `switch` |
| `:node/site` | string | see Sites |
| `:node/file` | string | repo-relative path |
| `:node/line` | int | |
| `:node/thread` | string | |
| `:node/async` | bool | frame started on a different thread from its parent |
| `:node/start-ns` | int | nanoseconds since the run started |
| `:node/code-ident` | string | frames only |
| `:node/duration-ns` | int | frames only, -1 if unfinished |
| `:node/status` | string | frames only: `ok` or `error` |
| `:node/error` | string | frames only: exception type that escaped |
| `:node/text` | string | branches only: condition source |
| `:node/outcome` | string | branches only |

### Input (`:i-<node id>-<k>`)

| Attribute | Type | Notes |
|---|---|---|
| `:input/node` | ref | to the node |
| `:input/node-id` | int | |
| `:input/idx` | int | position in the node's input list |
| `:input/name` | string | variable or parameter name |
| `:input/value` | string | rendered value |
| `:input/num` | int or float | only when the value is a plain number, so queries can compare it |

### Rules

Rules are not stored in the file. Readers define them when they open a run:

```clojure
(rule [(ancestor ?a ?d) [?d :node/parent ?a]])
(rule [(ancestor ?a ?d) [?d :node/parent ?m] (ancestor ?a ?m)])
(rule [(decision ?b) [?b :node/kind "if"]])
(rule [(decision ?b) [?b :node/kind "ternary"]])
(rule [(decision ?b) [?b :node/kind "switch"]])
```

Example: every decision made by the frames above node 18, before node 18:

```clojure
(query [:find ?site ?out
        :where [?t :node/id 18] (ancestor ?a ?t)
               [?b :node/parent ?a] (decision ?b)
               [?b :node/id ?bid] [(< ?bid 18)]
               [?b :node/site ?site] [?b :node/outcome ?out]])
```

## JSON Lines storage (`.jsonl`)

The fallback when Minigraf is not available. First line is the run header, then one line per node in id order. The shapes are the `run` and `node` objects below.

## JSON shapes

Every CLI command prints these shapes with `--json`. They are the base for an agent API.

`run`:

```json
{"type":"run","schema":1,"id":"...","name":"checkout","codeVersion":"abc123",
 "startedAtMillis":1790577132134,"status":"ok","error":null,"dropped":0,
 "anomalies":[],"nodeCount":31}
```

`node` (frame):

```json
{"type":"node","id":1,"parent":0,"kind":"frame","site":"com.acme.Checkout#handle(Order,int)",
 "file":"src/main/java/com/acme/Checkout.java","line":35,"thread":"main","async":false,
 "startNanos":1055600,"codeIdent":":function/src-main-java-com-acme-checkout-java--handle",
 "durationNanos":45866623,"status":"ok","error":null,
 "inputs":[{"name":"order","value":"<Order>"}]}
```

`node` (branch): same, with `text` and `outcome` in place of `codeIdent`, `durationNanos`, `status` and `error`.

`trail show --json`: `{"run": run, "nodes": [node...]}`

`trail diff --json`:

```json
{"baseline": run, "candidate": run,
 "first": divergence or null,
 "precedingInputChanges": [divergence...],
 "divergences": [divergence...]}
```

where `divergence` is

```json
{"kind":"OUTCOME_CHANGED","summary":"ternary ... went true in baseline, false in candidate",
 "path":["frame site", "..."],"baseline": node or null,"candidate": node or null,
 "inputChanges":[{"name":"t","baseline":"GOLD","candidate":"null"}]}
```

`kind` is one of `OUTCOME_CHANGED`, `STATUS_CHANGED`, `MISSING`, `EXTRA`, `INPUTS_CHANGED`. `first` is the earliest divergence of any kind except `INPUTS_CHANGED`. `precedingInputChanges` are the input-only changes recorded before it: the likely causes.

`trail why --json`: `{"target": node, "steps": [{"frame": node, "priorDecisions": [node...], "omitted": 0}...]}`

`trail runs --json`: `[run + {"file": "path"}...]`, newest first.
