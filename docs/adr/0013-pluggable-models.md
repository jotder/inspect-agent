---
type: adr
title: "ADR-0013: Models are pluggable deployment config, certified by eval — never a platform dependency"
description: "Architecture decision: model choice must be swappable per deployment without recompiling; new models are adopted by passing the certification eval, not by code change."
timestamp: "2026-07-03T21:00:00+05:30"
tags: ["pluggable-models"]
---
# ADR-0013: Models are pluggable deployment config, certified by eval — never a platform dependency

- **Status:** Accepted
- **Date:** 2026-07-03

## Context

Local open-weight models are improving on a monthly cadence (e.g. Qwen 2.5 → Qwen 3.5 →
Ornith 1.0, released 2026-06-25, an agentic-coding 9B that serves via Ollama/GGUF). The
platform owner's directive: *"we should have a mechanism to be able to change/plug newer
models as model progresses."*

Today the architecture is only half pluggable:

1. The chat model is chosen by the Application Pack's `ModelProfile` at **compile time** —
   swapping models means editing and rebuilding the pack
   ([PlatformBuilder.buildGateway](../specs/application-pack.md)).
2. `Lc4jChatGateway` does not map tool calls (`toolCalls()` always empty), so **no real model
   can drive the agent loop at all** — model choice is currently moot. Fixed by T-350.
3. There is no objective way to decide whether a candidate model is *good enough*: adopting a
   new model is an opinion, not a measurement.

## Decision

1. **Config outranks pack.** `eoiagent.model.chat.provider`, `.modelId`, `.baseUrl` (and the
   embedding equivalents) are read at platform assembly; when present they override the pack's
   `ModelProfile`. The pack profile is the *default*, not a binding. A deployment swaps models
   by editing config — no recompilation, no new jar. (T-354.)
2. **The provider seam stays generic.** All chat access goes through the two OpenAI-era local
   adapters (`ollama`, `openai-compatible`) plus the `LlmGateway` port; tool-call mapping is
   implemented once at the LangChain4j seam (T-350) and MUST stay model-agnostic — never
   per-model prompt hacks in core. Model-specific quirks belong in the pack's `PromptProfile`.
3. **New models are adopted by certification, not by code change.** The golden eval suite,
   run against a live endpoint (`eoiagent-eval` harness + the tool-call/citation/navigation
   assertions), is the acceptance gate for any candidate model (T-356). A model that passes is
   supported; one that fails is rejected with a scored report. "Should we use model X?" becomes
   a measurement.
4. **Multi-model routing is config too.** `RoutingLlmGateway` already chains backends with
   offline-fail-closed policy; per-task selection (e.g. an agentic-coding model for SQL/
   investigation sub-agents, a general instruct model for QA/navigation) is expressed in
   `ModelProfile`/config, never hardcoded.

## Consequences

- Chasing the model frontier becomes an ops activity (edit config, run certification, deploy),
  not an engineering project.
- The certification suite becomes a maintained artifact with real assertions per capability
  (tool-call fidelity, citation grounding, navigation classification) — extended whenever a
  new capability lands in the loop.
- The platform never claims "works with model X" in docs; it claims "certified models pass
  this suite" and ships the suite.
- Depends on: T-350 (tool-call mapping), T-354 (config-first selection), T-356 (certification
  run). See [backlog Phase 3.5](../roadmap/backlog.md).
