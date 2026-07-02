---
type: spec
title: "Guardrails — Spec"
description: "Input and output safety checks wrapping every model interaction."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["guardrails"]
---
# Guardrails — Spec

> Input and output safety checks wrapping every model interaction. Component 7 (part) in
> [01-component-model.md](../architecture/01-component-model.md). Port(s): `Guardrail`. Module:
> `eoiagent-safety` (`com.eoiagent.safety`). Phase 1 (input) → Phase 2 (output).

## Purpose

Make the agent's I/O safe **in the runtime, not the prompt** (design principle 5). One port,
`Guardrail`, covers both directions:

- **Input guardrails** (Flow A step 1) — run before the prompt reaches the model:
  **prompt-injection detection** and **PII redaction**. Verdicts: `PASS`, `FAIL` (block the turn),
  or `REDACTED` (continue with sanitized text).
- **Output guardrails** (Flow A step 5) — run after the model responds:
  **schema/validation** of structured outputs (e.g. `NavigationIntent`, `AgentAnswer` shape) with a
  bounded **retry** when the model's output is malformed. Verdicts: `PASS`, `FAIL`, or `RETRY`.

The concrete adapters use the **experimental** `langchain4j-guardrails` module. Per
[ADR-0010](../adr/0010-isolate-experimental-deps.md) and conventions §2, that dependency MUST stay
**behind the `Guardrail` port inside the adapter module** — it must not leak into `core`, the
host-facing API, or any other module's compile classpath. The port itself is plain Java so a
non-LC4j adapter can replace it without an ADR-worthy change.

## Port interface(s)

Signature copied verbatim from [01-component-model.md](../architecture/01-component-model.md)
(Component 7):

```java
package com.eoiagent.safety;

public interface Guardrail {                         // both input + output
    GuardrailResult check(GuardrailInput in);        // pass | fail | redacted | retry
}
```

Contract notes:

- **`Guardrail.check(GuardrailInput)`** — pure, synchronous, deterministic for a given input
  (a heuristic/regex guardrail must not call the network; an LLM-judge guardrail is **not** part of
  this spec — see Out of scope). Returns a `GuardrailResult` whose `verdict` drives the caller:
  - `PASS` → proceed with original text.
  - `FAIL` → block; caller converts to `GuardrailViolation` (input) or terminates the turn
    (output) with an `AgentAnswer(ERROR)`.
  - `REDACTED` → proceed using `GuardrailResult.transformedText()` (sanitized); original is never
    sent onward (input PII case).
  - `RETRY` → caller re-invokes the model up to `eoiagent.guardrail.output.maxRetries`; if still
    failing, escalate to `FAIL` (output schema case).
  `check` MUST be idempotent and side-effect free except for the audit hook the caller wraps around
  it (this module emits no audit itself; the `Orchestrator`/`AgentSession` records `DECISION`).

### Supporting type defined in this module

`GuardrailInput` is not in 02-domain-model; define it here in `com.eoiagent.safety` (it is a port
input, allowed in the api package). It carries direction + payload so one port serves both phases:

```java
package com.eoiagent.safety;

public enum GuardrailPhase { INPUT, OUTPUT }

public record GuardrailInput(
    GuardrailPhase phase,
    String text,                       // prompt text (INPUT) or model output (OUTPUT)
    String expectedJsonSchema,         // nullable: for OUTPUT schema validation
    AgentContext ctx) {}               // role/profile/session for policy-aware redaction
```

`GuardrailResult` and `GuardrailVerdict` are canonical (02-domain-model §Safety):

```java
enum GuardrailVerdict { PASS, FAIL, REDACTED, RETRY }
record GuardrailResult(GuardrailVerdict verdict, String message, String transformedText) {}
```

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `Lc4jInputGuardrail` | `dev.langchain4j:langchain4j-guardrails` (experimental, BOM `-betaNN`) | Phase 1 | Prompt-injection detection + PII redaction. Wraps LC4j `InputGuardrail`. Heuristic/regex by default (offline-safe); returns `FAIL` on injection, `REDACTED` on PII hit. |
| `Lc4jOutputGuardrail` | `dev.langchain4j:langchain4j-guardrails` (experimental, BOM `-betaNN`) | Phase 2 | JSON-schema / structured-output validation. Wraps LC4j `OutputGuardrail`. Returns `RETRY` on malformed output (with reprompt hint), `FAIL` after `maxRetries`. |
| `RegexPiiGuardrail` *(optional fallback)* | ours — `eoiagent-safety` (no third-party) | Phase 1 | Pure-Java PII redaction (email/phone/SSN-style patterns) usable when the experimental dep is excluded; satisfies the same contract. |

The `langchain4j-guardrails` import appears **only** in the `Lc4j*` adapter classes. Verified by the
Phase-0 architecture test (conventions §2). The adapters translate LC4j's guardrail result types into
our `GuardrailResult`/`GuardrailVerdict` — LC4j types never cross the port boundary.

## Maven coordinates

This module: `com.eoiagent:eoiagent-safety` (version `0.1.0-SNAPSHOT`, inherits `eoiagent-bom`).

| Coordinate | Version source | Scope | Why |
|------------|----------------|-------|-----|
| `com.eoiagent:eoiagent-core` | project (`0.1.0-SNAPSHOT`) | compile | domain types (`GuardrailResult`, `GuardrailVerdict`, `AgentContext`) |
| `dev.langchain4j:langchain4j-guardrails` | `eoiagent-bom` (`1.16.x-betaNN`, suffix resolved at build) | compile (adapter only) | experimental guardrails; adapter-quarantined per ADR-0010 |
| `dev.langchain4j:langchain4j` | `eoiagent-bom` (1.16.3) | compile (adapter only) | LC4j core types the guardrails module needs |
| `org.junit.jupiter:junit-jupiter` | `eoiagent-bom` | test | JUnit 5 |
| `org.assertj:assertj-core` | `eoiagent-bom` | test | assertions |

**Never hardcode a version** — every LC4j artifact resolves through `eoiagent-bom` (which imports
`langchain4j-bom:1.16.3`); the `-betaNN` suffix for the experimental module is pinned in the BOM,
not in this pom (conventions §1, 02-domain-model §Maven coordinates, §Version policy).

## Inputs / Outputs

Consumes:

- `GuardrailInput(GuardrailPhase, String text, String expectedJsonSchema, AgentContext ctx)` (this module)
- `GuardrailVerdict`, `GuardrailResult`, `AgentContext` (core)

Produces:

- `GuardrailResult(GuardrailVerdict verdict, String message, String transformedText)`

Downstream effects (performed by the caller, not this module): on `REDACTED`, the
`transformedText` replaces the prompt before `LlmGateway.chat`; on `RETRY`, the caller reprompts; on
`FAIL`, the caller throws `GuardrailViolation` (input) or returns `AgentAnswer(ERROR)` (output). A
`DECISION` `AuditEvent` is recorded by the caller for every guardrail verdict.

## Behavior / algorithm

Reference: [04-sequence-flows.md](../architecture/04-sequence-flows.md) Flow A steps **1** (input)
and **5** (output).

**Input guardrail (`Lc4jInputGuardrail.check`, phase=INPUT):**
1. Run prompt-injection detection over `in.text()` (LC4j injection guardrail / heuristic ruleset:
   instruction-override phrases, role-play jailbreaks, tool-exfiltration patterns). On detection →
   `GuardrailResult(FAIL, "prompt-injection: <rule>", null)`.
2. Run PII detection/redaction. On match → return
   `GuardrailResult(REDACTED, "redacted N PII spans", redactedText)`.
3. Else `GuardrailResult(PASS, "", null)`.
4. Caller: `FAIL` → throw `GuardrailViolation` and audit `DECISION`+`ERROR`; `REDACTED` → use
   `transformedText` as the prompt and audit `DECISION`; `PASS` → continue.

**Output guardrail (`Lc4jOutputGuardrail.check`, phase=OUTPUT):**
1. If `in.expectedJsonSchema()` is non-null, validate `in.text()` against it (structured-output
   conformance — e.g. a `NavigationIntent` payload must carry `targetPageId`).
2. Valid → `GuardrailResult(PASS, "", in.text())`.
3. Invalid and caller retry budget remains → `GuardrailResult(RETRY, "<validation error to feed
   back as a reprompt hint>", null)`.
4. Caller re-invokes `LlmGateway.chat` with the hint appended, up to
   `eoiagent.guardrail.output.maxRetries`. On exhaustion the caller calls `check` once more; the
   adapter returns `FAIL` (it tracks no state itself — the *caller* owns the retry counter and asks
   for `RETRY` vs `FAIL` by how it interprets the budget; alternatively pass remaining-budget in
   `GuardrailInput.ctx().attributes()`). Recommended: caller passes `attributes["retriesLeft"]`; the
   adapter returns `RETRY` while `>0`, else `FAIL`.

Determinism: input guardrails are heuristic/regex (no model call) so OFFLINE is unaffected. Output
schema validation is local JSON-schema validation (no network). Neither guardrail performs a network
call (Flow invariant #3).

## Configuration keys

Prefix `eoiagent.`; registered in `ConfigProvider` defaults (conventions §9.4).

| Key | Type | OFFLINE | ON_PREM_HOSTED | CLOUD | Meaning |
|-----|------|---------|----------------|-------|---------|
| `eoiagent.guardrail.input.enabled` | boolean | `true` | `true` | `true` | Run input guardrails (Phase 1). |
| `eoiagent.guardrail.input.injection` | boolean | `true` | `true` | `true` | Enable prompt-injection detection. |
| `eoiagent.guardrail.input.pii` | enum `OFF`/`REDACT`/`BLOCK` | `REDACT` | `REDACT` | `REDACT` | PII handling: redact (→`REDACTED`) or block (→`FAIL`). |
| `eoiagent.guardrail.output.enabled` | boolean | `false` (P1) → `true` (P2) | `true` | `true` | Run output guardrails (Phase 2). |
| `eoiagent.guardrail.output.maxRetries` | int | `1` | `2` | `2` | Reprompt budget on schema `RETRY` before `FAIL`. |

Default profile values are equal across profiles because guardrails are offline-safe; CLOUD does not
relax them. `output.enabled` defaults `false` in Phase 1 because the output adapter ships in Phase 2.

## Error handling

Typed exceptions (conventions §5):

- **`GuardrailViolation`** — the caller throws this when an input guardrail returns `FAIL` (or PII
  policy `BLOCK`), or when an output guardrail returns `FAIL` after retries are exhausted. This
  module's `check` returns the verdict; the typed throw happens at the call site so the
  `Orchestrator` can audit `DECISION`/`ERROR` and turn it into `AgentAnswer(ERROR)`.
- **`ConfigException`** — invalid guardrail config (e.g. unknown `pii` mode).

Failure modes: the underlying experimental library throws unexpectedly → adapter catches, audits
`ERROR` via the caller's hook, and returns `FAIL` (fail-closed; conventions §5 — never silently let
unchecked input/output through). A `RETRY` that never converges escalates to `FAIL` (bounded by
`maxRetries`) — no infinite loop.

## Acceptance criteria

- **AC1** `Lc4jInputGuardrail.check` returns `FAIL` for a known prompt-injection sample set
  (instruction-override, jailbreak, tool-exfiltration) and `PASS` for benign prompts.
- **AC2** With `pii=REDACT`, `check` returns `REDACTED` and `transformedText` has emails/phones/
  ids masked; the original PII never appears in `transformedText`. With `pii=BLOCK`, the same input
  returns `FAIL`.
- **AC3** `Lc4jOutputGuardrail.check` returns `PASS` for output matching `expectedJsonSchema`,
  `RETRY` for malformed output while retries remain, and `FAIL` once the budget is exhausted.
- **AC4** No `check` call performs a network call in any profile (asserted under the network-deny
  harness) — verifies Flow invariant #3 for guardrails.
- **AC5** The `langchain4j-guardrails` package is referenced **only** from `Lc4j*` adapter classes
  (architecture-test assertion); `GuardrailResult` carrying LC4j types never crosses the port.
- **AC6** `RegexPiiGuardrail` (fallback) passes the same `GuardrailContractTest` as
  `Lc4jInputGuardrail` for the PII cases, proving the contract holds without the experimental dep.
- **AC7** `check` is deterministic: identical `GuardrailInput` yields identical `GuardrailResult`.

## Test plan

All default tests run with **no network and no live LLM** — input/output guardrails here are
heuristic/regex/JSON-schema and need no model. Where an LLM-judge variant is later added, it uses a
stub `LlmGateway` (conventions §8) and is profile-tagged.

- **Unit** — `Lc4jInputGuardrailTest` (AC1, AC2, AC7), `Lc4jOutputGuardrailTest` (AC3),
  `RegexPiiGuardrailTest` (AC6). Use a fixed injection/PII sample corpus checked into
  `src/test/resources`.
- **Contract** — `GuardrailContractTest`: abstract JUnit 5 class with one `@Test` per verdict
  (`PASS`/`FAIL`/`REDACTED`/`RETRY`); every adapter extends it (AC6 reuses it).
- **Architecture** — `GuardrailIsolationArchTest` (AC5): dependency-rule test asserting `langchain4j-guardrails`
  classes are imported only by `com.eoiagent.safety.*Lc4j*` adapters.
- **Network-deny** — `GuardrailOfflineTest` (AC4) under the shared network-deny harness.

Run: `mvn -pl eoiagent-safety -am test`

## Dependencies on other modules

- **`eoiagent-core`** — `GuardrailResult`, `GuardrailVerdict`, `AgentContext`, exception hierarchy.
- **`eoiagent-runtime`** (Component 4) — *consumer*: the `Orchestrator`/`AgentSession` calls
  `Guardrail.check` at Flow A steps 1 and 5, handles verdicts, and emits `DECISION` audit events.
  No back-dependency.
- **`eoiagent-config`** (Component 11) — reads `eoiagent.guardrail.*`.
- **`eoiagent-observability`** (Component 9) — caller records `DECISION`/`ERROR` `AuditEvent`s.

## Out of scope / deferred

- **`ApprovalGate` / `PolicyEngine`** — same component, see
  [approval-governance.md](approval-governance.md).
- **LLM-judge guardrails** (model-graded toxicity/relevance) — **deferred, Phase 2+**; would use a
  stub-able `LlmGateway` and respect the OFFLINE network ban.
- **Semantic / embedding-based injection detection** — **deferred, Phase 2** (reuses Knowledge/RAG
  embeddings; offline-capable but heavier).
- **Output guardrails beyond schema validation** (factuality, citation-grounding checks) —
  **deferred, Phase 4 hardening.**
- Multilingual PII (C8: English only in v1).

## Related ADRs & flows

- [ADR-0010 — Isolate experimental dependencies](../adr/0010-isolate-experimental-deps.md)
  (binds the `langchain4j-guardrails` quarantine)
- [ADR-0008 — Mutating actions: approval gate & dry-run](../adr/0008-mutating-actions-approval-gate-dryrun.md) *(sibling component)*
- [04-sequence-flows.md](../architecture/04-sequence-flows.md) — Flow A steps 1 & 5, invariant #3
- [01-component-model.md](../architecture/01-component-model.md) — Component 7
- [conventions.md](../conventions.md) §2 (experimental deps), §5 (errors), §7 (logging vs audit)
