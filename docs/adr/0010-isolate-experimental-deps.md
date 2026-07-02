---
type: adr
title: "ADR-0010: Quarantine experimental/single-maintainer dependencies behind ports + feature flags"
description: "Architecture decision: Quarantine experimental/single-maintainer dependencies behind ports + feature flags."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["isolate-experimental-deps"]
---
# ADR-0010: Quarantine experimental/single-maintainer dependencies behind ports + feature flags

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

Some of the most useful pieces of our substrate are **not yet stable**:

- **`langchain4j-agentic`** and **`langchain4j-guardrails`** are **experimental**,
  **beta-suffixed** (`1.16.x-betaNN`), and their APIs may churn release-to-release.
- **`org.bsc.langgraph4j:*`** (pinned `1.8.19`) is **single-maintainer** — a bus-factor risk.

These power the MVP orchestrator ([ADR-0005](0005-orchestration-agentic-then-langgraph4j.md)),
input/output guardrails, and Phase 3 stateful flows. We want their capability without letting
their instability reach the stable host-facing API or the shared core. This is design
principle 2 in [`../architecture/00-overview.md`](../architecture/00-overview.md) §3 and the
dependency rule in [`../conventions.md`](../conventions.md) §2, made concrete here.

## Decision

**Quarantine** these dependencies behind ports and feature flags:

- **`langchain4j-agentic`, `langchain4j-guardrails`, and `org.bsc.langgraph4j:*` appear ONLY
  inside adapter modules** — never in **`eoiagent-core`** and never in the
  **host-integration API** (`eoiagent-host`). They sit behind the `Orchestrator` and
  `Guardrail` ports respectively ([ADR-0004](0004-hexagonal-ports-and-adapters.md)).
- **Gated by `Feature` flags** via `ConfigProvider.featureEnabled(...)` and the
  [capability matrix](../architecture/03-deployment-profiles.md#capability-matrix) (e.g.
  `LANGGRAPH_CHECKPOINTING`) — **presence on the classpath never enables a feature**.
- **Versions are pinned centrally:** the experimental modules' `-betaNN` suffixes are
  resolved at build time and pinned in **`eoiagent-bom`**; LangGraph4j is pinned directly at
  `1.8.19`. No module pom hardcodes these
  ([`../architecture/02-domain-model.md`](../architecture/02-domain-model.md) §Version policy).
- **Covered by contract tests:** each port has a shared test suite every adapter must pass,
  so an unstable adapter can be **swapped** (e.g. replace the agentic orchestrator, or fork
  LangGraph4j) without touching core or the host.
- **Enforced by dependency-direction architecture tests** (JDK Class-File API, JEP 484) (Phase 0, Definition of Done).

## Consequences

**Positive**
- **Churn and bus-factor are contained:** an upstream breaking change or abandonment hits one
  adapter module, not the platform's stable API.
- Features ride on flags, so an install can ship without a risky capability simply by leaving
  its flag off — provably, not just by hiding UI.

**Negative / follow-ups**
- **Small adapter-maintenance cost:** chasing `-betaNN` bumps and re-running the port
  contract tests when an experimental API shifts.
- A thin amount of port/adapter indirection over what direct use would require — the same
  trade-off accepted in [ADR-0004](0004-hexagonal-ports-and-adapters.md).

**Risks / mitigation**
- Risk: an experimental type leaks into a port signature. Mitigation: ports live in
  `eoiagent-core`/`*-api` which import no framework, asserted by the architecture tests; a port change is
  ADR-worthy.

## Alternatives considered

- **Use these libraries directly in core / the host API** — less boilerplate, but it couples
  instability (API churn) and bus-factor risk straight into the stable API every host and
  every other module depends on. Rejected.
