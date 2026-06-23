# Enterprise Operational Intelligence Agent Platform (EOI Agent)

An **embeddable, plain-Java "Agent Operating System"** that runs *inside* a host application —
not a chatbot, not an LLM wrapper. It analyzes metadata and schemas, authors ETL pipelines and
generates SQL, investigates production issues, executes operational tasks (behind human
approval), and provides page-aware in-product help. It is designed to run **fully offline or
online**, selected per client deployment.

**It is reusable across products:** a single **CORE** engine plus a per-product **Application
Pack** that supplies that product's models, domain knowledge, tools, navigation, prompts, roles,
and config through a typed SPI. A new product = a new pack, not a fork of core — see
[`docs/architecture/05-core-and-application-packs.md`](docs/architecture/05-core-and-application-packs.md).

> **Status:** Phases 0–2 are **implemented** and **Phase 3 has started** — an 18-module Maven reactor
> that builds green and runs **fully offline** (JDK 25, Maven). The CORE engine assembles a working
> `AgentService` from an Application Pack via `eoiagent-platform`; the bundled **Acme Lakehouse**
> reference pack ([`eoiagent-app-reference`](eoiagent-app-reference)) and the runnable demos in
> [`eoiagent-examples`](eoiagent-examples) show it end-to-end. Phase 2 added planning, **gated
> mutating actions**, supervisor delegation, summarizing memory, advanced retrieval, MCP tools, and
> output guardrails; Phase 3 begins with a checkpointed investigation orchestrator (LangGraph4j).
> New here? Start with [`HOWTO.md`](HOWTO.md). The agent-friendly specs in [`docs/`](docs/) remain the source of truth.

## Start here

| If you are… | Read |
|-------------|------|
| **An AI coding agent** | [`AGENTS.md`](AGENTS.md) — the build guide. |
| **Building, running, or embedding it** | [`HOWTO.md`](HOWTO.md) — practical task-by-task guide. |
| **Wanting to see it run** | [Try it — runnable demos](#try-it--runnable-demos) ([`eoiagent-examples`](eoiagent-examples)) |
| New to the project | [`docs/architecture/00-overview.md`](docs/architecture/00-overview.md) |
| Looking for the contracts | [`docs/architecture/01-component-model.md`](docs/architecture/01-component-model.md) + [`docs/architecture/02-domain-model.md`](docs/architecture/02-domain-model.md) |
| Wondering *why* a choice was made | [`docs/adr/`](docs/adr/) |
| Implementing a CORE module | [`docs/specs/`](docs/specs/) |
| Understanding core vs product-specific | [`docs/architecture/05-core-and-application-packs.md`](docs/architecture/05-core-and-application-packs.md) |
| Onboarding a new product (build a pack) | [`docs/specs/application-pack.md`](docs/specs/application-pack.md) + [`docs/specs/reference-app-pack.md`](docs/specs/reference-app-pack.md) |
| Planning the work | [`docs/roadmap/roadmap.md`](docs/roadmap/roadmap.md) + [`docs/roadmap/backlog.md`](docs/roadmap/backlog.md) |

## Documentation map

```
README.md                     ← you are here
AGENTS.md                     master guide for AI coding agents
docs/
  conventions.md              binding rules (naming, deps, errors, testing, DoD)
  glossary.md                 shared vocabulary
  architecture/
    00-overview.md            vision, constraints, C4 context+containers, substrate
    01-component-model.md     11 CORE ports & their adapters + the SPI/bootstrap reuse layer
    02-domain-model.md        canonical types, config keys, Maven coordinates
    03-deployment-profiles.md OFFLINE / ON_PREM_HOSTED / CLOUD + capability matrix
    04-sequence-flows.md      runtime flows (incl. Flow 0 bootstrap) + invariants
    05-core-and-application-packs.md  reusable CORE vs per-product Application Pack (the reuse model)
  adr/                        0001–0011 architecture decision records
  specs/                      one implementable spec per module (incl. application-pack, reference-app-pack)
  roadmap/
    roadmap.md                phases 0–4
    backlog.md                agent-sized tickets with acceptance criteria
```

## What's implemented

An 18-module Maven reactor (`mvn clean install`, JDK 25) — the CORE engine plus the reuse layer:

- **CORE engine** — `eoiagent-core` (ports + domain types) with adapters across `eoiagent-config`,
  `eoiagent-model` (LangChain4j chat/embeddings + an offline stub), `eoiagent-knowledge` (in-process
  ONNX embeddings, in-memory + pgvector stores, ingestor/retriever, advanced retrieval),
  `eoiagent-tool` (read-only + mutating Java-API tools, MCP adapter, RBAC-enforcing registry with
  approval routing), `eoiagent-runtime` (ReAct, supervisor/sub-agent, and a Phase-3 LangGraph4j
  investigation orchestrator + planner/task-manager), `eoiagent-memory` (window + summarizing memory;
  in-memory + Postgres stores), `eoiagent-scratchpad`, `eoiagent-safety` (input + output guardrails,
  approval gate, role-based policy), `eoiagent-observability` (file / Slf4j / JDBC audit sinks),
  `eoiagent-host` (the `AgentService` facade), and `eoiagent-eval` (golden-set harness).
- **Reuse layer** — `eoiagent-app-api` (the typed Application Pack SPI), `eoiagent-platform`
  (`PlatformBuilder` — validates a pack and assembles the engine), `eoiagent-app-reference` (the
  worked **Acme Lakehouse** pack), and `eoiagent-examples` (runnable demos).

> **Phase 2 (complete):** planning + task management, **gated mutating actions** (dry-run + human
> approval + RBAC), supervisor/sub-agent delegation, summarizing memory, advanced retrieval, MCP
> tools, and output guardrails. **Phase 3 (started):** a checkpointed, cyclical investigation
> orchestrator on LangGraph4j — durable checkpoint stores, breakpoints/HITL, and time-travel are next.

## Try it — runnable demos

No setup required — the demos run **fully offline** against a deterministic stub model, and use a
local **Ollama** automatically if one is reachable at `localhost:11434`.

```bash
# Build once, then run all demos (bootstrap, tools, navigation, policy, a Q&A session):
mvn -q -DskipTests install
mvn -q -pl eoiagent-examples exec:java

# Force offline even if Ollama is running:
mvn -q -pl eoiagent-examples exec:java -Deoiagent.demo.offline=true

# Run a single demo:
mvn -q -pl eoiagent-examples exec:java -Dexec.mainClass=com.eoiagent.examples.QaSessionDemo
```

| Demo | Shows |
|------|-------|
| `PlatformBootstrapDemo` | Assembling a usable `AgentService` from a pack in one call (Flow 0) |
| `RagAndToolsDemo` | The bundled knowledge corpus + invoking the read-only tools |
| `NavigationDemo` | The navigation catalog and a validated `NavigationIntent` |
| `PolicyAndProfilesDemo` | Host-role → `Role` mapping, capability grants, OFFLINE config |
| `QaSessionDemo` | An end-to-end Q&A session with the recorded audit trail |
| `MutatingApprovalDemo` | **Flow C** — dry-run → approve/deny a mutating action + RBAC enforcement |
| `SupervisorDemo` | **Flow D** — a supervisor delegating to an isolated sub-agent |
| `SummarizingMemoryDemo` | Condensing evicted turns into a running summary |
| `AdvancedRetrievalDemo` | Query rewrite + routing + re-rank vs naive vector search |
| `McpGatingDemo` | Gating MCP-backed tools on the `MCP_TOOLS` feature |
| `OutputGuardrailDemo` | Schema output validation with a bounded reprompt (PASS/RETRY/FAIL) |
| `Phase2EvalDemo` | Scoring an agent against a golden suite with the eval harness |

For the task-by-task guide (build, embed in a host app, write a pack, enable Phase-2 capabilities,
optional Postgres/Ollama/MCP), see [`HOWTO.md`](HOWTO.md).

## Tech at a glance

- **JDK 25**, **Maven**, plain library (no Spring/Quarkus).
- **LangChain4j 1.16.3** foundation (models, RAG, tools, memory, MCP), BOM-pinned.
- **Hybrid orchestration**: `langchain4j-agentic` for the MVP → **LangGraph4j 1.8.19** for
  stateful/checkpointed investigation workflows later — both behind one `Orchestrator` port.
- **Offline-first**: in-process ONNX embeddings + `InMemoryEmbeddingStore`; local LLM via an
  OpenAI-compatible endpoint (Ollama / llama.cpp / vLLM / LM Studio). pgvector for production.
- **Safety built into the runtime**: human-approval gate + dry-run for mutating actions, RBAC by
  user role, guardrails, and a persisted audit trail.
- **Reusable across products**: one CORE engine + a per-product **Application Pack** (typed SPI),
  assembled by `eoiagent-platform`; one pack per deployment.

See [`docs/adr/`](docs/adr/) for the reasoning behind each of these.

## Roadmap (one line)

Phase 0 Foundations ✓ → Phase 1 MVP (read-only RAG + tools + page help) ✓ → Phase 2 Planning +
mutating actions + delegation ✓ → Phase 3 Stateful investigation + durability **(in progress)** →
Phase 4 Hardening.
