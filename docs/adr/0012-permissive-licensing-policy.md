---
type: adr
title: "ADR-0012: Permissive-only dependency licensing (Apache-2.0 / MIT / BSD)"
description: "Architecture decision: all runtime dependencies must carry permissive licenses; model weights are chosen (and license-checked) per client deployment."
timestamp: "2026-07-03T00:00:00+05:30"
tags: ["licensing"]
---
# ADR-0012: Permissive-only dependency licensing (Apache-2.0 / MIT / BSD)

- **Status:** Proposed (confirm at next stakeholder review — flagged "not sure" in the original
  brainstorm; recorded now so the policy is explicit rather than implicit)
- **Date:** 2026-07-03

## Context

The platform is an embeddable library shipped inside client products, deployed offline,
on-prem, or in the cloud. The [roadmap](../roadmap/roadmap.md#open-questions-to-resolve-at-kickoff-from-the-brainstorm)
carried an open question: *"Licensing/compliance regime — confirm Apache-2/MIT-only policy; …
Record as an ADR if a regime is mandated."* Every substrate choice already made happens to
qualify — LangChain4j is Apache-2.0, LangGraph4j is MIT ([ADR-0003](0003-foundation-langchain4j-bom.md),
[ADR-0005](0005-orchestration-agentic-then-langgraph4j.md)) — but nothing enforced this for
future additions.

## Decision

1. **Runtime (compile/runtime-scope) dependencies must carry a permissive license:**
   Apache-2.0, MIT, or BSD-2/3-Clause. **No GPL, AGPL, or LGPL** in distributed artifacts.
   Weak-copyleft (EPL/MPL) requires an explicit ADR before adoption.
2. **Test-scope dependencies** follow the same default; exceptions are tolerable but must be
   noted in the BOM.
3. **Model weights are a per-deployment concern**, not a platform dependency: the Application
   Pack's `ModelProfile` selects the model, and the pack owner verifies the weight license fits
   that client's deployment (e.g. Apache-2.0 Qwen vs. research-only licenses).
4. Enforcement is by **review at BOM change time**: any new artifact in `eoiagent-bom` states
   its license in the PR/commit description. (An automated license-check plugin is a Phase-4
   packaging concern — see [backlog T-404](../roadmap/backlog.md#phase-4--hardening).)

## Consequences

- The two existing substrates and all current BOM entries comply; no code change required.
- A future dependency with a copyleft license is rejected at review, or quarantined behind a
  port in a separate optional module the same way experimental deps are
  ([ADR-0010](0010-isolate-experimental-deps.md)) — never in core or the default assembly.
- Client legal review gets a single document to point at instead of an implicit convention.
