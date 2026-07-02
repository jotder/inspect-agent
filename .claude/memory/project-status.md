---
name: project-status
description: Actual build progress (from git log) — further along than ONBOARDING.md claims
metadata:
  type: project
---

# Project status (as of 2026-07-02)

**Done (committed, tagged `T-NNN:` in git log):** Phase 0 (T-001…T-010), Phase 1 (T-101…T-116),
**all of Phase 2** (T-201…T-211), and Phase 3 through **T-303** (LangGraphOrchestrator T-301,
CheckpointStore InMemory+Postgres T-302, breakpoints/HITL/time-travel T-303).

**Remaining:**
- Phase 3: T-304 (investigation tools + playbooks), T-305 (VectorLongTermMemory), T-306 (investigation + resume-after-restart eval)
- Phase 4: T-401…T-405 (OTel tracing, performance pass, security review + offline network-deny, packaging, eval/CI gates)

**Why:** `ONBOARDING.md` says only Phase 0+1 are done and "next is Phase 2" — it predates the
Phase-2/3 commits and is **stale**. Trust `git log --oneline` over ONBOARDING.md for progress.

**How to apply:** pick the lowest-numbered remaining ticket whose Depends-on are done
(docs/roadmap/backlog.md). Verify baseline with `mvn clean install` (JDK 25+, Maven 3.9+).
See [[improvement-backlog]] for the prioritized wider list, and note the queued doc fixes there
(ArchUnit references are wrong — arch tests use the JDK Class-File API, JEP 484).

Repo: https://github.com/jotder/inspect-agent (personal `jotder`; commits authored
rahul@gammanalytics.com — org transfer still an open item).
