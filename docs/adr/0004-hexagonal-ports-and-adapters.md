---
type: adr
title: "ADR-0004: Organize the platform as Hexagonal Ports & Adapters"
description: "Architecture decision: Organize the platform as Hexagonal Ports & Adapters."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["hexagonal-ports-and-adapters"]
---
# ADR-0004: Organize the platform as Hexagonal Ports & Adapters

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

EOI Agent is an embeddable plain-Java library (constraint **C1**,
[ADR-0001](0001-embeddable-java-no-spring.md)) built largely by AI coding agents, on top of
a base library whose newest pieces are experimental or single-maintainer
([ADR-0003](0003-foundation-langchain4j-bom.md),
[ADR-0005](0005-orchestration-agentic-then-langgraph4j.md),
[ADR-0010](0010-isolate-experimental-deps.md)).

That combination imposes three forces:

1. **Stable contracts up front.** AI agents build best against fixed interfaces with
   machine-checkable acceptance criteria, one module per spec.
2. **Small, independently testable units.** Each capability must be swappable and verifiable
   in isolation, without a live LLM.
3. **Quarantine of risk.** Experimental/bleeding-edge dependencies must not leak instability
   into the host-facing API or the shared core.

The host must also be able to depend on a thin, framework-free surface (C1) and pick
behavior per `DeploymentProfile` (C2/C3).

## Decision

Organize the platform as **Hexagonal Ports & Adapters**:

- **Every capability is a stable port** — a Java interface — living in **`eoiagent-core`**
  (or a component `*-api` package): `LlmGateway`, `Retriever`/`VectorStore`/`DocumentIngestor`,
  `Tool`/`ToolRegistry`, `Planner`/`Orchestrator`/`TaskManager`, `ChatMemory`/`MemoryStore`,
  `Scratchpad`, `ApprovalGate`/`Guardrail`/`PolicyEngine`, `CheckpointStore`,
  `AuditSink`/`TraceCollector`, `AgentService`/`AgentSession`, `ConfigProvider`.
- **Adapters implement ports in their component module** (e.g. `eoiagent-model`,
  `eoiagent-knowledge`) and are the **only** place a third-party library may be imported.
- **Strict dependency direction** ([`../conventions.md`](../conventions.md) §2,
  [`../architecture/01-component-model.md`](../architecture/01-component-model.md)
  §Dependency direction):

  ```
  host app ──► host-integration-api ──► core (ports + domain types)
                                          ▲
                   every adapter module ──┘   (adapters import core; core imports no adapter)
  ```

  Ports never import adapters. **Core imports no agent framework** (no LangChain4j, no
  LangGraph4j, no MCP).
- Changing a port signature is an **ADR-worthy event**.
- **Enforced by architecture tests** (JDK Class-File API dependency-rule tests: dependency direction, no-framework-in-core) that are part
  of Phase 0 and the per-ticket Definition of Done.

This is the single most important decision for *AI-agent-built* code: contracts are fixed,
adapters are small independently testable units, and experimental deps are quarantined
behind ports.

## Consequences

**Positive**
- Modules compose without rework; an adapter can be replaced (e.g. `AgenticOrchestrator` →
  `LangGraphOrchestrator`) without touching core or the host.
- Risky dependencies stay in adapter modules; instability cannot reach the stable API.
- Each port has a shared contract-test suite every adapter must pass — ideal for AI agents.

**Negative / follow-ups**
- More interfaces and wiring boilerplate than a direct-call design.
- Some adapters are thin wrappers over LangChain4j types (e.g. `VectorStore` over an
  `EmbeddingStore`); the indirection is deliberate, to preserve swappability.

**Risks / mitigation**
- Risk: ports drift toward leaking adapter concepts. Mitigation: architecture-test rules plus the
  "port change = ADR" rule keep the boundary honest.

## Alternatives considered

- **Layered monolith** — fewer interfaces, but it couples experimental/bleeding-edge
  dependencies directly into core and makes isolated testing and adapter swaps hard.
  Rejected.
- **Framework-native wiring** (Spring/Quarkus beans across layers) — violates C1 and
  defeats the embeddable goal. Rejected.
