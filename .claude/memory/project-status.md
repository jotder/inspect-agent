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

**Remaining:**
- Phase 4: T-402…T-405 (performance pass, security review + offline
  network-deny, packaging, eval/CI gates) — plus the [[platform-wiring-gotcha]] design item
  (wire retriever/NavigationCatalog/gate/supervisor/LangGraph into PlatformBuilder so the
  signature demo runs through `platform.agentService()`).

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
