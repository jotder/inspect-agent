# AGENTS.md — Guide for AI Coding Agents

> **Read this first, every time.** This repo is built primarily by AI coding agents. It is
> structured so you can implement **one module at a time** against **fixed contracts**. Follow
> this guide and the docs it points to; do not improvise architecture.

## What this project is

The **Enterprise Operational Intelligence Agent Platform (EOI Agent)** — an embeddable,
plain-Java "Agent Operating System" that runs inside a host application. It analyzes
schemas/metadata, authors pipelines, generates SQL, investigates incidents, executes (gated)
operational actions, and provides page-aware in-product help. It must run **offline or online**.
Full overview: [`docs/architecture/00-overview.md`](docs/architecture/00-overview.md).

## The 5 documents you must respect (precedence order)

1. [`docs/conventions.md`](docs/conventions.md) — binding rules (wins over any spec).
2. [`docs/architecture/02-domain-model.md`](docs/architecture/02-domain-model.md) — canonical
   type names, package names, config keys, **Maven coordinates** (use these verbatim).
3. [`docs/architecture/01-component-model.md`](docs/architecture/01-component-model.md) — the 11
   ports and their adapters.
4. [`docs/architecture/04-sequence-flows.md`](docs/architecture/04-sequence-flows.md) — required
   runtime behavior + invariants to assert in tests.
5. The relevant [`docs/specs/<module>.md`](docs/specs/) — what you implement, with acceptance
   criteria and a test plan.

**Know which half you are building.** The platform is **CORE** (reusable engine) + a
per-product **Application Pack** (project-specific). Before coding, read
[`docs/architecture/05-core-and-application-packs.md`](docs/architecture/05-core-and-application-packs.md).
- Building **core**? → the relevant `docs/specs/<module>.md` (the 11 components) + the contracts above.
- Building/onboarding an **Application Pack**? → [`docs/specs/application-pack.md`](docs/specs/application-pack.md)
  (the SPI + bootstrap) and copy [`docs/specs/reference-app-pack.md`](docs/specs/reference-app-pack.md).
  Put **all** product specifics (models, knowledge, tools, navigation, prompts, roles, config) in
  the pack — **never** add product content to core.

Decisions and their rationale are in [`docs/adr/`](docs/adr/). The build order and
agent-sized tickets are in [`docs/roadmap/backlog.md`](docs/roadmap/backlog.md).

## Non-negotiable rules (the ones that break the build if ignored)

1. **Ports & Adapters.** Implement against the port interface in
   [`01-component-model.md`](docs/architecture/01-component-model.md). Do not change a port
   signature without first writing/updating an ADR.
2. **Dependency direction.** Adapters depend on ports; **core/ports never import any agent
   framework** (no LangChain4j, LangGraph4j, MCP in `eoiagent-core`). Experimental deps
   (`langchain4j-agentic`, `langchain4j-guardrails`, `org.bsc.langgraph4j:*`) live **only** in
   adapter modules. ([ADR-0010](docs/adr/0010-isolate-experimental-deps.md))
   **CORE never imports an Application Pack and contains no product-specific content;** a pack
   depends on `eoiagent-app-api` + `eoiagent-bom` only. ([ADR-0011](docs/adr/0011-core-and-application-pack-split.md))
3. **Use exact names.** Type names, config keys (`eoiagent.*`), and Maven coordinates come from
   [`02-domain-model.md`](docs/architecture/02-domain-model.md). No synonyms.
4. **Versions via BOM.** All LangChain4j artifacts inherit from `langchain4j-bom:1.16.3`.
   LangGraph4j pinned at `1.8.19`. Never hardcode a third-party version in a module pom.
5. **JDK 25 + JDK HttpClient.** Target `maven.compiler.release=25`; use the JDK `HttpClient`
   transport, not Netty. ([ADR-0002](docs/adr/0002-jdk25-maven-httpclient.md))
6. **Safety is code, not prompt.** Mutating tools route through `ApprovalGate` + dry-run; every
   model/tool/mutation/approval emits an `AuditEvent`; offline fails closed (never silently
   reaches the network). Assert the invariants in
   [`04-sequence-flows.md`](docs/architecture/04-sequence-flows.md).
7. **Tests gate done.** Every acceptance criterion maps to a passing test. See the Definition of
   Done in [`conventions.md` §9](docs/conventions.md).

## How to pick up work

1. Open [`docs/roadmap/backlog.md`](docs/roadmap/backlog.md). Pick the lowest-numbered ticket in
   the current phase whose **Depends-on** items are all done.
2. Open the ticket's linked **spec** in [`docs/specs/`](docs/specs/). Read Purpose → Port →
   Adapters → Acceptance criteria → Test plan.
3. Check the port signature in
   [`01-component-model.md`](docs/architecture/01-component-model.md) and the types in
   [`02-domain-model.md`](docs/architecture/02-domain-model.md).
4. Implement the adapter + tests in the module the spec names. Add config keys to
   `ConfigProvider` defaults.
5. Run the verification commands in the spec/ticket. All acceptance tests must pass.
6. If you discover the contract is wrong: **stop, write/Update an ADR**, adjust the spec, then
   code. Do not silently diverge.

## Build & test (once the Maven skeleton exists — Phase 0)

> The code skeleton does not exist yet; these are the intended commands.
```bash
mvn -q verify                      # compile + unit + contract + architecture tests
mvn -q -pl eoiagent-model test     # one module
mvn -q -pl eoiagent-eval test      # eval/golden-set suite
```
Default tests must pass **without network and without a live LLM** (use the stub `LlmGateway`).
Profile-tagged integration tests (local Ollama) are opt-in.

## What to build first

Phase 0 (foundations) then Phase 1 (MVP: read-only RAG + tools + page-context help). Do **not**
build mutating actions, sub-agents, LangGraph4j, or pgvector before their phase — they are
sequenced deliberately in [`docs/roadmap/roadmap.md`](docs/roadmap/roadmap.md).

## When in doubt

- Prefer the simplest adapter that satisfies the port + acceptance criteria.
- Prefer an existing LangChain4j building block over hand-rolling (models, splitters, memory,
  embedding store) — but keep it behind the port.
- Quarantine anything experimental behind a port and a feature flag.
- If a decision isn't in the docs and matters, write an ADR proposing it rather than guessing in
  code.
