---
type: roadmap
title: "Roadmap"
description: "Phased build plan."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["roadmap"]
---
# Roadmap

> Phased build plan. Each phase has a **goal**, **scope**, **exit criteria**, and a
> **dependency** on the prior phase. Agent-sized work items are in [`backlog.md`](backlog.md).
> Sequencing is deliberate — do not pull later-phase work forward (e.g. mutating actions,
> LangGraph4j, pgvector) without an ADR.

## Principles for sequencing

1. **Contracts before adapters.** Phase 0 freezes ports/types so all later work composes.
2. **Read-only before mutating.** The MVP (Phase 1) cannot change state; mutation arrives in
   Phase 2 only once the `ApprovalGate` exists.
3. **Offline before online extras.** Every phase ships an OFFLINE-passing eval suite before
   online-only features are added.
4. **Simple orchestration before stateful.** `langchain4j-agentic`/ReAct in Phases 1–2;
   LangGraph4j only in Phase 3 when cyclical/checkpointed investigation needs it.
5. **Core first, then packs.** Phases 0–4 build the reusable **CORE** plus the **reference pack**
   that proves it. Onboarding each real product is a separate, repeatable **pack track** (copy the
   reference pack, fill the eight providers, add a golden set) that runs against whatever core
   capabilities exist — it is **not** a new phase. See
   [`../architecture/05-core-and-application-packs.md`](../architecture/05-core-and-application-packs.md) §7.

## Milestones (set exact dates at kickoff)

| Milestone | = end of | What a stakeholder sees |
|-----------|----------|-------------------------|
| **M1 — Internal demo** | Phase 1 | Embedded agent answers page-aware product questions over docs/config, returns inline answers and `NavigationIntent` redirects, runs fully offline and online; every call audited. |
| **M2 — Beta** | Phase 2 | Agent plans multi-step work, takes **gated** mutating actions (dry-run + human approval), delegates to SQL/analysis/pipeline sub-agents; RBAC by user tier; pgvector option. |
| **M3 — Production** | Phase 3 + Phase 4 | Resumable issue-investigation workflows (checkpoint/HITL/time-travel), long-term memory, tracing, security-reviewed, packaged. |

> **On dates:** the team is 4+ engineers with **most code produced by AI agents**, so throughput
> is gated by review/integration, not typing. Effort below is indicative engineer-weeks of
> *review + integration*; set calendar dates at kickoff against the team's actual cadence. Do not
> treat these as commitments.

| Phase | Indicative effort (review/integration) |
|-------|----------------------------------------|
| Phase 0 | ~1–2 wk |
| Phase 1 (M1) | ~3–5 wk |
| Phase 2 (M2) | ~4–6 wk |
| Phase 3 | ~4–6 wk |
| Phase 4 (M3) | ~2–4 wk |

---

## Phase 0 — Foundations

**Goal:** freeze the contracts and the test scaffold so modules can be built in parallel.

**Scope:**
- Maven multi-module skeleton + `eoiagent-bom` (imports `langchain4j-bom:1.16.3`), JDK 25,
  `maven.compiler.release=25`.
- `eoiagent-core`: all **domain types** (records/enums from
  [`02-domain-model.md`](../architecture/02-domain-model.md)) and all **port interfaces** from
  [`01-component-model.md`](../architecture/01-component-model.md) (compile-only).
- Exception hierarchy (`EoiAgentException` + subtypes).
- `eoiagent-app-api`: the **Application Pack SPI** (interfaces + records, compile-only) +
  `eoiagent-platform` (`PlatformBuilder`/`AgentPlatform` + `PackValidator`).
- **ArchUnit** architecture tests (dependency direction; no agent framework in `core`; **core
  never imports a pack**; `eoiagent-app-api` imports only core domain types).
- `ConfigProvider` + adapters + the capability matrix / `featureEnabled` gating.
- A deterministic **stub `LlmGateway`** + test fixtures (so all later tests run with no network /
  no live LLM).
- `eoiagent-eval` harness scaffold (case loader + runner).

**Exit criteria:** `mvn verify` green with empty adapters; ArchUnit rules (incl. core/pack
direction) enforced; the SPI compiles and `PlatformBuilder.start()` wires a stub pack; stub
gateway + eval scaffold usable; capability matrix unit-tested per profile.

**Tickets:** T-001…T-010.

---

## Phase 1 — MVP (read-only RAG + tools + page-context help)

**Goal:** a useful, safe, offline-capable in-product assistant — the M1 demo.

**Scope (maps to the brainstorm's "product help on every page"):**
- **Model:** `OllamaChatAdapter`, `OpenAiCompatibleChatAdapter` (JDK HttpClient),
  `RoutingLlmGateway` (profile routing + fallback, offline fail-closed) + streaming.
- **Knowledge:** `OnnxEmbeddingAdapter` (all-MiniLM), `InMemoryVectorStore`, loaders
  (ProductDoc/ConfigFile/SchemaConfig), `DocumentIngestor`, `Retriever`.
- **Tools:** `JavaApiTool` + `ToolRegistry` — **read-only** host Java-API tools only.
- **Memory:** Window/TokenWindow + `InMemoryMemoryStore`. **Scratchpad:** `InMemoryScratchpad`.
- **Runtime:** `ReActOrchestrator` + `AgenticOrchestrator` (behind the `Orchestrator` port).
- **Safety:** `Lc4jInputGuardrail` (prompt-injection + PII).
- **Audit:** `Slf4jAuditSink` + `FileAuditSink` wired through the runtime.
- **Host:** `AgentService`/`AgentSession`, `AgentAnswer`, **`NavigationIntent`**, page-context
  help (Flow A) + streaming.
- **Reference Application Pack** (`eoiagent-app-reference`): the MVP is wired and demoed **through
  a pack** via `eoiagent-platform`, proving the reuse model end-to-end (and serving as the
  copy-to-start template).
- **Eval:** golden product-help Q&A + navigation-intent assertions, OFFLINE + one online profile.

**Exit criteria (M1):** Flow A end-to-end offline and online, **assembled from the reference
Application Pack** by `PlatformBuilder`; every model/tool/retrieval/decision audited (with
`AppId`); eval suite passes in OFFLINE CI; no mutating capability exists yet.

**Depends on:** Phase 0. **Tickets:** T-101…T-116.

---

## Phase 2 — Planning + mutating actions + delegation

**Goal:** the agent can plan and *act* — safely — and split work across sub-agents (M2 beta).

**Scope:**
- **Planning:** `Planner` + `TaskManager` (multistep plan/todo, visible to host).
- **Safety/act:** `CallbackApprovalGate` + dry-run + `ApprovalHandler` SPI;
  `RoleBasedPolicyEngine` (RBAC); `ToolRegistry` mutating dispatch enforcement (Flow C).
- **Mutating tools:** pipeline author/run, config edit, job trigger — all behind the gate.
- **Delegation:** `SupervisorOrchestrator` + Analysis/SQL/Pipeline sub-agents (Flow D).
- **Scale-up adapters:** `PgVectorStore` (≥1.16.3), `JdbcAuditSink`, `PostgresMemoryStore`,
  `SummarizingChatMemory`, `AdvancedRetriever`, `McpToolAdapter`, `Lc4jOutputGuardrail`.
- **Eval:** SQL-gen, analysis, and mutating-with-approval scenarios.

**Exit criteria (M2):** no mutating `ToolResult` without a preceding `APPROVED` audit event
(asserted); RBAC enforced per tier; supervisor delegates and aggregates; eval green offline+online.

**Depends on:** Phase 1. **Tickets:** T-201…T-211.

---

## Phase 3 — Stateful investigation + durability

**Goal:** long-running, resumable issue investigation — the hardest control flow.

**Scope:**
- `LangGraphOrchestrator` (`org.bsc.langgraph4j:*:1.8.19`) behind the `Orchestrator` port.
- `CheckpointStore`: InMemory + Postgres saver — checkpoint/resume; breakpoints + HITL pauses;
  time-travel/replay (Flow E).
- Investigation tools over events/alerts/incidents/cases (host Java API) + playbooks.
- `VectorLongTermMemory` (cross-session).
- **Eval:** investigation scenarios + a resume-after-restart test.

**Exit criteria:** an investigation survives a process restart and resumes from its last
checkpoint; mutating/escalation nodes pause at a breakpoint for approval; time-travel replay works.

**Depends on:** Phase 2. **Tickets:** T-301…T-306.

---

## Phase 4 — Hardening

**Goal:** production-ready (M3).

**Scope:**
- `OpenTelemetryTraceCollector` + spans across the runtime.
- Performance pass (latency budgets; embedding/retrieval tuning).
- Security review (prompt-injection red-team; audit completeness; **offline network-deny test**).
- Packaging (shaded uber-jar / JPMS `module-info` as the host requires).
- Eval hardening + regression baseline + CI gates across all profiles.

**Exit criteria (M3):** security review signed off; OFFLINE network-deny test proves zero egress;
regression baseline enforced in CI; artifact consumable by the host build.

**Depends on:** Phase 3. **Tickets:** T-401…T-405.

---

## Open questions to resolve at kickoff (from the brainstorm)

These were flagged "not sure" and can be decided without re-forking the architecture (the ports
absorb them):

- **Task durability scope** — confirmed handled by the Phase-3 `CheckpointStore` + LangGraph4j
  adapter; if early durability is needed, pull `InMemoryCheckpointStore` into Phase 2.
- **Licensing/compliance regime** — confirm Apache-2/MIT-only policy; both substrates qualify
  (LangChain4j Apache-2, LangGraph4j MIT). Record as an ADR if a regime is mandated.
- **Definition of done / labeled set** — bootstrapped by [`../specs/eval-harness.md`](../specs/eval-harness.md);
  grow the golden set each phase.
