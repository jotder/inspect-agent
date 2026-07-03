# Project Memory — EOI Agent (agent-brainstorm)

Shared, repo-committed memory for anyone (human or Claude session) working in this project.
Read this index at session start; open the linked files as needed. Update these files (and this
index) when project reality changes, then commit — the profile-scoped memory in
`~/.claude/projects/...` is NOT used for this project.

- [Project status](project-status.md) — real phase progress from git (ONBOARDING.md is stale)
- [Improvement & optimization backlog](improvement-backlog.md) — doc-mined, prioritized ideas (2026-07-02 analysis)
- [Graphify tooling is broken here](graphify-tooling-broken.md) — CLI can't read graph.json; how to orient instead
- [Platform wiring gotcha](platform-wiring-gotcha.md) — PlatformBuilder wires only read-only Flow B; Phase-2/3 features are adapter-only
- [Local Postgres dev](local-postgres-dev.md) — portable PG 18.2 on localhost:5432 (db eoiagent, trust auth); pgvector NOT installed
- [Office doc rendering](office-doc-rendering.md) — no LibreOffice on this box; use Word/PowerPoint COM via PowerShell
- [OKF compliance](okf-compliance.md) — docs/ is a conformant Google OKF v0.1 bundle; checker at tools/okf/check.mjs
