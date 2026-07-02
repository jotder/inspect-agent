---
type: glossary
title: "Glossary"
description: "Shared vocabulary."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["glossary"]
---
# Glossary

> Shared vocabulary. Use these terms verbatim in code, comments, specs, and tickets. Domain
> **types** are defined in [`architecture/02-domain-model.md`](architecture/02-domain-model.md);
> this file explains the **concepts**.

## Platform concepts

| Term | Meaning |
|------|---------|
| **EOI Agent** | The Enterprise Operational Intelligence Agent Platform — this embeddable Java library. |
| **Agent OS** | Framing of the platform as an operating system for agents inside a host app (runtime + tools + memory + safety), not an LLM wrapper. |
| **Host application** | The product that embeds the library and exposes its Java API as tools and its pages as navigation targets. |
| **Port** | A stable Java interface (a capability contract) in `core`/`*-api`. |
| **Adapter** | An implementation of a port, the only place a third-party library is used. |
| **Deployment profile** | `OFFLINE` / `ON_PREM_HOSTED` / `CLOUD` — selects model placement and the capability matrix. |
| **Capability matrix** | Profile × `Feature` table deciding what is even reachable. |
| **Core** | The reusable, product-agnostic engine: all ports + domain types + generic adapters + the SPI module + bootstrap. Built once, reused by every product. |
| **Application Pack** | The project-specific module (one per product) implementing the `ApplicationPack` SPI — supplies models, domain knowledge, tools, navigation, prompts, roles, config. A new product = a new pack, never a fork of core. |
| **Application Pack SPI** | The typed contract (`com.eoiagent.app`, `eoiagent-app-api`) a product implements: `ApplicationPack` + eight providers (`ModelProfile`, `KnowledgeSource`, `ToolProvider`, `NavigationCatalog`, `PromptProfile`, `PolicyProfile`, `PackConfig`, `PackMetadata`). |
| **AgentPlatform / PlatformBuilder** | Core bootstrap (`com.eoiagent.platform`) that validates a pack, wires the core components from it, ingests the corpus, and returns a ready `AgentService`. One pack per deployment. |
| **Reference pack** | The bundled worked example pack (`eoiagent-app-reference`) that agents copy to start a new product's pack. |

## Runtime concepts

| Term | Meaning |
|------|---------|
| **Orchestrator** | Drives a run: plan → act → observe → reflect. MVP adapter on `langchain4j-agentic`; Phase 3 adapter on LangGraph4j. |
| **ReAct loop** | Reason+Act iterative loop: model emits thought + tool call, observes result, repeats. |
| **Planner** | Produces and revises a multistep `Plan`. |
| **TaskManager** | The `write_todos` equivalent: tracks `Task`/`TaskList` status, visible to the host. |
| **Sub-agent / worker** | An isolated nested orchestration (Analysis / SQL / Pipeline) under a supervisor. |
| **Supervisor** | An LLM-driven orchestrator that delegates sub-goals to workers and aggregates results. |
| **Scratchpad** | Virtual filesystem for context offloading — large intermediate results stored by handle so the context window doesn't blow up. |
| **Checkpoint** | A saved run state enabling resume-after-restart, breakpoints, and time-travel. |
| **Run** | One end-to-end execution of a goal (`RunId`); a session may contain many runs. |
| **Session** | A conversation context (`SessionId`) for one user, carrying memory + page context. |

## Knowledge / RAG concepts

| Term | Meaning |
|------|---------|
| **Corpus** | What the agent retrieves over: product docs, pipeline/job config files, schema/data-model configs. (Dynamic data — events/alerts/incidents — comes via **tools**, not RAG.) |
| **Ingestion** | load → split → embed → store pipeline (`DocumentIngestor`). |
| **In-process embeddings** | ONNX `all-MiniLM` running in-JVM with no network — the offline default. |
| **Retriever** | Returns top-k `RetrievedChunk`s with citations for a query. |
| **Advanced retrieval** | Query rewriting / routing / re-ranking (Phase 2). |
| **Citation** | Provenance for an answer (source id + locator). |

## Tooling concepts

| Term | Meaning |
|------|---------|
| **Tool** | A callable the agent can invoke; wraps a host Java-API method (`@Tool`) or an MCP tool. |
| **Read-only tool** | A tool that cannot change state; no approval needed. |
| **Mutating tool** | A tool that changes state (run pipeline, edit config, write data, trigger job); always gated. |
| **MCP** | Model Context Protocol — standard for calling external tools; we are an MCP *client*. |

## Safety / governance concepts

| Term | Meaning |
|------|---------|
| **Approval gate** | Human-in-the-loop checkpoint that blocks a mutating action until approved/denied. |
| **Dry-run** | Preview of a mutating action's effects without committing. |
| **Guardrail** | Input check (prompt-injection / PII) or output check (schema / validation). |
| **Policy / RBAC** | Role-based control (`ADMIN`/`SUPPORT`/`ANALYST`/`USER`) over which capabilities/tools are available. |
| **Audit trail** | Append-only record of every model call, tool call, decision, approval, and mutation. |

## Product-surface concepts

| Term | Meaning |
|------|---------|
| **Page context** | The current page id, entity ids, and filters passed in on every `ask`. |
| **Navigation intent** | The agent's primary output mode: route the user to a target KPI/report page with pre-filled parameters. |
| **Inline artifact** | A chart/table/data answer rendered on the current page. |
| **User tiers** | Product user levels mapped to `Role`: admin, support, analyst, normal user. |

## Substrate names

| Term | Meaning |
|------|---------|
| **LangChain4j** | Base AI library (models, RAG, tools, memory, MCP). Pinned via `langchain4j-bom:1.16.3`. |
| **langchain4j-agentic** | Experimental agent-workflow module; MVP orchestration adapter. |
| **langchain4j-guardrails** | Experimental guardrails module; guardrail adapters. |
| **LangGraph4j** | Graph orchestration library (`org.bsc.langgraph4j`, 1.8.19); Phase 3 stateful/checkpointed adapter. |
| **Ollama / llama.cpp / vLLM / LM Studio** | Local model servers reachable via an OpenAI-compatible `baseUrl`. |
| **pgvector** | PostgreSQL vector extension; production vector store. |
