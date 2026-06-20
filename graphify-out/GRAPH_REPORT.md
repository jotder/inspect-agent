# Graph Report — EOI Agent

Generated: 2026-06-20T05:11:32.061Z
Tool: local-graphify (docs equivalent) v1.0.0

## Summary

| Graph | Nodes | Edges | Issues |
|-------|------:|------:|--------|
| Docs link graph | 37 | 459 | 0 dangling, 0 orphan |
| Architecture graph | 19 | 20 | — |

## Health

- ✅ **No dangling `.md` links.**
- ✅ **No orphan docs** (every doc is reachable; README/AGENTS are entry points).

## Most-referenced docs (inbound links)

| Doc | Inbound |
|-----|--------:|
| `docs/architecture/04-sequence-flows.md` | 53 |
| `docs/architecture/01-component-model.md` | 45 |
| `docs/architecture/02-domain-model.md` | 40 |
| `docs/conventions.md` | 40 |
| `docs/architecture/03-deployment-profiles.md` | 25 |
| `docs/architecture/05-core-and-application-packs.md` | 24 |
| `docs/adr/0004-hexagonal-ports-and-adapters.md` | 18 |
| `docs/specs/application-pack.md` | 18 |

## Docs by group

### root (2)

- `AGENTS.md` — AGENTS.md — Guide for AI Coding Agents  _(in:2, out:20)_
- `README.md` — Enterprise Operational Intelligence Agent Platform (EOI Agent)  _(in:0, out:10)_

### architecture (6)

- `docs/architecture/00-overview.md` — 00 — Architecture Overview  _(in:5, out:21)_
- `docs/architecture/01-component-model.md` — 01 — Component Model (Ports & Adapters)  _(in:45, out:21)_
- `docs/architecture/02-domain-model.md` — 02 — Domain Model & Maven Coordinates  _(in:40, out:4)_
- `docs/architecture/03-deployment-profiles.md` — 03 — Deployment Profiles & Capability Matrix  _(in:25, out:5)_
- `docs/architecture/04-sequence-flows.md` — 04 — Sequence Flows  _(in:53, out:3)_
- `docs/architecture/05-core-and-application-packs.md` — 05 — Core & Application Packs (reuse across products)  _(in:24, out:12)_

### adr (11)

- `docs/adr/0001-embeddable-java-no-spring.md` — ADR-0001: Embeddable plain-Java library, no Spring/Quarkus  _(in:5, out:3)_
- `docs/adr/0002-jdk25-maven-httpclient.md` — ADR-0002: Target JDK 25 + Maven; standardize on the JDK HttpClient transport  _(in:11, out:5)_
- `docs/adr/0003-foundation-langchain4j-bom.md` — ADR-0003: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM  _(in:9, out:6)_
- `docs/adr/0004-hexagonal-ports-and-adapters.md` — ADR-0004: Organize the platform as Hexagonal Ports & Adapters  _(in:18, out:6)_
- `docs/adr/0005-orchestration-agentic-then-langgraph4j.md` — ADR-0005: Hybrid orchestration — langchain4j-agentic for MVP, LangGraph4j for stateful flows, behind one Orchestrator port  _(in:8, out:3)_
- `docs/adr/0006-local-llm-portability-openai-compatible.md` — ADR-0006: Standardize local/on-prem model access on the OpenAI-compatible baseUrl client  _(in:4, out:5)_
- `docs/adr/0007-vector-store-inmemory-then-pgvector.md` — ADR-0007: InMemoryEmbeddingStore for embedded/offline; pgvector for production  _(in:4, out:3)_
- `docs/adr/0008-mutating-actions-approval-gate-dryrun.md` — ADR-0008: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime  _(in:6, out:4)_
- `docs/adr/0009-audit-trail-and-observability.md` — ADR-0009: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing  _(in:5, out:6)_
- `docs/adr/0010-isolate-experimental-deps.md` — ADR-0010: Quarantine experimental/single-maintainer dependencies behind ports + feature flags  _(in:17, out:7)_
- `docs/adr/0011-core-and-application-pack-split.md` — ADR-0011: Split the platform into a reusable Core and a project-specific Application Pack  _(in:13, out:4)_

### spec (14)

- `docs/specs/application-pack.md` — Application Pack SPI & Platform Bootstrap — Spec  _(in:18, out:16)_
- `docs/specs/approval-governance.md` — Approval & Governance — Spec  _(in:5, out:12)_
- `docs/specs/audit-observability.md` — Audit & Observability — Spec  _(in:7, out:9)_
- `docs/specs/config-profiles.md` — Config / Deployment Profiles — Spec  _(in:5, out:19)_
- `docs/specs/eval-harness.md` — Eval Harness — Spec  _(in:17, out:12)_
- `docs/specs/guardrails.md` — Guardrails — Spec  _(in:7, out:10)_
- `docs/specs/host-integration.md` — Host Integration — Spec  _(in:5, out:19)_
- `docs/specs/memory.md` — Memory — Spec  _(in:6, out:15)_
- `docs/specs/model-gateway.md` — Model Gateway — Spec  _(in:6, out:19)_
- `docs/specs/orchestration-runtime.md` — Agent Runtime / Orchestration — Spec  _(in:11, out:22)_
- `docs/specs/rag-knowledge.md` — RAG / Knowledge — Spec  _(in:9, out:17)_
- `docs/specs/reference-app-pack.md` — Reference Application Pack — Spec  _(in:6, out:27)_
- `docs/specs/scratchpad.md` — Scratchpad / Virtual FS — Spec  _(in:3, out:12)_
- `docs/specs/tool-registry.md` — Tool Registry — Spec  _(in:9, out:18)_

### roadmap (2)

- `docs/roadmap/backlog.md` — Backlog — Agent-Sized Tickets  _(in:5, out:69)_
- `docs/roadmap/roadmap.md` — Roadmap  _(in:3, out:5)_

### docs (2)

- `docs/conventions.md` — Conventions  _(in:40, out:9)_
- `docs/glossary.md` — Glossary  _(in:3, out:1)_

## Architecture graph

### Components (CORE)

| ID | Component | Ports | Adapters | Spec |
|----|-----------|-------|---------:|------|
| C1 | Model Access | LlmGateway | 6 | `docs/specs/model-gateway.md` |
| C2 | Knowledge / RAG | EmbeddingModel, VectorStore, Retriever, DocumentIngestor | 7 | `docs/specs/rag-knowledge.md` |
| C3 | Tools | Tool, ToolRegistry | 2 | `docs/specs/tool-registry.md` |
| C4 | Agent Runtime / Orchestration | Planner, Orchestrator, TaskManager | 3 | `docs/specs/orchestration-runtime.md` |
| C5 | Memory | ChatMemory, MemoryStore, LongTermMemory | 7 | `docs/specs/memory.md` |
| C6 | Scratchpad / Virtual FS | Scratchpad | 2 | `docs/specs/scratchpad.md` |
| C7 | Safety / Governance | ApprovalGate, PolicyEngine, Guardrail | 4 | `docs/specs/approval-governance.md` |
| C8 | Persistence / Checkpointing | CheckpointStore | 2 | `docs/specs/orchestration-runtime.md` |
| C9 | Observability / Audit | AuditSink, TraceCollector | 5 | `docs/specs/audit-observability.md` |
| C10 | Host Integration | AgentService, AgentSession | 0 | `docs/specs/host-integration.md` |
| C11 | Configuration / Deployment Profiles | ConfigProvider | 3 | `docs/specs/config-profiles.md` |

### Reuse layer

- **ApplicationPack SPI** (`eoiagent-app-api`, layer: app-api) — providers: PackMetadata, ModelProfile, KnowledgeSource[], ToolProvider, NavigationCatalog, PromptProfile, PolicyProfile, PackConfig
- **AgentPlatform / PlatformBuilder** (`eoiagent-platform`, layer: platform)
- **Reference Application Pack** (`eoiagent-app-reference`, layer: app-pack)

### Module dependency direction

```
host app (consumer)  ──depends-on──►  eoiagent-platform
eoiagent-platform  ──depends-on──►  core
eoiagent-platform  ──consumes──►  eoiagent-app-api
eoiagent-app-reference  ──implements──►  eoiagent-app-api
eoiagent-app-api  ──depends-on (domain types only)──►  core
```

_Invariant: core imports no adapter and no pack; the pack depends on `eoiagent-app-api` + BOM only._
