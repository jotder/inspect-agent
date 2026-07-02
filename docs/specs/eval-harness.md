---
type: spec
title: "Eval Harness — Spec"
description: "The \"definition of done\" measurement engine: golden cases, assertions, and a regression suite that runs offline and online in CI."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["eval-harness"]
---
# Eval Harness — Spec

> The "definition of done" measurement engine: golden cases, assertions, and a regression suite
> that runs offline and online in CI. Module: `com.eoiagent:eoiagent-eval`
> (`com.eoiagent.eval`). Not a numbered runtime component — it is the cross-cutting test rig
> referenced by [conventions.md](../conventions.md) §8 and
> [03-deployment-profiles.md](../architecture/03-deployment-profiles.md) §Testing requirement.
> Phase 1 onward.

## Purpose

Turn "done" into something **measured, not asserted by hand**. The harness runs a **golden Q&A set**
of cases against a configured agent (`AgentService`/`AgentSession`) and checks:

- the **answer** (text/navigation/artifact) against expected outputs,
- **tool-call assertions** (which tools were invoked, with which args, in which order),
- **navigation-intent assertions** (correct `targetPageId` + parameters — the signature product
  behavior, 02-domain-model §NavigationIntent),
- and a **regression suite** that fails CI if a previously-passing case regresses.

It must run **under OFFLINE** (stub `LlmGateway` by default, or a containerized Ollama) **and at
least one online profile** in CI (03-profiles §Testing requirement). Because the team has **no
labeled set yet**, this spec recommends a pragmatic scoring approach (exact-match / contains /
LLM-judge with a local model) and a case schema that lets the set grow incrementally.

## Port interface(s) — harness API & case schema

The eval module is not a runtime port; it defines its own small, stable **harness API** plus a
**case schema**. (Per the section template: for eval-harness, define the harness API/case schema.)

### Harness API

```java
package com.eoiagent.eval;

public interface EvalHarness {
    // Run all cases in a suite against the given agent under a profile; aggregate results.
    EvalReport run(EvalSuite suite, AgentService agent, DeploymentProfile profile);
}

public interface Scorer {                          // pluggable scoring strategy
    Score score(EvalCase expected, EvalRunResult actual);
}

// --- harness value types (records, in com.eoiagent.eval) ---
public record EvalSuite(String name, List<EvalCase> cases) {}

public record EvalCase(
    String id,                                     // stable, unique (regression key)
    String prompt,                                 // user message text
    PageContext page,                              // page-context fixture (core type)
    Role role,                                     // role to run as (core type)
    Expectation expect,                            // what "correct" means for this case
    Set<String> tags) {}                           // e.g. "phase1","navigation","sql","smoke"

public record Expectation(
    AnswerKind expectedKind,                       // TEXT | INLINE_ARTIFACT | NAVIGATION | CLARIFICATION | ERROR
    AnswerAssertion answer,                        // how to match the answer (see below)
    List<ToolCallAssertion> toolCalls,             // expected tool invocations (order-sensitive flag)
    NavigationAssertion navigation,                // nullable: only for NAVIGATION cases
    List<String> mustCiteSourceIds) {}             // RAG provenance expectations (nullable/empty)

public enum MatchMode { EXACT, CONTAINS, REGEX, LLM_JUDGE }
public record AnswerAssertion(MatchMode mode, String expected, double minScore) {} // minScore for LLM_JUDGE

public record ToolCallAssertion(
    String toolName,
    Map<String,Object> argsSubset,                 // expected args (subset match); empty = any args
    boolean mustBeAbsent) {}                        // true asserts the tool was NOT called

public record NavigationAssertion(
    String targetPageId,
    Map<String,String> requiredParams,             // subset of NavigationIntent.parameters
    MatchMode rationaleMode, String rationale) {}   // optional rationale check

public record EvalRunResult(
    AgentAnswer answer,                            // the produced answer (core type)
    List<ToolCall> toolCalls,                      // observed from the audit stream (TOOL_CALL events)
    List<String> citedSourceIds,
    RunId run) {}

public record Score(boolean pass, double value, String detail) {} // value in [0,1]
public record CaseOutcome(EvalCase case_, Score score, EvalRunResult actual) {}
public record EvalReport(
    String suite, DeploymentProfile profile,
    int total, int passed, int failed,
    List<CaseOutcome> outcomes, Instant at) {}
```

Contract notes:

- **`EvalHarness.run(...)`** — opens an `AgentSession` per case via `AgentService.open(...)` with the
  case's `role`/`page`/`profile`, sends `prompt`, captures the `AgentAnswer`, and reconstructs
  `toolCalls`/`citedSourceIds` from the **audit stream** (`TOOL_CALL`/`RETRIEVAL` `AuditEvent`s — see
  [audit-observability.md](audit-observability.md)) rather than from internal hooks, so the harness
  observes the same record compliance does. Aggregates into an `EvalReport`. Deterministic ordering
  of cases; isolates failures (one failing case never aborts the suite).
- **`Scorer.score(expected, actual)`** — applies the case's `MatchMode`(s) and returns a `Score`.
  The default `CompositeScorer` evaluates: kind match → answer match → tool-call assertions →
  navigation assertions → citation assertions; `pass` is the AND of all required checks.
- **`Span`/threading** — cases run sequentially by default (deterministic, easy to debug); a
  `parallelism` config may fan out across sessions (conventions §6 allows parallel runs across
  sessions).

### Case file schema (YAML; JSON accepted)

Cases live in `eoiagent-eval/src/test/resources/eval/<suite>/*.yaml`. One file may hold many cases.

```yaml
suite: phase1-product-help
cases:
  - id: nav-pipeline-failures-q3        # stable regression key
    prompt: "Why did the ingestion pipeline fail last quarter?"
    role: ANALYST
    page:
      pageId: pipeline-overview
      entityIds: { pipelineId: "pl-123" }
      filters: { period: "Q3" }
    expect:
      expectedKind: NAVIGATION
      navigation:
        targetPageId: pipeline-run-history
        requiredParams: { pipelineId: "pl-123", status: "FAILED", period: "Q3" }
      toolCalls:
        - toolName: list_pipeline_runs        # read-only; subset args
          argsSubset: { pipelineId: "pl-123" }
        - toolName: run_pipeline               # mutating tool must NOT be called
          mustBeAbsent: true
      mustCiteSourceIds: []
  - id: qa-what-is-iceberg
    prompt: "What is an Iceberg table in our stack?"
    role: USER
    page: { pageId: docs-home, entityIds: {}, filters: {} }
    expect:
      expectedKind: TEXT
      answer: { mode: LLM_JUDGE, expected: "explains Iceberg table format / our usage", minScore: 0.7 }
      mustCiteSourceIds: [ "docs/iceberg-overview" ]
```

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `YamlEvalCaseLoader` | `org.yaml:snakeyaml` (via `eoiagent-bom`) | Phase 1 | Loads `*.yaml`/`*.json` case files into `EvalSuite`. |
| `CompositeScorer` | ours — JDK only | Phase 1 | Runs kind/answer/tool/navigation/citation checks; aggregates `Score`. |
| `ExactMatchScorer` / `ContainsScorer` / `RegexScorer` | ours — JDK only | Phase 1 | The deterministic `MatchMode` strategies; offline, no model. |
| `LlmJudgeScorer` | reuses `eoiagent-model` `LlmGateway` (local model) | Phase 1 (opt-in) → default in P2 | Model-graded answer match via a **local** model (OFFLINE = containerized Ollama or stub returns fixed scores); returns `Score.value` vs `minScore`. Never calls a hosted model in OFFLINE. |
| `DefaultEvalHarness` | ours — depends on `eoiagent-host` (`AgentService`) + audit stream | Phase 1 | Orchestrates `run(...)`, reconstructs tool calls from audit, builds `EvalReport`. |
| `JUnitEvalRunner` | JUnit 5 `@TestFactory` | Phase 1 | Turns each `EvalCase` into a dynamic JUnit test so CI reports per-case pass/fail. |
| `MarkdownReportWriter` / `JsonReportWriter` | ours — JDK only | Phase 1 | Emit `EvalReport` for CI artifacts + score deltas vs baseline (regression). |

## Maven coordinates

This module: `com.eoiagent:eoiagent-eval` (version `0.1.0-SNAPSHOT`, inherits `eoiagent-bom`).

| Coordinate | Version source | Scope | Why |
|------------|----------------|-------|-----|
| `com.eoiagent:eoiagent-core` | project (`0.1.0-SNAPSHOT`) | compile | domain types (`AgentAnswer`, `AnswerKind`, `NavigationIntent`, `ToolCall`, `Role`, `PageContext`, `RunId`) |
| `com.eoiagent:eoiagent-host` | project | test/compile | `AgentService`/`AgentSession` to run cases against |
| `com.eoiagent:eoiagent-observability` | project | test | read the audit stream (tool-call/retrieval reconstruction) |
| `com.eoiagent:eoiagent-model` | project | test | `LlmGateway` for `LlmJudgeScorer` and the stub gateway |
| `org.yaml:snakeyaml` | `eoiagent-bom` | compile | case file parsing |
| `org.junit.jupiter:junit-jupiter` | `eoiagent-bom` | test | JUnit 5 `@TestFactory` |
| `org.assertj:assertj-core` | `eoiagent-bom` | test | assertions |
| `org.testcontainers:testcontainers` + `ollama` module | `eoiagent-bom` | test (CI profile) | containerized Ollama for the online/local-model CI leg |

No hardcoded versions — all via `eoiagent-bom` (conventions §1). The eval module may depend on
adapter modules because it is a **test/measurement** module, not a runtime port (it does not violate
the core/ports purity rule — it sits above everything).

## Inputs / Outputs

Consumes: `EvalSuite`/`EvalCase` (loaded from YAML/JSON), and at runtime an `AgentService`,
`DeploymentProfile`, and the **audit stream** of each run. Core types in/out: `UserMessage`,
`PageContext`, `Role`, `AgentAnswer`, `AnswerKind`, `NavigationIntent`, `ToolCall`, `RunId`,
`Citation`.

Produces: `EvalReport` (per profile), `CaseOutcome` per case, `Score` per case, and CI artifacts
(`report.md`, `report.json`) including a **regression delta** vs the committed baseline.

## Behavior / algorithm

References Flow A (product help / navigation) and Flow B/C (tool calls) in
[04-sequence-flows.md](../architecture/04-sequence-flows.md).

**`DefaultEvalHarness.run(suite, agent, profile)`**
1. For each `EvalCase` (deterministic order):
   a. `session = agent.open(SessionRequest{user, role=case.role, page=case.page, profile})`.
   b. `answer = session.ask(new UserMessage(case.prompt, case.page, now))`.
   c. Reconstruct `EvalRunResult`: pull `TOOL_CALL`/`RETRIEVAL` `AuditEvent`s for `answer.run()` from
      a `RecordingAuditSink` wired into the test agent → `toolCalls`, `citedSourceIds`.
   d. `score = scorer.score(case, runResult)`.
   e. `session.close()`; append `CaseOutcome`.
2. Aggregate counts; build `EvalReport`.

**`CompositeScorer.score(expected, actual)`** (all required checks ANDed; first hard failure short-
circuits with detail):
1. **Kind**: `actual.answer.kind() == expected.expectedKind` else fail.
2. **Answer**: apply `AnswerAssertion.mode`:
   - `EXACT` — normalized equality.
   - `CONTAINS` — case-insensitive substring.
   - `REGEX` — pattern match.
   - `LLM_JUDGE` — `LlmJudgeScorer` returns `[0,1]`; pass iff `>= minScore`.
3. **Tool calls**: for each `ToolCallAssertion`: if `mustBeAbsent`, assert the tool is absent from
   `actual.toolCalls`; else assert present with `argsSubset` matched (subset, not exact). If the
   assertion list is declared order-sensitive, also assert relative order.
4. **Navigation** (when `expectedKind==NAVIGATION`): assert `actual.answer.navigation().targetPageId`
   equals expected, and `requiredParams` ⊆ `navigation.parameters()`; optional rationale match.
5. **Citations**: `mustCiteSourceIds` ⊆ `actual.citedSourceIds`.
6. `pass = AND of all`; `value` = fraction of checks passed (for trend reporting).

**Profiles & CI legs (03-profiles §Testing requirement):**
- **OFFLINE leg (default, always runs):** agent wired with a **stub `LlmGateway`** that returns
  recorded/scripted responses keyed by case id (fully deterministic, no model, no network) — OR a
  **containerized Ollama** for a more realistic run. The stub is the default so the suite is green
  with zero infra; the Ollama variant is profile-tagged. Deterministic scorers only by default;
  `LLM_JUDGE` uses a stub judge returning fixed scores when no local model is present.
- **Online leg (≥1 profile, CI):** `ON_PREM_HOSTED` or `CLOUD` with a real local/hosted model and
  `LlmJudgeScorer` enabled. Tagged so it can be gated by credentials/availability.
- A `Feature` marked ✗ for OFFLINE must have a case proving it is **unreachable** in that profile
  (03-profiles rule; e.g. a case asserting `HOSTED_MODELS` use fails closed with `PolicyViolation`).

**Regression suite:** the committed baseline (`baseline/<suite>.<profile>.json`) records each case's
last-known `pass`/`value`. CI fails if any case flips pass→fail, or if aggregate score drops below a
configured threshold. New cases start "unbaselined" and don't break the build until promoted.

**Bootstrapping with no labeled set (recommended path):** start with deterministic, high-confidence
cases (`EXACT`/`CONTAINS` facts, exact `NavigationAssertion` for known page routes, `mustBeAbsent`
for mutating tools) — these need no labels. Add `LLM_JUDGE` cases incrementally with a written
rubric in `expected`; calibrate `minScore` against a handful of hand-graded examples. Capture real
sessions and freeze the good ones into golden cases over time (the audit stream gives you tool-call
ground truth for free).

## Configuration keys

Prefix `eoiagent.eval.` (read by the harness; not part of the runtime `ConfigProvider` defaults but
documented here per the template).

| Key | Type | OFFLINE | ON_PREM_HOSTED | CLOUD | Meaning |
|-----|------|---------|----------------|-------|---------|
| `eoiagent.eval.profile` | `DeploymentProfile` | `OFFLINE` | `ON_PREM_HOSTED` | `CLOUD` | Profile to run the suite under. |
| `eoiagent.eval.gateway` | enum `stub`/`ollama`/`live` | `stub` | `ollama`/`live` | `live` | Which `LlmGateway` backs the agent. `stub` = no network/no model. |
| `eoiagent.eval.judge.enabled` | boolean | `false` | `true` | `true` | Enable `LlmJudgeScorer` (`LLM_JUDGE` cases). |
| `eoiagent.eval.judge.model` | string | (n/a) | local model id | local/hosted id | Model for the judge (local in OFFLINE/on-prem). |
| `eoiagent.eval.regression.baseline` | path | `baseline/` | `baseline/` | `baseline/` | Baseline directory for regression deltas. |
| `eoiagent.eval.regression.minScore` | double | `0.0` | `0.0` | `0.0` | Fail CI if aggregate score drops below this. |
| `eoiagent.eval.parallelism` | int | `1` | `1` | `4` | Concurrent sessions (deterministic default `1`). |

## Error handling

Typed exceptions (conventions §5):

- **`ConfigException`** — malformed case file (missing `id`/`prompt`/`expect`), duplicate case `id`,
  unknown `MatchMode`, or `eval.gateway=live` selected under `OFFLINE` (fail-closed: refuse network).
- A case whose run throws (e.g. `PolicyViolation`, `GuardrailViolation`, `ApprovalDeniedException`,
  `ModelUnavailableException`) is **not** a harness crash: the harness records it as a `CaseOutcome`
  with `pass=false` (or `pass=true` if the case `expectedKind==ERROR` and the failure was expected),
  so the suite continues. Exception type is captured in `Score.detail`.

Failure isolation: one bad case never aborts the suite. The harness never silently passes a case it
could not evaluate — an un-scoreable case is a failure with detail.

## Acceptance criteria

- **AC1** `YamlEvalCaseLoader` parses the documented YAML/JSON schema into `EvalSuite`; rejects
  duplicate `id`s and missing required fields with `ConfigException`.
- **AC2** `DefaultEvalHarness.run` produces an `EvalReport` whose `total == cases`, with one
  `CaseOutcome` per case, and isolates a throwing case (suite still completes).
- **AC3** Deterministic scorers: `EXACT`/`CONTAINS`/`REGEX` answer assertions pass/fail correctly on
  fixture answers with **no model and no network**.
- **AC4** Tool-call assertions: a case with `mustBeAbsent: true` for a mutating tool fails if that
  tool was invoked, and passes if it was not (reconstructed from the audit `TOOL_CALL` stream).
- **AC5** Navigation assertions: a `NAVIGATION` case passes only when `targetPageId` matches and
  `requiredParams ⊆ navigation.parameters`.
- **AC6** The full suite runs **OFFLINE with the stub `LlmGateway`** (no network, no live LLM) and
  is green; `mvn` invocation below works with zero external infra.
- **AC7** Regression: introducing a change that flips a baselined case pass→fail makes the CI
  regression check fail; a new (unbaselined) case does not break the build.
- **AC8** Online leg: with `eval.gateway=ollama|live` and `judge.enabled=true`, `LlmJudgeScorer`
  scores `LLM_JUDGE` cases against a local/hosted model; OFFLINE never selects a hosted model
  (fail-closed `ConfigException`/`PolicyViolation`).

## Test plan

The harness *is* the test rig, so its own tests verify it on **fixtures** (a tiny built-in suite +
a stub agent), all **offline, no live LLM**:

- **Unit** — `YamlEvalCaseLoaderTest` (AC1), `CompositeScorerTest` + `ExactMatchScorerTest` /
  `ContainsScorerTest` / `RegexScorerTest` (AC3), `ToolCallAssertionTest` (AC4),
  `NavigationAssertionTest` (AC5), `RegressionDeltaTest` (AC7).
- **Harness self-test** — `DefaultEvalHarnessTest` (AC2, AC6): runs a built-in `phase1-smoke` suite
  against a **`StubAgentService`** (backed by a scripted `LlmGateway`) and a `RecordingAuditSink`;
  asserts the report and offline cleanliness.
- **Online (profile-tagged, CI)** — `OllamaEvalIT` (AC8): `@Tag("online")`, runs a subset against
  containerized Ollama with the judge enabled; skipped when unavailable.
- The golden suites under `src/test/resources/eval/` are themselves executed via `JUnitEvalRunner`'s
  `@TestFactory` so every case is a visible CI test (conventions §8 "eval tests").

Run (offline default, no infra): `mvn -pl eoiagent-eval -am test`
Run online leg: `mvn -pl eoiagent-eval -am verify -Peval-online` (Testcontainers Ollama).

## Dependencies on other modules

- **`eoiagent-core`** — all domain types asserted on.
- **`eoiagent-host`** — `AgentService`/`AgentSession` is the system-under-test entry point.
- **`eoiagent-observability`** — tool-call/retrieval ground truth read from the audit stream
  ([audit-observability.md](audit-observability.md)); this is *why* the harness trusts the same
  record compliance uses.
- **`eoiagent-model`** — stub + real `LlmGateway` for the OFFLINE/online legs and `LlmJudgeScorer`.
- **`eoiagent-config`** — provides the `DeploymentProfile` the suite runs under.
- (Transitively exercises `eoiagent-tool`, `eoiagent-knowledge`, `eoiagent-runtime`,
  `eoiagent-safety` through the agent, but does not compile against their internals.)

## Out of scope / deferred

- **Human-annotation / labeling UI** for building golden sets — out of scope (team curates YAML by
  hand; capture-and-freeze from real sessions over time).
- **Statistical significance / A-B model comparison harness** — **deferred, Phase 4.**
- **Safety/red-team eval suite** (adversarial guardrail/approval probing) — **deferred, Phase 2**
  once output guardrails and approval flows land (cross-refs
  [guardrails.md](guardrails.md), [approval-governance.md](approval-governance.md)).
- **Latency/cost benchmarking** — belongs with `TraceCollector` (Phase 4), not scoring correctness.
- **Multilingual cases** (C8: English only).

## Related ADRs & flows

- [conventions.md](../conventions.md) §9 — Definition of Done (eval pass gates "done"; no dedicated ADR)
- [ADR-0009 — Audit trail & observability](../adr/0009-audit-trail-and-observability.md)
  (audit stream is the tool-call ground truth)
- [03-deployment-profiles.md](../architecture/03-deployment-profiles.md) §Testing requirement
- [04-sequence-flows.md](../architecture/04-sequence-flows.md) — Flow A (navigation), B/C (tool calls)
- [conventions.md](../conventions.md) §8 (testing), §9 (definition of done)
