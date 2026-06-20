# ADR-0005: Hybrid orchestration — langchain4j-agentic for MVP, LangGraph4j for stateful flows, behind one Orchestrator port

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The Agent Runtime drives the plan → act → observe → reflect loop and the workflow patterns
(sequential, parallel, conditional, loop, supervisor) and, later, long-running stateful
investigations with checkpoint/replay and human-in-the-loop.

No single orchestration library cleanly covers both ends well:

- **`langchain4j-agentic`** is ergonomic for the MVP workflow patterns, but is
  **experimental** (beta-suffixed, API may churn) and weak for long-running, resumable,
  cyclical investigations.
- **LangGraph4j** (`org.bsc.langgraph4j`) is strong for cyclical, checkpointed graphs with
  breakpoints, time-travel, and HITL — but is **single-maintainer** (bus-factor risk) and
  more boilerplate-heavy for simple flows.

Per Ports & Adapters ([ADR-0004](0004-hexagonal-ports-and-adapters.md)) and the
experimental-dependency quarantine ([ADR-0010](0010-isolate-experimental-deps.md)), we must
not couple the runtime to either library.

## Decision

Adopt a **hybrid orchestration** strategy behind **one `Orchestrator` port**:

- Define **our own `Planner` / `Orchestrator` / `TaskManager` ports** in
  `com.eoiagent.runtime` (`eoiagent-runtime`). These are the stable contract.
- **MVP adapter — `AgenticOrchestrator`** on **`dev.langchain4j:langchain4j-agentic`**:
  sequential / parallel / conditional / loop / supervisor patterns. Supervisor + isolated
  workers (analysis / SQL / pipeline) are nested `Orchestrator`/AI-Service invocations.
- **Phase 3 adapter — `LangGraphOrchestrator`** on **`org.bsc.langgraph4j:*:1.8.19`**
  (`langgraph4j-core`, `langgraph4j-langchain4j`, and the Postgres checkpoint saver) for
  cyclical investigation with **checkpoint/replay, breakpoints, time-travel, and HITL**.
  This pairs with the `CheckpointStore` port and `PostgresCheckpointStore`, gated by the
  `LANGGRAPH_CHECKPOINTING` feature.
- A simple **`ReActOrchestrator`** (LC4j tools + our own loop) remains as a fallback/simple
  path.

The host and core depend only on the `Orchestrator` port; the orchestration engine is a
configuration/wiring choice, never a compile-time dependency of core.

## Consequences

**Positive**
- Right tool per phase: low-boilerplate agentic for the MVP; durable, replayable graphs for
  long investigations — without locking the platform to either.
- The experimental `langchain4j-agentic` module and the single-maintainer LangGraph4j are
  each isolated in one adapter module behind the port.

**Negative / follow-ups**
- Two orchestration adapters to maintain plus a shared `Orchestrator` contract-test suite
  both must pass.
- Behavioral parity across adapters (e.g. how a plan maps to graph nodes) needs deliberate
  contract tests.

**Risks / mitigation**
- **Bus-factor (LangGraph4j single maintainer):** mitigated by the MIT license (we can fork
  if abandoned) **and** the `Orchestrator` port (we can replace the adapter without touching
  core or the host).
- **API churn (`langchain4j-agentic` beta):** confined to the adapter; the `-betaNN` suffix
  is pinned in `eoiagent-bom` ([ADR-0010](0010-isolate-experimental-deps.md)).

## Alternatives considered

- **LangGraph4j-first (also for the MVP)** — more boilerplate for simple flows and brings
  the single-maintainer bus-factor risk forward into Phase 1. Rejected for the MVP.
- **`langchain4j-agentic`-only (long term)** — weak for long-running, resumable, cyclical
  investigations with checkpoint/replay and time-travel. Rejected as the long-term answer.
- **Build our own graph engine** — reinvents checkpointing, replay, and HITL that
  LangGraph4j already provides. Rejected (reinvention).
