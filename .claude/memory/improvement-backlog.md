---
name: improvement-backlog
description: Doc-mined, prioritized improvement & optimization ideas (analysis of 2026-07-02)
metadata:
  type: project
---

# Improvement & optimization backlog (mined from docs, 2026-07-02)

Sources: every spec's "Out of scope / deferred" section, docs/roadmap/{roadmap,backlog}.md,
graphify-out/GRAPH_REPORT.md, ONBOARDING.md, and the two root .docx files (original brainstorm +
stakeholder overview). Cross-checked against git log — see [[project-status]].

## 1. Immediate: finish Phase 3 — ✅ DONE (2026-07-03, see [[project-status]])

- ~~T-304 — investigation tools + root-cause playbooks~~ committed `81028e0` (10 tests green)
- ~~T-305 — VectorLongTermMemory (cross-session recall)~~ committed `4890c58`
- ~~T-306 — investigation eval + resume-after-restart test~~ committed `65df452`

The whole numbered roadmap (T-001…T-405) is now committed. Only OPEN item is T-402's
live-model latency AC — BLOCKED until Ollama is installed on this box. Discretionary work =
§2 optimizations and §5 reflection loop below. **[[project-status]] is the source of truth.**

## 2. Now-unblocked "Phase 2+" optimizations (dependencies landed)

| Idea | Documented in | Ripe because |
|---|---|---|
| Tool result caching/memoization + parallel tool fan-out | specs/tool-registry.md §deferred | latency win; dispatch path stable |
| Token-budget / cost-aware routing + response caching | specs/model-gateway.md §deferred | RoutingLlmGateway exists |
| Cross-encoder re-ranking / hybrid BM25+vector search | specs/rag-knowledge.md §deferred | AdvancedRetriever (T-208) landed |
| Safety/red-team eval suite | specs/eval-harness.md §deferred | gated on T-202/T-210 — both done |
| Secret/vault integration for apiKey config | specs/config-profiles.md §deferred | marked Phase 2+ |
| LLM-judge + embedding-based injection guardrails | specs/guardrails.md §deferred | reuse stub gateway / ONNX embeddings |

## 3. Phase 4 hardening (scheduled, untouched)

T-401 OTel tracing · T-402 performance pass (latency budgets, embedding/retrieval tuning — the
only pure-optimization ticket) · T-403 security review + offline network-deny proof · T-404
packaging (shaded jar/JPMS) · T-405 regression baseline + CI gates. Plus spec-deferred: audit
hash-chaining (the `seq` column is the hook), per-run token/cost accounting, per-tool rate
limits, multi-approver/quorum, memory TTL/forgetting, scratchpad encryption-at-rest,
multi-pack `AppPackRegistry` / pack hot-reload, host follow-ups + answer feedback.

## 4. Queued documentation fixes (docs admit these)

1. **ONBOARDING.md is stale** (claims Phase 0+1 only; reality is through T-303) — highest-leverage fix.
2. ArchUnit references wrong in ADR-0010 / conventions / backlog T-005 — arch tests actually use JDK Class-File API (JEP 484).
3. specs/reference-app-pack.md has fictional code sketches (`JavaApiTool.of`, `DocumentSource.fromClasspathDir`) and deferred ACs that may now be deliverable.
4. Graph hygiene: exclude `target/**` from graph generation (2 orphan build copies); fix graph.json format vs CLI ([[graphify-tooling-broken]]).
5. Close two open decisions as ADRs: licensing regime (Apache-2/MIT-only) and task durability (de-facto resolved by T-302).
6. Consider transferring repo `jotder/inspect-agent` → gammanalytics org.

## 5. Conceptual gap vs. the original brainstorm — ✅ CLOSED (T-500, 2026-07-05)

"The pieces a deep agent needs.docx" lists **Reflection / evaluator-critic refinement loops** as
a core piece and a stated reason for choosing LangGraph4j — but no spec/ticket implemented an
explicit critic loop (draft → review → fix) for SQL/pipeline generation.

**Done:** `ReflectionOrchestrator` (eoiagent-runtime) — our own bounded loop (NOT on LangGraph4j;
a hand-rolled loop matches the ReAct/Supervisor idiom and stays framework-free), ports-only
(LlmGateway/AuditSink/ConfigProvider), offline-safe, fully audited. draft → critique → revise,
bounded by `eoiagent.runtime.reflection.maxRevisions` (default 2). Critic verdict = reply leading
with `APPROVED`. 7 tests green (`ReflectionOrchestratorTest`); spec §6 "Flow F" + AC13 + config key
recorded. NOT yet wired into orchestrator auto-selection (that selection work was itself deferred
at T-354 — it composes via the `Orchestrator` port; wiring generation goals to it is the follow-up).

**Recommended order:** T-304–306 → red-team suite + tool caching/fan-out (quick wins) → T-402
performance pass with the reflection loop folded in → rest of Phase 4 → doc fixes anytime as one
cleanup commit.
