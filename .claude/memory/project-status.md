---
name: project-status
description: Actual build progress (from git log) — further along than ONBOARDING.md claims
metadata:
  type: project
---

# Project status (as of 2026-07-03, evening)

**Done (committed, tagged `T-NNN:` in git log):** Phase 0 (T-001…T-010), Phase 1 (T-101…T-116),
**all of Phase 2** (T-201…T-211), and **ALL of Phase 3** (T-301…T-306: LangGraphOrchestrator,
CheckpointStore InMemory+Postgres, breakpoints/HITL/time-travel, investigation tools + playbooks,
VectorLongTermMemory, investigation eval + restart→resume asserted). Full reactor green.

**Phase 4 started: T-401 done (OTel tracing).** `NoopTraceCollector` + quarantined
`OpenTelemetryTraceCollector` (optional `opentelemetry-api` 1.44.1 via BOM) in
eoiagent-observability; spans (model_call/tool_call) wired into `ReActOrchestrator` via a new
6-arg ctor (5-arg ctor unchanged, defaults to a private no-op — runtime depends only on the
TraceCollector PORT in core, never the observability adapter); `PlatformBuilder.traceCollector(...)`
override, Noop default. OTel tests run against `OpenTelemetry.noop()` — offline, no SDK.

**HANDOVER AUDIT (2026-07-03, new sole owner):** phases 0–3 are component-complete but the LIVE
path was never closed. Verified gaps: **F1** `Lc4jChatGateway.toolCalls()` always empty — no real
model can drive a tool (whole agentic stack only ever ran on StubLlmGateway); **F2** sessions
stateless (no ChatMemory consumed in host/platform); **F3** no Retriever in the loop (no live
RETRIEVAL/citations); **F4** NavigationIntent never emitted by any orchestrator; **F5** Phase 4
as written collided with reality (JPMS vs split packages; SecurityManager removed in JDK 25; no
CI exists). Owner directives: model choice must be pluggable as models progress (→ ADR-0013);
otherwise architect's discretion. New ADRs: **0013** (pluggable models, config-first + eval
certification), **0014** (classpath jars + Automatic-Module-Name, NO JPMS).

**T-350 DONE (2026-07-03):** tool calls now map across the LC4j seam both ways. `ToolCall` gained
a nullable `callId` as FIRST component (back-compat 3-arg ctor delegates); `ToolCallMeta` (core,
pure-JDK encoder) carries assistant-tool-call turns + paired TOOL results in ChatMessageRecord.meta;
`ToolMapping`/`MessageMapping` (model) decode via LC4j internal Json + `JsonSchemaElementJsonUtils
.fromMap` (exact inverse of JavaApiTool's toMap); ReAct loop now replays the assistant tool-call
turn and preserves callId when scoping. Malformed model-emitted arg JSON degrades to
`{"_raw": <string>}` (never crashes; dispatch validation reports). KEY: OpenAI-protocol servers
REQUIRE the tool result to echo the request's call id — legacy meta-less TOOL records still map to
UserMessage (fallback).

**T-351 DONE (2026-07-03):** session memory in the live loop. `ReActOrchestrator` 7-arg ctor takes
nullable `MemoryStore` (port); seeds history from stored transcript (window = new
`eoiagent.runtime.memory.maxMessages` key, default 20; store keeps everything), persists USER+final
ASSISTANT turn on success (also on max-steps CLARIFICATION), nothing on ERROR; tool turns NEVER
persisted. `PlatformBuilder.memoryStore(...)` override, `InMemoryMemoryStore` default (platform pom
gained eoiagent-memory dep). Demo `MultiTurnMemoryDemo` (prints the exact context the model sees —
anti-misconception teaching per [[delivery-style]]); HOWTO "Phase-3.5 integration" section; memory
spec §1b records as-built vs aspirational §1.

**Remaining (restructured backlog):**
- **Phase 3.5 Integration (T-350 ✓, T-351 ✓):**
  T-352 RAG-in-loop, T-353 NavigationIntent emission, T-354 platform wiring v2 + config-first
  models (subsumes [[platform-wiring-gotcha]]), T-355 real streaming, T-356 live-model E2E +
  model certification (candidates: qwen2.5, Ornith 1.0 9B — agentic-coding model, GGUF/Ollama).
- Phase 4 (after 3.5): T-402…T-405 as re-scoped in backlog.md.

**Fixed 2026-07-03** (all committed): ONBOARDING.md refreshed to real status; all ArchUnit doc
references corrected to JDK Class-File API arch tests (conventions §2 has the note); ADR-0012
(permissive licensing, Proposed) recorded + roadmap open questions closed; docs graph excludes
target/**; CLAUDE.md graphify rules retargeted to GRAPH_REPORT.md/docs/index.md.
Still open: `.claude/settings.json` hooks still mandate the broken pip graphify CLI — editing
hooks needs the user (permission classifier blocks agent self-modification); see
[[graphify-tooling-broken]].

**How to apply:** pick the lowest-numbered remaining ticket whose Depends-on are done
(docs/roadmap/backlog.md). Verify baseline with `mvn clean install` (JDK 25+, Maven 3.9+); use
`-am` for per-module test runs. T-304's canned corpus (orders_daily failure: E-1..E-3, A-101/
A-102, INC-2001, C-501, playbook `pipeline-failure`) is what the T-306 investigation eval should
assert against. See [[improvement-backlog]] and [[platform-wiring-gotcha]].

Repo: https://github.com/jotder/inspect-agent (personal `jotder`; commits authored
rahul@gammanalytics.com — org transfer still an open item).
