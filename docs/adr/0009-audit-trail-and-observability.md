---
type: adr
title: "ADR-0009: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing"
description: "Architecture decision: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["audit-trail-and-observability"]
---
# ADR-0009: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The agent makes consequential decisions and takes mutating operational actions
([ADR-0008](0008-mutating-actions-approval-gate-dryrun.md)) inside a host product, often in
regulated/on-prem environments. Constraint **C5** is hard: **persist an audit trail of every
decision, tool call, and action.**

Two concerns are routinely conflated and must be kept separate:

- **Audit** — durable, structured, append-only, compliance-grade, **never disabled**.
- **Logging** — SLF4J, for developers, may be sampled/leveled, **not** a compliance record
  ([`../conventions.md`](../conventions.md) §7).

Developers also want runtime observability (latency, spans) in dev, but that is optional and
must not be confused with the audit obligation.

## Decision

Separate **audit** (mandatory) from **tracing** (optional):

- **`AuditSink.record(AuditEvent)`** captures every significant event, wired through the
  runtime and **never disabled** (constraint C5). `AuditEvent` is **append-only** and carries
  `at, run, session, user, kind, summary, details`
  ([`../architecture/02-domain-model.md`](../architecture/02-domain-model.md) §Observability).
  `AuditKind` covers **`MODEL_CALL`, `TOOL_CALL`, `DECISION`, `APPROVAL`, `MUTATION`,
  `RETRIEVAL`, `ERROR`**.
- Adapters: **`JdbcAuditSink`** (append-only table), **`FileAuditSink`**, **`Slf4jAuditSink`**;
  selected by `eoiagent.audit.sink = slf4j | jdbc | file` (per-profile defaults: OFFLINE
  `file`/`jdbc`, ON_PREM_HOSTED/CLOUD `jdbc`).
- **`TraceCollector` is optional** dev observability — `NoopTraceCollector` by default,
  `OpenTelemetryTraceCollector` when enabled.
- **Invariants:** every mutating action and every model call **MUST** emit an `AuditEvent`
  ([`../architecture/01-component-model.md`](../architecture/01-component-model.md)
  §Component 9); and **no mutation is recorded without a preceding `APPROVAL` event**
  ([ADR-0008](0008-mutating-actions-approval-gate-dryrun.md)). Per
  [`../conventions.md`](../conventions.md) §5, a caught exception emits
  `AuditEvent(ERROR, …)` rather than being swallowed.

**Audit ≠ logging.**

## Consequences

**Positive**
- **Compliance-ready** out of the box: a durable, structured, append-only record of what the
  agent decided and did, independent of log levels.
- Optional OpenTelemetry tracing gives dev observability without touching the audit path.

**Negative / follow-ups**
- **Storage and retention** of the audit trail must be managed (table growth, archival,
  access control) — an operational responsibility per deployment.
- **Slight overhead** per event (write on the hot path); the append-only design and async
  sink options keep it bounded.

**Risks / mitigation**
- Risk: an action slips through un-audited. Mitigation: audit is wired in the runtime
  (`ToolRegistry.dispatch`, model calls), not at call sites, and the mutation⇒prior-approval
  invariant is test-asserted.

## Alternatives considered

- **Rely on SLF4J logs** as the record — not durable, not structured, level-dependent, and
  not a compliance artifact. Rejected (audit is a distinct concern from logging).
- **No audit trail** — directly violates constraint C5 and the regulated-deployment
  requirement. Rejected.
