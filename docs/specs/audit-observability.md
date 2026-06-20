# Audit & Observability — Spec

> Append-only audit trail of every consequential agent action, plus optional tracing. Component 9
> in [01-component-model.md](../architecture/01-component-model.md). Port(s): `AuditSink`,
> `TraceCollector`. Module: `com.eoiagent:eoiagent-observability`
> (`com.eoiagent.observability`). Phase 1 (audit) + Phase 4 (tracing).

## Purpose

Satisfy constraint **C5** (audit everything) and make the system observable:

- **`AuditSink`** — a persisted, **append-only** record of every `MODEL_CALL`, `TOOL_CALL`,
  `DECISION`, `APPROVAL`, `MUTATION`, `RETRIEVAL`, and `ERROR` (`AuditKind`). Audit is for
  **compliance**, is **never disabled**, and is **distinct from logging** (conventions §7):
  logging (SLF4J) is for developers and may be sampled/filtered/turned off; audit is structured,
  immutable, and mandatory. Nothing mutating happens off-audit.
- **`TraceCollector`** — optional spans for performance/latency observability
  (OpenTelemetry-compatible). Tracing may be a no-op (default); it never substitutes for audit.

This module is the *sink*; the **emit points** live in the runtime/tooling/safety flows. This spec
states the emit contract and the cross-cutting invariants that other modules must honor.

## Port interface(s)

Signatures copied verbatim from [01-component-model.md](../architecture/01-component-model.md)
(Component 9):

```java
package com.eoiagent.observability;

public interface AuditSink {
    void record(AuditEvent event);   // decision | tool-call | action | approval | model-call
}
public interface TraceCollector {
    Span start(String name, Map<String,Object> attrs);
    void end(Span span, SpanStatus status);
}
```

Contract notes:

- **`AuditSink.record(AuditEvent)`** — **append-only**: the implementation MUST NOT expose update or
  delete; storage adapters use insert-only tables/files (no `UPDATE`/`DELETE`). MUST NOT throw on a
  well-formed event for ordinary I/O hiccups in a way that drops the event — on hard failure it
  throws (so the caller can fail-closed for mutations) but never silently no-ops. SHOULD be
  low-latency / non-blocking enough to wrap every model and tool call; durability ordering must
  preserve happens-before for events sharing a `RunId` (an `APPROVAL/APPROVED` event must be durable
  before the `MUTATION` it authorizes — see invariants). Thread-safe (runs may be parallel across
  sessions; conventions §6).
- **`TraceCollector.start(name, attrs)`** — opens a span; returns a `Span` handle. For the default
  `NoopTraceCollector` this is a cheap no-op handle. Attributes MUST NOT carry secrets or full
  prompts (conventions §7).
- **`TraceCollector.end(span, status)`** — closes the span with `SpanStatus`. Safe to call on a
  no-op span. `Span` and `SpanStatus` are defined in this module (`com.eoiagent.observability`);
  `SpanStatus { OK, ERROR }`.

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `Slf4jAuditSink` | `org.slf4j:slf4j-api` (via `eoiagent-bom`) | Phase 1 | Emits each `AuditEvent` as a structured (key=value / JSON) line to a dedicated audit logger name (`com.eoiagent.audit`), separate from app logging. For dev/low-stakes; not the compliance store. |
| `FileAuditSink` | ours — JDK only | Phase 1 | Append-only newline-delimited JSON to a configured file/dir; never rewrites. OFFLINE-friendly default. Uses `StandardOpenOption.APPEND`. |
| `JdbcAuditSink` | `java.sql` (JDBC; driver via host, e.g. PostgreSQL) | Phase 1 | Insert-only into an append-only table (DDL below). The compliance-grade store for `ON_PREM_HOSTED`/`CLOUD`. |
| `NoopTraceCollector` | ours — JDK only | Phase 1 | Default; spans are no-ops. Keeps tracing optional. |
| `OpenTelemetryTraceCollector` | `io.opentelemetry:opentelemetry-api` (optional, pinned via `eoiagent-bom`) | Phase 4 | Bridges spans to OTel; adapter-only. Optional dependency — absence must not break the build of consumers. |

A `CompositeAuditSink` (ours, Phase 1) fan-outs to multiple sinks (e.g. `File` + `Jdbc`) so audit
can be both local and centralized; it records to all and aggregates failures.

### Suggested append-only DDL (`JdbcAuditSink`, PostgreSQL)

```sql
CREATE TABLE IF NOT EXISTS eoiagent_audit (
    seq          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- monotonic append order
    at           TIMESTAMPTZ   NOT NULL,                            -- AuditEvent.at
    run_id       TEXT          NOT NULL,                            -- RunId.value
    session_id   TEXT          NOT NULL,                            -- SessionId.value
    user_id      TEXT          NOT NULL,                            -- UserId.value
    kind         TEXT          NOT NULL,                            -- AuditKind (CHECK below)
    summary      TEXT          NOT NULL,                            -- AuditEvent.summary
    details      JSONB         NOT NULL DEFAULT '{}'::jsonb,        -- AuditEvent.details
    CONSTRAINT eoiagent_audit_kind_chk CHECK (kind IN
        ('MODEL_CALL','TOOL_CALL','DECISION','APPROVAL','MUTATION','RETRIEVAL','ERROR'))
);
CREATE INDEX IF NOT EXISTS eoiagent_audit_run_idx     ON eoiagent_audit (run_id, seq);
CREATE INDEX IF NOT EXISTS eoiagent_audit_session_idx ON eoiagent_audit (session_id, at);

-- Append-only enforcement (revoke mutation at the DB level for the agent role):
REVOKE UPDATE, DELETE, TRUNCATE ON eoiagent_audit FROM eoiagent_app;
GRANT  INSERT, SELECT            ON eoiagent_audit TO   eoiagent_app;
```

`seq` gives a tamper-evident monotonic order per the happens-before requirement; `(run_id, seq)`
makes the C4 ordering check (APPROVED before MUTATION) a single ordered scan.

## Maven coordinates

This module: `com.eoiagent:eoiagent-observability` (version `0.1.0-SNAPSHOT`, inherits
`eoiagent-bom`).

| Coordinate | Version source | Scope | Why |
|------------|----------------|-------|-----|
| `com.eoiagent:eoiagent-core` | project (`0.1.0-SNAPSHOT`) | compile | `AuditEvent`, `AuditKind`, ids |
| `org.slf4j:slf4j-api` | `eoiagent-bom` | compile | `Slf4jAuditSink` |
| `io.opentelemetry:opentelemetry-api` | `eoiagent-bom` (optional) | compile (optional, adapter only) | `OpenTelemetryTraceCollector` (Phase 4) |
| `org.junit.jupiter:junit-jupiter` | `eoiagent-bom` | test | JUnit 5 |
| `org.assertj:assertj-core` | `eoiagent-bom` | test | assertions |
| `com.h2database:h2` *(or Testcontainers PG)* | `eoiagent-bom` | test | in-memory/containerized DB for `JdbcAuditSink` tests |

JDBC uses `java.sql` from the JDK; the actual driver is provided by the host runtime. No third-party
versions are hardcoded — all via `eoiagent-bom` (conventions §1, 02-domain-model §Maven coordinates).

## Inputs / Outputs

Consumes (from `com.eoiagent.core` / 02-domain-model):

- `AuditEvent(Instant at, RunId run, SessionId session, UserId user, AuditKind kind, String summary, Map<String,Object> details)`
- `AuditKind { MODEL_CALL, TOOL_CALL, DECISION, APPROVAL, MUTATION, RETRIEVAL, ERROR }`

Produces:

- Durable audit records (log line / JSON line / DB row). No return value (`void record`).
- Trace spans (`Span`, `SpanStatus`) — observability only, not persisted as audit.

This module **does not** create `AuditEvent`s — it persists them. Emitters (runtime, tools, safety)
build the events per the flows below.

## Behavior / algorithm

### Emit contract (where `AuditEvent`s come from — referencing the flows)

[04-sequence-flows.md](../architecture/04-sequence-flows.md):

| Event | Emitted by | Flow / step |
|-------|-----------|-------------|
| `MODEL_CALL` | runtime around `LlmGateway.chat`/`chatStream` | A.3, A.6, B (each model turn). Details: `ModelInfo`, token `Usage`, no full prompt. |
| `RETRIEVAL` | runtime around `Retriever.retrieve` | A.2, A.6. Details: query, k, citation source ids. |
| `DECISION` | runtime / guardrails | A.5, A.6, B; every `Guardrail` verdict and routing/answer decision. |
| `TOOL_CALL` | `ToolRegistry.dispatch` after `tool.invoke` | B (read-only) and C (post-mutation result). Details: tool name, ok/error, args summary (no secrets). |
| `APPROVAL` | `ToolRegistry.dispatch` after `ApprovalGate.request` | C.3b. Details: `ApprovalDecision`, `humanSummary`, dry-run preview ref. |
| `MUTATION` | `ToolRegistry.dispatch` after a successful mutating `tool.invoke` | C.3c. **Only after** an `APPROVED` `APPROVAL` event for the same `(run, call)`. |
| `ERROR` | any module on caught exception | conventions §5 — never swallow; record `ERROR` then rethrow/convert. |

### Sink algorithms

**`FileAuditSink.record(event)`** — serialize to one JSON line; append with `APPEND` open option;
flush. Never seeks/overwrites. Rotation by date prefix is allowed (a new file is still append-only).

**`JdbcAuditSink.record(event)`** — single `INSERT` (prepared statement) into `eoiagent_audit`;
`details` → JSONB. No `UPDATE`/`DELETE` path exists in the adapter. On `SQLException`, throw a typed
exception (see Error handling) so a mutating caller fails closed.

**`Slf4jAuditSink.record(event)`** — log at a fixed level to logger `com.eoiagent.audit` with MDC
keys `runId`/`sessionId`/`kind`; structured rendering, no full prompts/secrets.

**`CompositeAuditSink.record(event)`** — call each delegate; collect failures; if any delegate
fails, throw an aggregated typed exception after attempting all (so the centralized store failing
doesn't lose the local file copy).

### Cross-cutting invariants (verbatim from Flow §"Cross-cutting invariants" — assert in tests)

1. **Every** `MODEL_CALL`, `TOOL_CALL`, mutation, and approval emits an `AuditEvent` (C5).
2. **No** mutating `ToolResult` is produced without a preceding `APPROVED` `AuditEvent` (C4).
3. In `OFFLINE`, no flow performs a network call (C2) — enforced by profile checks, asserted by a
   network-deny test harness.
4. `ToolRegistry.visibleTo(ctx)` never returns a tool whose `requiredRole` exceeds `ctx.role` or
   whose capability the profile disables.

This module owns the persistence that makes #1 and #2 *verifiable*: the `(run_id, seq)` ordering lets
a test assert that an `APPROVED` `APPROVAL` row precedes every `MUTATION` row for the same run/call.

## Configuration keys

Prefix `eoiagent.`; registered in `ConfigProvider` defaults (conventions §9.4).

| Key | Type | OFFLINE | ON_PREM_HOSTED | CLOUD | Meaning |
|-----|------|---------|----------------|-------|---------|
| `eoiagent.audit.sink` | enum `slf4j`/`jdbc`/`file` (or csv for composite) | `file` (or `jdbc`) | `jdbc` | `jdbc` | Which `AuditSink` adapter(s). Matches 02-domain-model + 03-profiles. |
| `eoiagent.audit.file.path` | path | `./eoiagent-audit/` | — | — | Directory for `FileAuditSink`. |
| `eoiagent.audit.jdbc.url` | string | — | (LAN PG) | (PG) | JDBC URL for `JdbcAuditSink`. |
| `eoiagent.audit.jdbc.table` | string | — | `eoiagent_audit` | `eoiagent_audit` | Table name. |
| `eoiagent.trace.collector` | enum `noop`/`otel` | `noop` | `noop` | `noop` (`otel` opt-in) | Tracing adapter (Phase 4). |
| `eoiagent.trace.otel.endpoint` | string | — | — | (OTLP endpoint) | Only read by `OpenTelemetryTraceCollector`; OFFLINE keeps `noop`. |

Audit cannot be globally disabled (C5): there is no `eoiagent.audit.enabled=false`. The only choice
is *which* sink, never *whether*.

## Error handling

Typed exceptions (conventions §5, rooted at `EoiAgentException`):

- **`ConfigException`** — bad sink config (unknown sink kind, missing `jdbc.url` for `jdbc`,
  unwritable `file.path`).
- **`AuditException`** *(new, in core, extends `EoiAgentException`)* — durable write failed
  (`SQLException`, `IOException`). Callers performing a **mutation** MUST treat a failed
  `record(APPROVAL/MUTATION)` as fail-closed: do **not** proceed/commit if the authorizing event
  cannot be persisted (preserves invariant #2). For non-mutating audit (e.g. a `MODEL_CALL`) the
  caller may degrade per policy but still must not silently drop — log + surface.

Failure modes: file disk full / DB down → `AuditException`; for `MUTATION`-adjacent events this
aborts the mutation (fail-closed, conventions §5). `TraceCollector` failures are swallowed to
no-ops (tracing is best-effort and never gates behavior). The audit path never swallows an exception
silently (conventions §7).

## Acceptance criteria

- **AC1** Each of `FileAuditSink`, `JdbcAuditSink`, `Slf4jAuditSink` persists an `AuditEvent` with
  all fields round-trippable (`at`, `run`, `session`, `user`, `kind`, `summary`, `details`).
- **AC2** Append-only: `JdbcAuditSink` exposes no update/delete code path; the DDL revokes
  `UPDATE/DELETE`; `FileAuditSink` only appends (verified by writing N events and asserting prior
  bytes are unchanged and order preserved).
- **AC3** (Invariant #1) A driven Flow A/B run produces at least one `MODEL_CALL`, the expected
  `RETRIEVAL`, and a `TOOL_CALL` per tool invocation, all captured by a `RecordingAuditSink`.
- **AC4** (Invariant #2 / C4) For a mutating run, every `MUTATION` event is preceded (by `seq`/order)
  by an `APPROVED` `APPROVAL` event with the same `run`+tool; a `DENIED`/`TIMED_OUT` run yields zero
  `MUTATION` events.
- **AC5** A failed durable write for an `APPROVAL`/`MUTATION` event raises `AuditException` and the
  caller's mutation does not commit (fail-closed) — tested with a sink that throws.
- **AC6** `NoopTraceCollector.start/end` are no-ops and never throw; `eoiagent.trace.collector` must
  be `noop` in OFFLINE (no OTLP egress) — asserted under the network-deny harness.
- **AC7** Audit is not disable-able: there is no config that turns audit off; selecting an unknown
  sink raises `ConfigException`.
- **AC8** `CompositeAuditSink` records to all delegates and, if one fails, still attempts the rest
  and raises an aggregated `AuditException`.

## Test plan

All default tests run with **no network and no live LLM**. `JdbcAuditSink` tests use in-memory H2 (or
a Testcontainers PostgreSQL profile-tagged for CI). The invariant tests use a `RecordingAuditSink`
fake and a stubbed runtime (no model).

- **Unit** — `FileAuditSinkTest` (AC1, AC2), `JdbcAuditSinkTest` (AC1, AC2, AC5 via failing
  connection), `Slf4jAuditSinkTest` (AC1), `NoopTraceCollectorTest` (AC6),
  `CompositeAuditSinkTest` (AC8).
- **Contract** — `AuditSinkContractTest`: abstract JUnit 5 class every sink adapter extends
  (round-trip, append-only, throw-on-failure). `TraceCollectorContractTest` for collectors.
- **Invariant** — `AuditInvariantsTest`: drives stub Flow A/B/C through a `RecordingAuditSink` and
  asserts invariants #1 (AC3) and #2 (AC4) over the ordered event stream.
- **Network-deny** — `ObservabilityOfflineTest` (AC6) under the shared network-deny harness;
  asserts `noop` tracing and `file`/`jdbc`-local audit make no egress.

Run: `mvn -pl eoiagent-observability -am test`

## Dependencies on other modules

- **`eoiagent-core`** — `AuditEvent`, `AuditKind`, ids; hosts the new `AuditException`.
- **`eoiagent-tool`, `eoiagent-runtime`, `eoiagent-safety`** — *emitters*: they construct and
  `record(...)` events at the flow points above. They depend on this module's `AuditSink` port; this
  module does not depend back on them.
- **`eoiagent-config`** (Component 11) — selects the sink/collector via `eoiagent.audit.*` /
  `eoiagent.trace.*`.
- **`eoiagent-eval`** — the [eval harness](eval-harness.md) inspects the audit stream to assert
  tool-call / approval expectations in regression cases.

## Out of scope / deferred

- **Tracing adapters beyond no-op** (`OpenTelemetryTraceCollector`) — **Phase 4.**
- **Audit tamper-proofing** (hash-chaining / signing rows) — **deferred, Phase 4 hardening** (the
  `seq` column is the hook).
- **Audit retention / archival / export** — host concern; deferred, Phase 4.
- **Metrics (counters/histograms)** distinct from spans — deferred; not part of `TraceCollector`.
- **Log shipping / centralized SIEM integration** — host responsibility.

## Related ADRs & flows

- [ADR-0009 — Audit trail & observability](../adr/0009-audit-trail-and-observability.md)
- [ADR-0008 — Mutating actions: approval gate & dry-run](../adr/0008-mutating-actions-approval-gate-dryrun.md) *(C4 ordering)*
- [04-sequence-flows.md](../architecture/04-sequence-flows.md) — Flows A/B/C and **Cross-cutting invariants**
- [conventions.md](../conventions.md) §7 (logging vs audit), §5 (errors), §9 (definition of done #5)
- [01-component-model.md](../architecture/01-component-model.md) — Component 9
