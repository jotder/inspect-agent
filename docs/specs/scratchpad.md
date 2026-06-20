# Scratchpad / Virtual FS — Spec

> Context offloading so the LLM context window doesn't blow up: large intermediate results are stored by handle and re-read on demand. Component 6 in [01-component-model.md](../architecture/01-component-model.md). Port(s): `Scratchpad`.

## Purpose

During a multi-step run, tools and sub-agents produce large intermediate results — a full schema dump, a 2,000-row query preview, a retrieved document, a generated pipeline spec. Feeding all of that back into the model history would exhaust the context window and balloon cost. The `Scratchpad` is a **virtual filesystem**: the orchestrator writes a large blob, gets back a compact **handle**, and puts only the handle (plus a short synopsis) into the model history. The model — or a later step — can `read(handle)` to pull the full content back when it actually needs it.

This is a Phase 1 / MVP capability. It is the mechanism behind the "context offloading" step in Flow B and the per-worker isolated scratchpad scopes in Flow D.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-6--scratchpad--virtual-fs---port-scratchpad), package `com.eoiagent.scratchpad`. Copied verbatim:

```java
package com.eoiagent.scratchpad;

public interface Scratchpad {
    String write(String key, String content);   // returns a handle/ref
    String read(String key);
    List<String> list(String prefix);
    void delete(String key);
}
```

Contract notes:

- **`write(key, content)`** — Pre: `key` and `content` non-null; `key` is a logical path (slash-separated namespace, e.g. `run-123/sql/preview`). Post: stores `content` and returns a **handle** string that uniquely identifies this entry (the handle is what goes into model history; an implementation may return the key itself or a key+revision token). Writing an existing `key` **overwrites** it. The handle is stable enough to `read` back within the same run. Threading: safe for concurrent writes to **distinct** keys; concurrent writes to the same key are last-write-wins.
- **`read(key)`** — Pre: `key` (or handle) non-null. Post: returns the exact stored content. Reading an unknown key throws `ScratchpadKeyNotFound` (a `ScratchpadException`; see Error handling) — it does **not** return null (no nullable returns per [conventions.md §4](../conventions.md#4-java-style)).
- **`list(prefix)`** — Post: returns all keys under `prefix` (prefix-match on the slash namespace), sorted; empty list (never null) if none. Used to enumerate a worker's scope.
- **`delete(key)`** — Post: idempotent; deleting an unknown key is a no-op. Used to reclaim a run's or worker's scope on completion.

Keys are namespaced by run and (for sub-agents) by worker, e.g. `${runId}/...` and `${runId}/${worker}/...`. The Scratchpad does **not** itself enforce isolation across scopes — the orchestrator constructs a scope-prefixed view per worker so a worker can only address its own subtree (Flow D, see [orchestration-runtime.md §4](orchestration-runtime.md)).

`ScratchpadKeyNotFound` is owned by this module (subtype of `EoiAgentException`).

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `InMemoryScratchpad` | (ours; `ConcurrentHashMap`) | MVP | Default everywhere; per-run, discarded at run end. The only scratchpad available in `OFFLINE` unless temp-dir is explicitly enabled. |
| `TempDirScratchpad` | (ours; JDK `java.nio.file` in a temp dir) | MVP | Profile-gated. For very large blobs or long-running (Phase 3) runs whose intermediates should survive in-process memory pressure. Writes under a per-run temp directory, cleaned on `delete`/run end. |

Both adapters are pure JDK — no third-party library. `TempDirScratchpad` is constructed only when `eoiagent.scratchpad.kind=temp-dir` **and** the profile permits filesystem use; otherwise the runtime uses `InMemoryScratchpad`.

## Maven coordinates

This module: `com.eoiagent:eoiagent-scratchpad` (version `0.1.0-SNAPSHOT`). Depends only on `com.eoiagent:eoiagent-core`.

No third-party dependencies — both adapters use only the JDK (`java.util.concurrent`, `java.nio.file`). Nothing to pin via BOM. This keeps the module trivially offline-safe.

## Inputs / Outputs

Consumes: `AgentContext` (for the `RunId`/`SessionId` used to build scope prefixes) and config from `ConfigProvider`. Strings in (`key`, `content`).

Produces: handle strings (returned from `write`, embedded into the orchestrator's model history). The Scratchpad does **not** produce domain records — it is a side store. Large `ToolResult.value` payloads are the typical input; the handle replaces the raw payload in `ChatRequest.messages`.

There are no new domain types beyond `ScratchpadKeyNotFound` (exception). Content is opaque `String` (callers serialize structured data to JSON/text before writing).

## Behavior / algorithm

### Offloading by handle (the core mechanism — Flow B step "Scratchpad.write for large intermediate results")

Referenced from [Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1):

1. The orchestrator dispatches a tool and receives a `ToolResult`.
2. It measures `result.value` (serialized size). If size ≤ `eoiagent.runtime.offloadThresholdBytes`, the value is inlined into model history as usual.
3. If size **>** threshold:
   1. `String handle = scratchpad.write("${runId}/${stepId}/${toolName}", serialized);`
   2. Append to model history a compact reference instead of the payload, e.g. *"Result stored at `<handle>` (1,842 rows, columns: …). Use read to inspect."* — a short, model-readable synopsis the orchestrator derives (row/byte count, first keys, schema).
   3. The model, on a later step, may emit a built-in `read_scratchpad(handle)` tool call (registered by the runtime) to pull the full content back when needed; the orchestrator resolves it via `scratchpad.read(handle)`.
4. This keeps the working context small and bounded regardless of intermediate result sizes — the window holds handles + synopses, not megabytes of data.

### Per-worker scoping (Flow D)

- For each sub-agent, the orchestrator hands the worker a **scope-prefixed Scratchpad view** rooted at `${runId}/${worker}/`. The worker writes/reads/lists relative to that root; it cannot address sibling workers' subtrees. The supervisor can `list("${runId}/")` across all workers to aggregate.

### Lifecycle

- A run's scratchpad subtree is reclaimed at run end (`delete` by prefix / drop the in-memory map). `TempDirScratchpad` removes its per-run temp directory. No scratchpad content persists across runs in MVP (Phase 3 may pin selected handles into a checkpoint — out of scope here).

## Configuration keys

Keys this module reads (prefix `eoiagent.`):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) | Meaning |
|-----|------|--------------------------------------------|---------|
| `eoiagent.scratchpad.kind` | String | `in-memory` / `in-memory` / `in-memory` | `in-memory` \| `temp-dir` |
| `eoiagent.scratchpad.tempDir` | String | `${java.io.tmpdir}/eoiagent` / … / … | base dir for `TempDirScratchpad` |
| `eoiagent.scratchpad.maxEntryBytes` | int | `5242880` / `10485760` / `10485760` | reject single writes larger than this (5–10 MB) |
| `eoiagent.scratchpad.maxTotalBytes` | int | `52428800` / `104857600` / `104857600` | per-run cap; exceeding triggers eviction of oldest entries |

The **offload threshold** that decides when to offload is owned by the runtime (`eoiagent.runtime.offloadThresholdBytes`, see [orchestration-runtime.md](orchestration-runtime.md#configuration-keys)), not by this module — Scratchpad stores whatever it is told to.

## Error handling

Typed exceptions; `ScratchpadException` subtypes root at `EoiAgentException` ([conventions.md §5](../conventions.md#5-error-handling)):

- `ScratchpadKeyNotFound` — `read` of an unknown key/handle. Callers (orchestrator) treat this as a recoverable observation, not a crash.
- `ScratchpadException` (capacity) — a single `write` exceeds `maxEntryBytes`, or the run exceeds `maxTotalBytes` and eviction cannot reclaim enough; the orchestrator falls back to truncating the synopsis and records an `ERROR` `AuditEvent`.
- `ConfigException` — `kind=temp-dir` selected but the temp dir is unwritable, or temp-dir is disallowed by the profile (offline fail-closed: do **not** silently write to disk where the profile forbids it).
- I/O faults in `TempDirScratchpad` wrap and rethrow as `ScratchpadException`; never swallow silently.

The Scratchpad never makes a network call and is safe in `OFFLINE` by construction.

## Acceptance criteria

- **AC1** — `InMemoryScratchpad.write` returns a handle; `read(handle)` returns the exact content; `read` of an unknown key throws `ScratchpadKeyNotFound`.
- **AC2** — `write` to an existing key overwrites; subsequent `read` returns the new content.
- **AC3** — `list(prefix)` returns exactly the keys under that prefix, sorted, and an empty list for a prefix with no entries.
- **AC4** — `delete` removes a key (subsequent `read` throws) and is idempotent for unknown keys.
- **AC5** — A scope-prefixed view rooted at `${runId}/${worker}/` cannot `read` or `list` keys outside its subtree (worker isolation, Flow D).
- **AC6** — `TempDirScratchpad` persists content to disk under the configured temp dir and removes the per-run directory on cleanup; round-trips identically to `InMemoryScratchpad`.
- **AC7** — A `write` exceeding `maxEntryBytes` throws `ScratchpadException`; exceeding `maxTotalBytes` triggers oldest-first eviction or a typed capacity error (never silent data loss without an `ERROR` audit).
- **AC8** — With `kind=temp-dir` disallowed by the active profile (or unwritable dir), construction throws `ConfigException`; no disk write occurs.
- **AC9** — All operations complete with no network access (asserted by the network-deny harness).

## Test plan

All tests run **NO network, NO live LLM**; the Scratchpad has no model dependency. Framework: JUnit 5 + AssertJ.

- **Unit** — `InMemoryScratchpadTest` (AC1–AC4, AC7), `TempDirScratchpadTest` (AC6, AC8 using JUnit `@TempDir`), `ScopedScratchpadViewTest` (AC5 — the orchestrator's scope-prefixed wrapper).
- **Contract** — `ScratchpadContractTest`: a shared suite both adapters must pass (write/read/list/delete semantics, overwrite, not-found, idempotent delete, capacity behavior — AC1–AC4, AC7). Each adapter parameterizes the suite.
- **Concurrency** — `ScratchpadConcurrencyTest`: concurrent writes to distinct keys do not interfere; same-key writes are last-write-wins.
- **Offline** — network-deny assertion (AC9) included in the contract suite.

Run: `mvn -pl eoiagent-scratchpad -am test`.

## Dependencies on other modules

- `eoiagent-core` — `AgentContext`, `RunId`/`SessionId` (for scope prefixes), `EoiAgentException` base.
- `eoiagent-config` (`ConfigProvider`) — `kind`, temp dir, capacity limits, profile gating.
- Consumed by `eoiagent-runtime` (offloading + per-worker scopes) and `eoiagent-observability` (`AuditSink` for capacity-eviction `ERROR` events).

## Out of scope / deferred

- Persisting scratchpad content across runs / pinning handles into checkpoints → **Phase 3** (coupled to `CheckpointStore`).
- Encryption-at-rest for `TempDirScratchpad` blobs → Phase 4 hardening.
- A binary/byte-array content variant (current port is `String`-only; callers serialize) → revisit if a tool needs raw binary handoff.
- Cross-session shared scratchpad → not planned; use Memory / Knowledge for durable cross-session data.

## Related ADRs & flows

- ADR: [../adr/0004-hexagonal-ports-and-adapters.md](../adr/0004-hexagonal-ports-and-adapters.md).
- Flows: [Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1) (context offloading step), [Flow D](../architecture/04-sequence-flows.md#flow-d--supervisor--sub-agents-delegation-phase-2) (per-worker scratchpad scope).
- Component model: [01-component-model.md §Component 6](../architecture/01-component-model.md#component-6--scratchpad--virtual-fs---port-scratchpad).
- Related spec: [orchestration-runtime.md](orchestration-runtime.md) (owns the offload threshold and invokes the Scratchpad).
