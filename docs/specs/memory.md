---
type: spec
title: "Memory — Spec"
description: "Short-term windowed/summarized conversation memory + persistence + (Phase 3) long-term cross-session memory."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["memory"]
---
# Memory — Spec

> Short-term windowed/summarized conversation memory + persistence + (Phase 3) long-term cross-session memory. Component 5 in [01-component-model.md](../architecture/01-component-model.md). Port(s): `MemoryStore`, `LongTermMemory` (plus reuse of LangChain4j's `ChatMemory`).

## Purpose

The Memory module gives a `Session` continuity. Within a run, the orchestrator needs the recent conversation (short-term memory). Across restarts, that conversation must survive (persistence). Across sessions, the agent should be able to recall durable facts about a user/entity (long-term memory, Phase 3).

Per [conventions.md §1](../conventions.md#1-module-layout-maven-multi-module), short-term memory **reuses the LangChain4j `ChatMemory` abstraction** (`dev.langchain4j.memory.ChatMemory`) rather than re-inventing it — that is the one place we deliberately depend on an LC4j type as a port. Our own ports `MemoryStore` and `LongTermMemory` sit beside it: `MemoryStore` is the persistence backend behind LC4j's `ChatMemoryStore`, and `LongTermMemory` is our cross-session recall capability (which reuses Knowledge/RAG under the hood in Phase 3).

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-5--memory---ports-chatmemory-memorystore), package `com.eoiagent.memory`. Copied verbatim:

```java
package com.eoiagent.memory;

public interface MemoryStore {                 // persistence behind LC4j ChatMemoryStore
    void put(SessionId id, List<ChatMessageRecord> messages);
    List<ChatMessageRecord> get(SessionId id);
    void delete(SessionId id);
}
public interface LongTermMemory {              // Phase 3
    void remember(SessionId scope, MemoryFact fact);
    List<MemoryFact> recall(String query, int k);
}
```

Reused LC4j port: `dev.langchain4j.memory.ChatMemory` (windowing/eviction lives here); our adapters wrap LC4j's `MessageWindowChatMemory` / `TokenWindowChatMemory` and bridge our `MemoryStore` to LC4j's `dev.langchain4j.store.memory.chat.ChatMemoryStore`.

Contract notes:

- **`MemoryStore.put(id, messages)`** — Pre: `id` non-null; `messages` non-null (may be empty, which clears the session's stored list). Post: replaces the full stored message list for `id` (last-write-wins snapshot, matching LC4j `ChatMemoryStore.updateMessages` semantics — **not** an append). Threading: must be safe for concurrent calls on **distinct** `SessionId`s; concurrent writes to the same `SessionId` are the caller's responsibility (a session is single-threaded from the caller's view per [conventions.md §6](../conventions.md#6-concurrency--resources)).
- **`MemoryStore.get(id)`** — Post: returns the stored list (oldest→newest) or an **empty list** (never null) for an unknown session. Returned list is a copy/immutable; callers must not mutate it.
- **`MemoryStore.delete(id)`** — Post: idempotent; deleting an unknown session is a no-op. Emits no audit event (memory deletion is not a domain mutation).
- **`LongTermMemory.remember(scope, fact)`** — Pre: non-null args. Post: persists `fact` under `scope`; embedding/indexing is implementation-detail (VectorLongTermMemory embeds via the RAG embedding model). Gated by `featureEnabled(LONG_TERM_MEMORY)` — throws `PolicyViolation` if disabled by profile (fail-closed).
- **`LongTermMemory.recall(query, k)`** — Pre: `k ≥ 1`. Post: returns up to `k` `MemoryFact`s ranked by relevance, newest-as-tiebreak; empty list if none. Read-only; never throws on empty corpus.

`ChatMessageRecord` and `MemoryFact` are domain types owned here (not in the core table); define them in `com.eoiagent.memory` (see Inputs / Outputs).

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `WindowChatMemory` | `dev.langchain4j:langchain4j` (`MessageWindowChatMemory`) | MVP | Keeps the last N messages; N from config. |
| `TokenWindowChatMemory` | `dev.langchain4j:langchain4j` (`TokenWindowChatMemory`) | MVP | Keeps last messages within a token budget; needs a tokenizer (from the active model). |
| `SummarizingChatMemory` | `dev.langchain4j:langchain4j` (summarization eviction) | Phase 2 | Summarizes evicted turns into a running summary via `LlmGateway`; preserves long-run context. |
| `InMemoryMemoryStore` | (ours) | MVP | `Map<SessionId, List<ChatMessageRecord>>`; default offline test store. |
| `FileMemoryStore` | (ours, JSON on disk) | MVP | Survives restart in single-node/offline installs. |
| `PostgresMemoryStore` | (ours, JDBC) | Phase 2 | Multi-node / durable persistence. |
| `VectorLongTermMemory` | reuses `eoiagent-knowledge` (`EmbeddingModel` + `VectorStore`) | Phase 3 | Embeds + stores `MemoryFact`s; `recall` is a top-k vector search. |

`WindowChatMemory` / `TokenWindowChatMemory` delegate eviction to LC4j and delegate persistence to a configured `MemoryStore` via an LC4j `ChatMemoryStore` bridge.

## Maven coordinates

This module: `com.eoiagent:eoiagent-memory` (version `0.1.0-SNAPSHOT`). Depends on `com.eoiagent:eoiagent-core`. `VectorLongTermMemory` adds a dependency on `com.eoiagent:eoiagent-knowledge` (Phase 3).

Third-party (versions via BOM per [02-domain-model.md](../architecture/02-domain-model.md#maven-coordinates) — never hardcode):

- `dev.langchain4j:langchain4j` — `ChatMemory`, `MessageWindowChatMemory`, `TokenWindowChatMemory`, `ChatMemoryStore`, summarization eviction (version from `langchain4j-bom:1.16.3`).
- JDBC: PostgreSQL driver for `PostgresMemoryStore` is provided by the host/runtime classpath; this module declares it `provided`/`test` scope only — no version pin here.
- `VectorLongTermMemory` reuses the offline embedding adapter (`langchain4j-embeddings-all-minilm-l6-v2`) and vector store (`InMemoryVectorStore`/`PgVectorStore`) **through the Knowledge ports** — it does not import those artifacts directly.

The `SummarizingChatMemory` reaches the model only through the `LlmGateway` port (no direct model artifact dependency).

## Inputs / Outputs

Consumes (from `com.eoiagent.core`): `SessionId`, `AgentContext` (for profile/feature gating in long-term memory), and the reused LC4j `ChatMessage` types.

Produces / owns (in `com.eoiagent.memory`):

```java
package com.eoiagent.memory;
enum ChatRole { SYSTEM, USER, ASSISTANT, TOOL }
record ChatMessageRecord(ChatRole role, String text, Instant at, Map<String,String> meta) {}
record MemoryFact(SessionId scope, String text, Instant at, Map<String,String> meta) {}  // Phase 3
```

`ChatMessageRecord` is the persistence-friendly projection of an LC4j `ChatMessage`; adapters convert both ways at the `MemoryStore` boundary so the store never depends on LC4j message classes leaking into `core`.

## Behavior / algorithm

### 1. Short-term memory lifecycle (MVP)

1. On `AgentSession.open`, the runtime constructs a `ChatMemory` for `ctx.session()` per `eoiagent.memory.kind`: `window` → `WindowChatMemory(maxMessages)`, `token-window` → `TokenWindowChatMemory(maxTokens, tokenizer)`, `summarizing` → `SummarizingChatMemory(...)` (Phase 2).
2. The `ChatMemory` is backed by the configured `MemoryStore` via the LC4j `ChatMemoryStore` bridge: on construction it loads via `MemoryStore.get(id)`; after each turn it flushes via `MemoryStore.put(id, currentMessages)` (snapshot, last-write-wins).
3. The orchestrator reads `ChatMemory.messages()` to seed run history (Flow B step 1) and appends new user/assistant/tool messages as the run proceeds.

### 2. Windowing & token budgeting

- `WindowChatMemory`: LC4j evicts oldest messages beyond `eoiagent.memory.maxMessages`, always preserving the system message.
- `TokenWindowChatMemory`: LC4j evicts to keep total tokens ≤ `eoiagent.memory.maxTokens`, using a tokenizer obtained from the active chat model (`LlmGateway.activeChatModel()`); falls back to a heuristic tokenizer offline if the model exposes none.

### 3. Summarization (Phase 2)

- `SummarizingChatMemory` wraps LC4j summarization eviction: when the budget is exceeded, the oldest turns are summarized via `LlmGateway.chat(...)` into a single running-summary system message, which is retained while the raw turns are evicted. The summary call is audited as `MODEL_CALL`.

### 4. Long-term memory (Phase 3)

- `VectorLongTermMemory.remember`: embed `fact.text()` via the Knowledge `EmbeddingModel`, store the vector + `MemoryFact` metadata in a `VectorStore` namespace keyed by `scope` (or a global namespace for cross-session facts).
- `recall(query, k)`: embed `query`, `VectorStore.search(vector, k, filter)`, map matches back to `MemoryFact`s. The orchestrator may inject recalled facts into the system prompt for relevant runs.
- Gated by `featureEnabled(LONG_TERM_MEMORY)`; disabled profiles fail closed.

## Configuration keys

Keys this module reads (prefix `eoiagent.`, per [02-domain-model.md](../architecture/02-domain-model.md#config-key-namespace)):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) | Meaning |
|-----|------|--------------------------------------------|---------|
| `eoiagent.memory.kind` | String | `window` / `window` / `token-window` | `window` \| `token-window` \| `summarizing` |
| `eoiagent.memory.maxMessages` | int | `20` / `20` / `20` | window size for `WindowChatMemory` |
| `eoiagent.memory.maxTokens` | int | `4096` / `8192` / `8192` | budget for `TokenWindowChatMemory` / summarization trigger |
| `eoiagent.memory.store` | String | `file` / `postgres` / `postgres` | `in-memory` \| `file` \| `postgres` |
| `eoiagent.memory.file.dir` | String | `${workdir}/memory` / — / — | directory for `FileMemoryStore` |
| `eoiagent.memory.postgres.table` | String | — / `eoiagent_chat_memory` / `eoiagent_chat_memory` | table for `PostgresMemoryStore` |
| `eoiagent.memory.longterm.enabled` | boolean | `false` / `false` / `false` | Phase 3; also requires `LONG_TERM_MEMORY` feature |

`token-window`/`summarizing` require a usable tokenizer; if the active model exposes none and no fallback is configured, `ConfigException` is thrown at construction.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- `ConfigException` — unknown `eoiagent.memory.kind`/`store`, or `token-window`/`summarizing` selected without an available tokenizer.
- `PolicyViolation` — `LongTermMemory` used while `LONG_TERM_MEMORY` is disabled by profile (offline fail-closed: never silently degrade to no-op).
- `ModelUnavailableException` — `SummarizingChatMemory` cannot reach the LLM for a summary; the adapter must **not** lose history — it falls back to plain window eviction for that turn and records an `ERROR` `AuditEvent`.
- Persistence faults (`FileMemoryStore` I/O, `PostgresMemoryStore` JDBC): wrap and rethrow as `EoiAgentException`; `get` failures must not return partial data silently. Never swallow — record `ERROR` and rethrow.

Memory mutations are **not** domain mutations and do **not** route through the `ApprovalGate`.

## Acceptance criteria

- **AC1** — `WindowChatMemory` with `maxMessages=N` retains at most N messages plus the system message; oldest non-system messages are evicted first.
- **AC2** — `TokenWindowChatMemory` keeps total tokens ≤ `maxTokens` using the model tokenizer (or configured fallback); a single oversized message is handled without throwing.
- **AC3** — `InMemoryMemoryStore` round-trips: `put` then `get` returns an equal list; `get` of an unknown session returns an empty (non-null) list; `delete` is idempotent.
- **AC4** — `FileMemoryStore` survives a process restart: write, recreate the store on the same dir, `get` returns the same messages.
- **AC5** — `MemoryStore.put` is snapshot semantics (replaces, not appends): putting a 2-message list after a 5-message list yields 2 messages on `get`.
- **AC6** — `ChatMessageRecord` ⇄ LC4j `ChatMessage` conversion is lossless for role, text, and timestamp across all `ChatRole` values.
- **AC7** (Phase 2) — `SummarizingChatMemory` summarizes evicted turns via a stub `LlmGateway` and retains the summary; on `ModelUnavailableException` it falls back to window eviction and records `ERROR`.
- **AC8** (Phase 3) — `VectorLongTermMemory.remember` then `recall(query, k)` returns the relevant fact; with `LONG_TERM_MEMORY` disabled, both methods throw `PolicyViolation`.
- **AC9** — Concurrent `put`/`get` on distinct `SessionId`s never corrupt each other's data (stress test, in-memory and file stores).

## Test plan

All default tests run **NO network, NO live LLM** — `SummarizingChatMemory` and `VectorLongTermMemory` tests use a stub `LlmGateway`/stub `EmbeddingModel`. Framework: JUnit 5 + AssertJ.

- **Unit** — `WindowChatMemoryTest` (AC1), `TokenWindowChatMemoryTest` (AC2), `InMemoryMemoryStoreTest` (AC3, AC5), `FileMemoryStoreTest` (AC4, restart simulated by re-instantiation over a temp dir), `ChatMessageRecordMapperTest` (AC6), `SummarizingChatMemoryTest` (AC7).
- **Contract** — `MemoryStoreContractTest`: a shared suite every `MemoryStore` adapter must pass (round-trip, snapshot semantics, empty-on-unknown, idempotent delete, concurrency on distinct ids — AC3/AC5/AC9). `PostgresMemoryStore` runs it against an embedded/Testcontainers Postgres, tagged `@Tag("integration")` (excluded from the offline default run).
- **Phase 3** — `VectorLongTermMemoryTest` (AC8) over `InMemoryVectorStore` + stub embeddings.
- **Eval** — multi-turn golden conversations asserting context retention across the window (eval harness, [../specs/eval-harness.md](eval-harness.md)).

Run: `mvn -pl eoiagent-memory -am test` (Postgres/integration tests excluded unless `-Pintegration`).

## Dependencies on other modules

- `eoiagent-core` — `SessionId`, `AgentContext`, port declarations.
- `eoiagent-model` (`LlmGateway`) — tokenizer source + summarization LLM (Phase 2).
- `eoiagent-knowledge` (`EmbeddingModel`, `VectorStore`) — backing store for `VectorLongTermMemory` (Phase 3).
- `eoiagent-config` (`ConfigProvider`) — memory kind/store selection, feature gating.
- `eoiagent-observability` (`AuditSink`) — `MODEL_CALL` on summarization, `ERROR` on fallback.
- Consumed by `eoiagent-runtime` (`ChatMemory` seeds run history) and `eoiagent-host` (session lifecycle).

## Out of scope / deferred

- `SummarizingChatMemory` → **Phase 2**.
- `PostgresMemoryStore` → **Phase 2**.
- `LongTermMemory` / `VectorLongTermMemory` (cross-session recall) → **Phase 3** (reuses RAG; gated by `LONG_TERM_MEMORY`).
- Memory redaction / PII scrubbing of stored messages → owned by [../specs/guardrails.md](guardrails.md); not duplicated here.
- Per-entity (non-session) memory namespaces and TTL/forgetting policies → Phase 4.

## Related ADRs & flows

- ADR: [../adr/0003-foundation-langchain4j-bom.md](../adr/0003-foundation-langchain4j-bom.md) (reusing LC4j `ChatMemory`), [../adr/0004-hexagonal-ports-and-adapters.md](../adr/0004-hexagonal-ports-and-adapters.md), [../adr/0007-vector-store-inmemory-then-pgvector.md](../adr/0007-vector-store-inmemory-then-pgvector.md) (long-term memory store).
- Flows: [Flow B step 1](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1) (memory seeds run history), [Flow E](../architecture/04-sequence-flows.md#flow-e--long-running-issue-investigation-with-checkpointing-phase-3) (long-running runs resume with persisted memory).
- Component model: [01-component-model.md §Component 5](../architecture/01-component-model.md#component-5--memory---ports-chatmemory-memorystore).
