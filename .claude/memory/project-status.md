---
name: project-status
description: Actual build progress (from git log) — further along than ONBOARDING.md claims
metadata:
  type: project
---

# Project status (as of 2026-07-03)

**Done (committed, tagged `T-NNN:` in git log):** Phase 0 (T-001…T-010), Phase 1 (T-101…T-116),
**all of Phase 2** (T-201…T-211), and Phase 3 through **T-304** (LangGraphOrchestrator T-301,
CheckpointStore InMemory+Postgres T-302, breakpoints/HITL/time-travel T-303, investigation
tools + playbooks T-304).

**Remaining:**
- Phase 3: T-305 (VectorLongTermMemory), T-306 (investigation + resume-after-restart eval)
- Phase 4: T-401…T-405 (OTel tracing, performance pass, security review + offline network-deny, packaging, eval/CI gates)

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

Repo: https://github.com/jotder/inspect-agent (personal `jotder`; commits authored
rahul@gammanalytics.com — org transfer still an open item).
