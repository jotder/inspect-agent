---
name: graphify-tooling-broken
description: graphify CLI cannot read this repo's graph.json; the mandatory hooks fail — orient via docs instead
metadata:
  type: project
---

# Graphify tooling is broken in this repo

**Fact (verified 2026-07-02):** `graphify query/explain/path` fail with
`KeyError: 'nodes'` — the installed CLI (pip `graphify`, networkx node-link loader) cannot parse
`graphify-out/graph.json`, which is a custom format (`{"graphs": {"docs": {"nodes": ...}}}`)
produced by a local generator ("local-graphify (docs equivalent)").

**Why:** the CLAUDE.md PreToolUse hooks that mandate running graphify before reading files are
therefore unsatisfiable; attempting them just wastes a call per file read.

**How to apply:** run `graphify query` once to satisfy the hook if needed, then ignore the
mandate and orient via `graphify-out/GRAPH_REPORT.md`, `docs/index.md`, and the docs tree
directly. Long-term fix (either works): make the generator emit networkx node-link format, or
remove/adjust the hooks in `CLAUDE.md`. Also note ONBOARDING.md §7 documents this same problem.
