---
type: adr
title: "ADR-0003: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM"
description: "Architecture decision: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["foundation-langchain4j-bom"]
---
# ADR-0003: Adopt LangChain4j 1.16.3 as the base AI library, pinned via BOM

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

We need a base AI library that gives us, in plain Java, the full deep-agent surface:
model access (local + hosted), RAG (embeddings, vector stores, loaders, retrieval),
tool calling, chat memory, and MCP — without pulling in a DI framework (constraint **C1**,
[ADR-0001](0001-embeddable-java-no-spring.md)) and runnable on JDK 25 over the JDK
`HttpClient` transport (constraint **C7**, [ADR-0002](0002-jdk25-maven-httpclient.md)).

The platform is an **offline-first internal tool** (constraints **C2/C3**): breadth of
capability and a stable, unified API matter more than having the newest provider feature
the day it ships.

A multi-module Maven build (one module per component) must not let LangChain4j artifact
versions drift apart; mixing versions is a known source of subtle breakage.

## Decision

Adopt **LangChain4j as the base AI library**, pinned via its BOM:

- Import **`dev.langchain4j:langchain4j-bom:1.16.3`** (scope `import`) into the
  **`com.eoiagent:eoiagent-bom`**, which every module inherits. **No module pom hardcodes a
  LangChain4j version** ([`../conventions.md`](../conventions.md) §1).
- All LangChain4j artifacts align to the BOM: `langchain4j` (core — AI Services, tools,
  RAG, memory, in-memory store), `langchain4j-ollama`, `langchain4j-open-ai`,
  `langchain4j-anthropic`, `langchain4j-google-ai-gemini`,
  `langchain4j-embeddings-all-minilm-l6-v2`, `langchain4j-pgvector`, `langchain4j-mcp`.
- The LangChain4j **core** we depend on (models / AI Services / tools / RAG / memory / MCP)
  is **GA**.
- We require **≥ 1.16.3 specifically because it fixes a pgvector SQL-injection CVE**; the
  `langchain4j-pgvector` adapter is consequently pinned `≥ 1.16.3`
  ([ADR-0007](0007-vector-store-inmemory-then-pgvector.md),
  [`../architecture/02-domain-model.md`](../architecture/02-domain-model.md) §Maven coordinates).

The two experimental modules (`langchain4j-agentic`, `langchain4j-guardrails`) ship
`-betaNN` suffixes per release; we resolve the suffix at build time and pin it in
`eoiagent-bom` — see [ADR-0010](0010-isolate-experimental-deps.md).

## Consequences

**Positive**
- One library covers the full agent surface in plain Java, BOM-aligned, JDK-`HttpClient`
  friendly — directly satisfying C1/C2/C3/C7.
- Single version knob (`1.16.3`) across the whole reactor; no LC4j version skew.
- The pgvector CVE is closed by the version floor we adopt.

**Negative / follow-ups**
- **Breadth over bleeding-edge:** LangChain4j's unified API typically lags brand-new
  provider features by weeks. **Acceptable** for an offline-first internal tool — when a
  feature is truly needed early, it goes behind a port adapter, not into core.
- We track LC4j releases for the next CVE/feature floor and bump the BOM deliberately
  (Definition of Done forbids out-of-BOM versions).

## Alternatives considered

- **Google ADK-Java** — GA, but **Gemini-first**, with a **heavier transitive footprint**,
  and it **routes local models through LangChain4j anyway**. Rejected as the *primary* base;
  **noted as a fallback** should LangChain4j prove insufficient.
- **Hand-rolled provider clients** — maximum control, but we would reinvent RAG, tools,
  memory, MCP, and every provider integration. Rejected (reinvention, maintenance burden).
