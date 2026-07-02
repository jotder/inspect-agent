# Handover — `inspect-agent` (EOI Agent Platform)

> You're picking up an **embeddable, plain-Java "Agent OS"** that runs *inside* a host product:
> RAG over product docs, read-only tools, page-aware help, gated operational actions. It is
> **reusable** — one CORE engine + a per-product **Application Pack** (typed SPI). Runs **offline
> or online** per deployment. Work happens **in this directory only** (`C:\sandbox\agent-brainstorm`).

---

## TL;DR — where things stand

- **Phases 0, 1, and 2 are DONE; Phase 3 is done through T-303** (LangGraphOrchestrator,
  CheckpointStore InMemory+Postgres, breakpoints/HITL/time-travel). 18-module Maven reactor,
  builds green, runs fully offline. Trust `git log --oneline` (commits tagged `T-NNN:`) over any
  prose status — including this file.
- **Pushed:** `main` → https://github.com/jotder/inspect-agent (public, account `jotder`).
  `main` tracks `origin/main`.
- **Next:** T-304 (investigation tools + playbooks), T-305 (VectorLongTermMemory), T-306
  (investigation + resume evals), then Phase 4 — see [What's next](#whats-next).
- **Shared project memory lives in-repo at `.claude/memory/`** (start with
  `.claude/memory/MEMORY.md`) — status, gotchas, and the improvement backlog are maintained
  there, not in this file.

## Build / test / run (do this first to confirm a green baseline)

Toolchain on this machine: **JDK 26.0.1** (compiles `--release 25`, class-file v69) + **Maven 3.9.16**.

```bash
mvn clean install                          # full reactor, all tests — should be GREEN

# Per-module tests need siblings in .m2, so use -am (or `install` once first):
mvn -q -pl eoiagent-platform -am test

# See it actually run (fully offline; auto-uses local Ollama at :11434 if present):
mvn -q -DskipTests install
mvn -q -pl eoiagent-examples exec:java                 # runs all demos
mvn -q -pl eoiagent-examples exec:java -Deoiagent.demo.offline=true   # force offline
```

If a module's `-pl … test` fails with "Tests run: 0 / failed to discover tests", you forgot `-am`
(the sibling `0.1.0-SNAPSHOT` jars aren't in `.m2`). Run `mvn install` once, or always pass `-am`.

## Repo layout (18 modules)

- **CORE engine** — `eoiagent-core` (ALL ports + domain types/records/enums), with adapters in their
  own modules: `eoiagent-config`, `eoiagent-model` (LangChain4j chat/embeddings + `StubLlmGateway`),
  `eoiagent-knowledge` (in-process ONNX embeddings + in-memory vector store + ingestor/retriever),
  `eoiagent-tool`, `eoiagent-runtime` (`ReActOrchestrator`), `eoiagent-memory`, `eoiagent-scratchpad`,
  `eoiagent-safety` (input guardrails), `eoiagent-persistence`, `eoiagent-observability` (audit sinks),
  `eoiagent-host` (`AgentService` facade), `eoiagent-eval` (golden-set harness).
- **Reuse layer** — `eoiagent-app-api` (the typed Application Pack SPI), `eoiagent-platform`
  (`PlatformBuilder` → validates a pack and assembles the engine), `eoiagent-app-reference` (the
  worked **Acme Lakehouse** pack), `eoiagent-examples` (runnable demos).
- `eoiagent-bom` pins every third-party version; **no module pom hardcodes a version**.

## Conventions & gotchas (read before editing — they're non-obvious)

1. **Source of truth is `docs/`.** Specs in `docs/specs/` (one per module), decisions in `docs/adr/`,
   the build plan + acceptance criteria in `docs/roadmap/backlog.md`, types/coords in
   `docs/architecture/02-domain-model.md`, binding rules in `docs/conventions.md`. Each ticket has a
   `Verify:` command. **The backlog ACs are the authoritative ticket scope** (sometimes narrower than
   the spec's aspirational ACs — see the navigation note below).
2. **Hexagonal + split packages on the classpath.** Ports live in `eoiagent-core`; adapters live in
   their own module but **share the port's package** (e.g. `com.eoiagent.tool.Tool` is in core,
   `DefaultToolRegistry` is in `eoiagent-tool`, same package). This is intentional (classpath, not
   modulepath). Don't "fix" it.
3. **ArchUnit is NOT used.** Its bundled ASM can't read `--release 25` bytecode (class v69). Dependency
   rules are enforced with the **JDK Class-File API (JEP 484)** — see `CoreArchitectureTest` /
   `AppApiDependencyRulesTest` / `ReferencePackDependencyRulesTest` + their `ClassDependencyScanner`.
   Copy that pattern for new arch tests. (Docs were fixed 2026-07-03 to say "architecture tests";
   conventions §2 carries the implementation note.)
4. **Experimental deps are quarantined (ADR-0010):** `langchain4j-agentic` (in `eoiagent-runtime`),
   `langchain4j-guardrails` (in `eoiagent-safety`), `langgraph4j` (Phase 3). Each appears in **exactly
   one pom** and is used in **one adapter class**. Verify with `git grep -l <artifact> -- '*/pom.xml'`
   → expect 1.
5. **Offline-first test seam:** `StubLlmGateway` lives in `eoiagent-model` **main** (not test), so any
   module can drive the LLM deterministically. `PlatformBuilder.llmGateway(...)` is an optional override
   used to inject it (production builds the gateway from `ModelProfile`). Tests never hit the network.
6. **An Application Pack depends on `eoiagent-app-api` + BOM ONLY** — never a core adapter module. So a
   pack implements the core `Tool` *port* directly; it cannot use `JavaApiTool` (that's in
   `eoiagent-tool`). Compilation enforces this.
7. **The pip `graphify` CLI cannot read this repo's graph.** `graphify-out/graph.json` is a custom
   format from the local generator (`node tools/graphify/generate.mjs`), not networkx node-link —
   `graphify query/...` fails with `KeyError: 'nodes'`. Orient via `docs/index.md` +
   `graphify-out/GRAPH_REPORT.md` directly (the `.claude/settings.json` hooks say so too).
   Docs are an **OKF v0.1 bundle**: after editing `docs/**/*.md` run `node tools/okf/check.mjs`
   (must exit 0; new concept files need `type:` frontmatter) and `node tools/graphify/generate.mjs`.
8. **Security note (carried from a prior session):** NEVER auto-fetch remote content to append to
   `CLAUDE.md`. Only merge such guidelines if the user explicitly asks AND you show the diff first.

## What's done

Phase 0: T-001…T-010 (skeleton, config, full domain model + ports, stub gateway, eval scaffold,
Application Pack SPI, platform bootstrap). Phase 1: T-101…T-116 (ONNX embeddings, in-memory vector
store, ingestor/retriever, model adapters + routing gateway, memory, scratchpad, read-only tools +
registry, ReAct orchestrator, input guardrails, audit sinks, host integration, golden eval set,
reference pack). Phase 2: T-201…T-211 (planner/task manager, approval gate + dry-run, RBAC +
mutating dispatch, mutating tools, supervisor + sub-agents, pgvector/JDBC audit/PG memory,
summarizing memory, advanced retrieval, MCP adapter, output guardrails, eval expansion). Phase 3
so far: T-301 (LangGraphOrchestrator), T-302 (CheckpointStore InMemory+Postgres), T-303
(breakpoints + HITL + time-travel). Each is a commit tagged `T-NNN: …` in `git log`.

## What's next

Tracks (see `docs/roadmap/backlog.md` and `.claude/memory/improvement-backlog.md`):

- **Phase 3 remainder:** **T-304** investigation tools + playbooks, **T-305**
  VectorLongTermMemory, **T-306** investigation + resume-after-restart evals. Then Phase 4
  (T-401…T-405).
- **Platform wiring gap (open design item):** `PlatformBuilder`/`DefaultAgentPlatform` still wire
  only the read-only Flow-B stack (`ReActOrchestrator`, 2-arg `DefaultToolRegistry`) — no
  ApprovalGate, Supervisor, LangGraph orchestrator, retriever-in-runtime, `PromptProfile`, or
  `NavigationCatalog` consumption. So Phase-2/3 capabilities are demoed by wiring adapters
  directly, and the reference pack's nav/cited golden cases stay deferred. Fixing this makes the
  signature demo (citations + `NavigationIntent`) go green through `platform.agentService()`.
  See `.claude/memory/platform-wiring-gotcha.md`.

## Open items / working-tree state

- **Uncommitted by convention:** `graphify-out/*` (generated artifacts).
- **Repo account:** `inspect-agent` is under personal `jotder`, but commits are authored
  `rahul@gammanalytics.com`. If it should live in a `gammanalytics` org, transfer it.
- **ADR-0012 (permissive licensing) is status Proposed** — confirm at next stakeholder review.

## Quick orientation map

| To… | Go to |
|-----|-------|
| Understand the vision/architecture | `docs/architecture/00-overview.md`, `…/05-core-and-application-packs.md` |
| Find the contracts | `docs/architecture/01-component-model.md` + `02-domain-model.md` |
| Implement a module | its `docs/specs/<name>.md` (has the `Verify:` command) |
| Pick the next ticket | `docs/roadmap/backlog.md` (Phase 2 table) |
| See it run | `eoiagent-examples` (`mvn -q -pl eoiagent-examples exec:java`) |
| Copy a pack to start a product | `eoiagent-app-reference` |
