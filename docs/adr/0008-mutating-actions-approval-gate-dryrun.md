---
type: adr
title: "ADR-0008: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime"
description: "Architecture decision: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["mutating-actions-approval-gate-dryrun"]
---
# ADR-0008: All mutating actions require an ApprovalGate + dry-run, enforced in the runtime

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

In v1 the agent **may take mutating actions** — create/run pipelines, edit configs, write
data, trigger jobs — because the product needs operational capability, not just answers.
That makes safety a runtime property, not a prompt suggestion.

Constraint **C4** is hard: **every mutating action passes an approval gate and supports
dry-run**. Design principle 5 ([`../architecture/00-overview.md`](../architecture/00-overview.md)
§3) reinforces it: *safety is in the runtime, not the prompt.* A model that is merely *asked*
to confirm can be jailbroken or can hallucinate consent; the guarantee must hold in code.

## Decision

Enforce approval + dry-run for mutating actions **inside the runtime**:

- **Tools are classified read-only vs mutating.** Every `ToolSpec` declares
  `mutating` (boolean), `requiredRole`, and `capability`
  ([`../architecture/02-domain-model.md`](../architecture/02-domain-model.md) §Tools).
- **`ToolRegistry.dispatch(...)` enforces the gate.** For a mutating tool, dispatch first
  produces a **`DryRunResult` preview via `ApprovalGate.dryRun(call)`**, then **blocks on
  `ApprovalGate.request(ApprovalRequest)`** until `APPROVED` / `DENIED` / `TIMED_OUT`. A
  `DENIED`/`TIMED_OUT` decision aborts the action (`ApprovalDeniedException`).
- The **host supplies an `ApprovalHandler`**; the MVP adapter is `CallbackApprovalGate`
  (`eoiagent-safety`), with `RoleBasedPolicyEngine` checking
  `PolicyEngine.allows(role, capability, profile)` first.
- **Invariant:** there is **no mutating `ToolResult` without a preceding `APPROVED`
  `AuditEvent`** (`AuditKind.APPROVAL`, then `AuditKind.MUTATION`); see
  [ADR-0009](0009-audit-trail-and-observability.md). `MUTATING_ACTIONS` is always behind the
  gate in every profile ([capability matrix](../architecture/03-deployment-profiles.md#capability-matrix)).

This satisfies constraint **C4**.

## Consequences

**Positive**
- **Safe-by-construction** ETL/ops actions: nothing mutating commits without a previewed,
  approved, audited decision — independent of what the prompt says.
- Read-only tools are unaffected, so the read-only MVP path stays fast.

**Negative / follow-ups**
- Requires the **host to implement an approval UI/callback**; without one, mutating tools
  cannot proceed.
- **Headless / offline policy:** with no interactive operator, the configured policy either
  **auto-denies** or **requires an operator token** to approve; this must be set per
  deployment (`eoiagent.approval.required` defaults to `true` in every profile).
- Dry-run quality depends on each tool implementing a meaningful `DryRunResult`
  (`supported`, `preview`, `predictedEffects`); a tool that cannot preview must say so.

**Risks / mitigation**
- Risk: a mutating tool bypasses `dispatch`. Mitigation: tools are only reachable via
  `ToolRegistry.dispatch`, and the "no MUTATION AuditEvent without a preceding APPROVAL"
  invariant is asserted by tests.

## Alternatives considered

- **Prompt-only "please confirm"** — relies on the model to self-police; unsafe and
  bypassable. Rejected (violates "safety in the runtime, not the prompt").
- **No mutations in v1** (read-only only) — simplest and safest, but the product explicitly
  needs operational actions (run pipelines, edit configs, write data). Rejected.
