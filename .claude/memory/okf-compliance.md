---
name: okf-compliance
description: "docs/ is now a conformant Google OKF v0.1 bundle; the \"SDLC vibe coding\" whitepaper is the sibling practices reference"
metadata: 
  node_type: memory
  type: project
  originSessionId: c51a515a-bc6b-4155-97de-c9ef026604a3
---

On 2026-06-27 rahul asked "where we stand to comply" with two references for the [[eoi-agent-project]]:
1. A Google Drive PDF = **"The New SDLC With Vibe Coding"** (Osmani/Saboo/Kartakis, Google, May 2026; Day-1 of a series) — a whitepaper of *practices* (agentic engineering, the six context types, harness model, factory model), **not** a hard standard. Stance: strong alignment, kept as an assessment only. Product has MCP but not A2A — and rahul explicitly decided **not** to add A2A (2026-06-27); don't re-pitch it.
2. **Google Open Knowledge Format (OKF) v0.1** (`GoogleCloudPlatform/knowledge-catalog`, `okf/SPEC.md`) — the concrete markdown format. Hard rule: every non-reserved `.md` needs YAML frontmatter with a non-empty `type`.

We retrofitted `docs/` into a conformant OKF bundle: prepended `type/title/description/timestamp/tags` frontmatter to all 35 concept files, added bundle-root `docs/index.md` declaring `okf_version: "0.1"`, and added a zero-dep checker `tools/okf/check.mjs` (passes, exit 0).

**Why:** both references are the same theme — context engineering for AI agents — and this repo is built by AI agents, so its docs *are* curated agent context.

**How to apply:** run `node tools/okf/check.mjs` after editing docs; any new `docs/**/*.md` concept file needs frontmatter with a non-empty `type`. Bundle = `docs/` only — root `README.md`/`CLAUDE.md`/`AGENTS.md` are intentionally excluded (GitHub renders leading YAML frontmatter as a table). `docs/index.md` is currently a static artifact (no committed generator). After doc changes also re-run `node tools/graphify/generate.mjs` per [[eoi-agent-project]] convention.
