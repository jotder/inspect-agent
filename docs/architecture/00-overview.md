# 00 — Architecture Overview

> **Audience:** humans and AI coding agents. Read this first, then
> [`01-component-model.md`](01-component-model.md) and [`02-domain-model.md`](02-domain-model.md).

## 1. What we are building

The **Enterprise Operational Intelligence Agent Platform** (codename: **EOI Agent**) is an
**embeddable "Agent Operating System" that runs *inside* a host Java application** — not a
chatbot and not a thin LLM wrapper.

It gives a host product an embedded operational-intelligence agent that can:

- Analyze metadata and database schemas.
- Author / explain ETL pipelines and generate SQL.
- Investigate production issues (events, alerts, incidents, cases).
- Execute operational tasks (gated by human approval).
- Answer in-product help questions, page-aware, and route the user to the right
  KPI / report page with the right parameters.
- Understand the host's stack (DuckDB, Calcite, Iceberg, NiFi, PostgreSQL, TOON config,
  rule engine, product docs).

The same library must run **fully offline** (local model + local embeddings, no network) **or
online** (hosted model), selected per client deployment.

**It is reusable across products.** The platform is split into a **reusable CORE** (the engine —
product-agnostic) and a **project-specific Application Pack** (one per product, supplying its
models, domain knowledge, tools, navigation, prompts, roles, and config through a typed SPI). A
new product = a new pack, never a fork of core. This split is detailed in
[`05-core-and-application-packs.md`](05-core-and-application-packs.md) and
[ADR-0011](../adr/0011-core-and-application-pack-split.md).

## 2. Hard constraints (these fork the architecture — do not violate)

| # | Constraint | Consequence |
|---|------------|-------------|
| C1 | **Embeddable plain-Java library.** No Spring Boot, no Quarkus, no DI framework requirement. Drops into a host app, CLI, or daemon. | Rules out Spring AI and Quarkus LangChain4j extension. We expose plain constructors/builders + ports. |
| C2 | **Offline-capable.** Must run with zero network: local LLM **and** local embeddings, in-process or via localhost server. | In-process ONNX embeddings + `InMemoryEmbeddingStore` + Ollama / OpenAI-compatible local server are first-class, not afterthoughts. |
| C3 | **Online-capable too**, equal priority. Deployment chooses. | A `DeploymentProfile` selects model placement + feature gating. Both paths must be tested. |
| C4 | **Mutating actions require human approval.** v1 may take mutating actions (create/run pipelines, edit configs, write data) but every one passes an approval gate and supports dry-run. | `ApprovalGate` + dry-run are core, not optional. Tools are classified read-only vs mutating. |
| C5 | **Audit everything.** Persisted audit trail of every decision, tool call, and action. | `AuditSink` is wired through the runtime; nothing mutating happens off-audit. |
| C6 | **Reach the host via its Java API.** The suite's functionality is exposed to the agent primarily as Java methods turned into tools. | Tools dominate; RAG covers docs/config text. |
| C7 | **JDK 25, Maven.** | See [ADR-0002](../adr/0002-jdk25-maven-httpclient.md). Standardize on the JDK `HttpClient` transport (avoid the Netty event-loop shutdown issue on JDK 25). |
| C8 | **English only.** | No i18n layer in v1. |

## 3. Design principles

1. **Ports & Adapters (Hexagonal).** Every capability is a stable **port** (Java interface)
   with swappable **adapters**. This is the single most important decision for *AI-agent-built*
   code: contracts are fixed up front, each adapter is a small independently-testable unit, and
   experimental/risky dependencies are quarantined behind ports. See
   [ADR-0004](../adr/0004-hexagonal-ports-and-adapters.md).
2. **Quarantine experimental dependencies.** `langchain4j-agentic`, `langchain4j-guardrails`,
   and LangGraph4j are experimental or single-maintainer. They appear *only* inside adapter
   modules, never in core or in the host-facing API. See
   [ADR-0010](../adr/0010-isolate-experimental-deps.md).
3. **Offline is the default test target.** If a feature can't run offline, it is gated by a
   `DeploymentProfile`, not assumed.
4. **Typed, structured outputs.** The agent returns typed artifacts (`SqlArtifact`,
   `PipelineSpec`, `NavigationIntent`, `AgentAnswer`) — not just free text — so the host can act
   on them.
5. **Safety is in the runtime, not the prompt.** Approval, policy/RBAC, and audit are enforced
   in code paths, not merely requested in a system prompt.
6. **Deterministic seams for agents.** Stable interfaces, machine-checkable acceptance criteria,
   one module per spec. See [`../conventions.md`](../conventions.md).
7. **Core is reusable; the Pack is product-specific.** Mechanism (engine, ports, adapters) is
   CORE; policy/content/bindings (models, knowledge, tools, navigation, prompts, roles, config)
   live in the per-product **Application Pack**. CORE never depends on a pack. See
   [`05-core-and-application-packs.md`](05-core-and-application-packs.md).

## 4. C4 — Context (level 1)

```
                         +-------------------------------------+
                         |        Host Java Application         |
                         |  (the product: web UI, services)     |
                         |                                      |
   End users  ───────►   |   Pages / KPIs / Reports             |
 (admin, support,        |        │  embeds                     |
  analyst, user)         |        ▼                             |
                         |   ┌───────────────────────────┐      |
                         |   │   EOI Agent (this library) │      |
                         |   └───────────────────────────┘      |
                         +-------------------------------------+
                                   │ tools (Java API)    │ models
                                   ▼                     ▼
                         Suite Java API           Local LLM (Ollama / llama.cpp / vLLM)
                         (metadata, SQL,          Hosted LLM (OpenAI / Anthropic / Gemini)
                          pipeline, ops,
                          incidents)              Knowledge corpus (docs, config files, schemas)
```

## 5. C4 — Containers (level 2): the Agent OS

```
  Application Pack (project-specific) ──provides──► [ models · knowledge · tools ·
       navigation · prompts · roles · config ]   assembled by eoiagent-platform into ▼
+-----------------------------------------------------------------------+
|                 EOI Agent CORE (reusable engine — embeddable)         |
|                                                                       |
|  HOST INTEGRATION  (AgentService / AgentSession / streaming)          |
|        │  page-context in → AgentAnswer | NavigationIntent out        |
|        ▼                                                              |
|  AGENT RUNTIME / ORCHESTRATION                                         |
|   Planner · Orchestrator (ReAct + workflow) · TaskManager             |
|        │           │            │            │                        |
|        ▼           ▼            ▼            ▼                         |
|   MODEL ACCESS  KNOWLEDGE/RAG  TOOLS     MEMORY + SCRATCHPAD           |
|   (LlmGateway)  (Retriever)   (Registry)                              |
|                                                                       |
|  CROSS-CUTTING (wraps every action):                                  |
|   SAFETY/GOVERNANCE (ApprovalGate · Guardrails · PolicyEngine)        |
|   PERSISTENCE/CHECKPOINTING (CheckpointStore)                         |
|   OBSERVABILITY/AUDIT (AuditSink · TraceCollector)                    |
|   CONFIG / DEPLOYMENT PROFILES                                        |
+-----------------------------------------------------------------------+
```

The eleven components and their port interfaces are defined in
[`01-component-model.md`](01-component-model.md). All eleven are **CORE**; the per-product
**Application Pack** supplies their inputs (which model, which corpus, which tools, which pages,
which prompts/roles/config) and `eoiagent-platform` assembles them — see
[`05-core-and-application-packs.md`](05-core-and-application-packs.md).

## 6. Substrate (chosen technology)

| Layer | Choice | Notes |
|-------|--------|-------|
| Base AI library | **LangChain4j 1.16.3** (BOM-pinned) | models, RAG, tools, memory, MCP. [ADR-0003](../adr/0003-foundation-langchain4j-bom.md) |
| Orchestration (MVP) | **`langchain4j-agentic`** (experimental) behind our `Orchestrator` port | [ADR-0005](../adr/0005-orchestration-agentic-then-langgraph4j.md) |
| Orchestration (Phase 3) | **LangGraph4j 1.8.19** behind the same port, for cyclical/checkpointed flows | [ADR-0005](../adr/0005-orchestration-agentic-then-langgraph4j.md) |
| Embeddings (offline) | in-process ONNX `AllMiniLmL6V2EmbeddingModel` | zero network |
| Vector store | `InMemoryEmbeddingStore` (embedded) → **pgvector** (prod) | [ADR-0007](../adr/0007-vector-store-inmemory-then-pgvector.md) |
| Local model transport | OpenAI-compatible `baseUrl` client (Ollama / llama.cpp / vLLM / LM Studio) | [ADR-0006](../adr/0006-local-llm-portability-openai-compatible.md) |
| Runtime | **JDK 25**, **Maven**, **JDK HttpClient** transport | [ADR-0002](../adr/0002-jdk25-maven-httpclient.md) |

**Rejected alternatives:** Spring AI (violates C1), Google ADK-Java (Gemini-first, heavier
footprint, routes local models through LangChain4j anyway). See
[ADR-0001](../adr/0001-embeddable-java-no-spring.md) and
[ADR-0003](../adr/0003-foundation-langchain4j-bom.md).

## 7. How the pieces map to the brainstorm checklist

| Deep-agent need | Where it lives |
|-----------------|----------------|
| Model access + offline/online routing | Model Access (`LlmGateway`) |
| RAG (embeddings, vector store, loaders, retrieval) | Knowledge/RAG |
| Tool calling (Java API + MCP) | Tools (`ToolRegistry`) |
| Memory (short-term, summarized, long-term) | Memory |
| Planning / todos | Agent Runtime (`Planner`, `TaskManager`) |
| Sub-agents / delegation | Agent Runtime (supervisor + workers) |
| Orchestration / control flow / HITL / checkpoint | Agent Runtime + Persistence/Checkpointing |
| Virtual filesystem / scratchpad | Scratchpad |
| Guardrails | Safety/Governance |
| Observability / evals | Observability/Audit + `eval-harness` |

## 8. Roadmap at a glance

Phase 0 Foundations → Phase 1 MVP (read-only, RAG, product help) → Phase 2 Planning + mutating
actions + delegation → Phase 3 Stateful investigation + durability → Phase 4 Hardening. Full
detail in [`../roadmap/roadmap.md`](../roadmap/roadmap.md); agent-sized tickets in
[`../roadmap/backlog.md`](../roadmap/backlog.md).
