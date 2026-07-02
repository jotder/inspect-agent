---
okf_version: "0.1"
---
# EOI Agent — Knowledge Bundle

Curated context for AI agents, in [Open Knowledge Format](https://github.com/GoogleCloudPlatform/knowledge-catalog) v0.1. Each linked file is one concept with YAML frontmatter; links are relative to this bundle root (`docs/`).

## Architecture

* [00 — Architecture Overview](architecture/00-overview.md) - Entry-point architecture overview of the EOI Agent platform; read this first, before the component and domain models.
* [01 — Component Model (Ports & Adapters)](architecture/01-component-model.md) - Canonical list of the 11 components, their port interfaces, and the adapters that implement them.
* [02 — Domain Model & Maven Coordinates](architecture/02-domain-model.md) - The canonical vocabulary.
* [03 — Deployment Profiles & Capability Matrix](architecture/03-deployment-profiles.md) - The platform ships as one library that behaves differently per `DeploymentProfile`.
* [04 — Sequence Flows](architecture/04-sequence-flows.md) - The runtime behaviors as step-by-step flows.
* [05 — Core & Application Packs (reuse across products)](architecture/05-core-and-application-packs.md) - How the platform is split into a reusable CORE and a project-specific Application Pack, so one engine serves many products.

## Architecture Decision Records

* [ADR-0001: Embeddable plain-Java library, no Spring/Quarkus](adr/0001-embeddable-java-no-spring.md) - Architecture decision: Embeddable plain-Java library, no Spring/Quarkus.
* [ADR-0002: Target JDK 25 + Maven; standardize on the JDK HttpClient transport](adr/0002-jdk25-maven-httpclient.md) - Architecture decision: Target JDK 25 + Maven; standardize on the JDK HttpClient transport.
* [ADR-0003: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM](adr/0003-foundation-langchain4j-bom.md) - Architecture decision: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM.
* [ADR-0004: Organize the platform as Hexagonal Ports & Adapters](adr/0004-hexagonal-ports-and-adapters.md) - Architecture decision: Organize the platform as Hexagonal Ports & Adapters.
* [ADR-0005: Hybrid orchestration — langchain4j-agentic for MVP, LangGraph4j for stateful flows, behind one Orchestrator port](adr/0005-orchestration-agentic-then-langgraph4j.md) - Architecture decision: Hybrid orchestration — langchain4j-agentic for MVP, LangGraph4j for stateful flows, behind one Orchestrator port.
* [ADR-0006: Standardize local/on-prem model access on the OpenAI-compatible baseUrl client](adr/0006-local-llm-portability-openai-compatible.md) - Architecture decision: Standardize local/on-prem model access on the OpenAI-compatible baseUrl client.
* [ADR-0007: InMemoryEmbeddingStore for embedded/offline; pgvector for production](adr/0007-vector-store-inmemory-then-pgvector.md) - Architecture decision: InMemoryEmbeddingStore for embedded/offline; pgvector for production.
* [ADR-0008: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime](adr/0008-mutating-actions-approval-gate-dryrun.md) - Architecture decision: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime.
* [ADR-0009: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing](adr/0009-audit-trail-and-observability.md) - Architecture decision: Persisted, append-only audit trail of every agent decision/tool-call/action; pluggable tracing.
* [ADR-0010: Quarantine experimental/single-maintainer dependencies behind ports + feature flags](adr/0010-isolate-experimental-deps.md) - Architecture decision: Quarantine experimental/single-maintainer dependencies behind ports + feature flags.
* [ADR-0011: Split the platform into a reusable Core and a project-specific Application Pack](adr/0011-core-and-application-pack-split.md) - Architecture decision: Split the platform into a reusable Core and a project-specific Application Pack.

## Component & Capability Specs

* [Application Pack SPI & Platform Bootstrap — Spec](specs/application-pack.md) - The project-specific contract (`eoiagent-app-api`, `com.eoiagent.app`) a product implements to instantiate the agent for its domain, plus the core assembly module (`eoiagent-platform`).
* [Approval & Governance — Spec](specs/approval-governance.md) - Human-in-the-loop gating + RBAC for the agent.
* [Audit & Observability — Spec](specs/audit-observability.md) - Append-only audit trail of every consequential agent action, plus optional tracing.
* [Config / Deployment Profiles — Spec](specs/config-profiles.md) - Resolve typed configuration and enforce the per-profile capability matrix so a disabled feature is unreachable, not merely hidden.
* [Eval Harness — Spec](specs/eval-harness.md) - The "definition of done" measurement engine: golden cases, assertions, and a regression suite that runs offline and online in CI.
* [Guardrails — Spec](specs/guardrails.md) - Input and output safety checks wrapping every model interaction.
* [Host Integration — Spec](specs/host-integration.md) - The "embedded under every page" product surface: the host opens a session, asks page-aware questions, and gets back a typed `AgentAnswer` (often a `NavigationIntent`).
* [Memory — Spec](specs/memory.md) - Short-term windowed/summarized conversation memory + persistence + (Phase 3) long-term cross-session memory.
* [Model Gateway — Spec](specs/model-gateway.md) - Unified chat + embedding model access with local/hosted routing and per-profile fallback.
* [Agent Runtime / Orchestration — Spec](specs/orchestration-runtime.md) - Drives a run (plan → act → observe → reflect) for the EOI Agent.
* [RAG / Knowledge — Spec](specs/rag-knowledge.md) - Ingest and retrieve over the static corpus (product docs, pipeline/job config files, schema/data-model configs) with offline in-process embeddings.
* [Reference Application Pack — Spec](specs/reference-app-pack.md) - A worked, copy-to-start `ApplicationPack` for a fictional "Acme Lakehouse Suite", runnable fully offline.
* [Scratchpad / Virtual FS — Spec](specs/scratchpad.md) - Context offloading so the LLM context window doesn't blow up: large intermediate results are stored by handle and re-read on demand.
* [Tool Registry — Spec](specs/tool-registry.md) - Expose the host's Java API as agent tools and call external tools via MCP, with role/profile visibility filtering and approval+audit-enforced dispatch.

## Roadmap

* [Backlog — Agent-Sized Tickets](roadmap/backlog.md) - Each ticket is sized for one agent session: a single module/adapter + its tests.
* [Roadmap](roadmap/roadmap.md) - Phased build plan.

## Conventions & Glossary

* [Conventions](conventions.md) - Binding rules for everyone writing code in this repo — especially AI coding agents.
* [Glossary](glossary.md) - Shared vocabulary.
