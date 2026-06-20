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

> **Status:** architecture & build documentation. No code yet — this repository currently holds
> the agent-friendly specs that AI coding agents will implement.

## Start here

| If you are… | Read |
|-------------|------|
| **An AI coding agent** | [`AGENTS.md`](AGENTS.md) — the build guide. |
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

Phase 0 Foundations → Phase 1 MVP (read-only RAG + tools + page help) → Phase 2 Planning +
mutating actions + delegation → Phase 3 Stateful investigation + durability → Phase 4 Hardening.
