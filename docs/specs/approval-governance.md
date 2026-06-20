# Approval & Governance — Spec

> Human-in-the-loop gating + RBAC for the agent. Component 7 (part) in
> [01-component-model.md](../architecture/01-component-model.md). Port(s): `ApprovalGate`,
> `PolicyEngine`. Module: `eoiagent-safety` (`com.eoiagent.safety`). Phase 2.

## Purpose

Enforce constraint **C4** (mutating actions require human approval) and the platform's RBAC model
in **code paths, not prompts** (design principle 5). This module supplies two ports:

- **`ApprovalGate`** — blocks any mutating tool call until a human approves/denies, and produces a
  dry-run preview of the action's effects (Flow C). The host wires in *how* approval is collected
  via an `ApprovalHandler` callback (UI prompt, queue, operator token).
- **`PolicyEngine`** — decides whether a `Role` may exercise a `Capability` under a given
  `DeploymentProfile` (RBAC), and throws when a tool is invoked outside the caller's grant.

The hard, testable invariant this module is responsible for: **no mutating `ToolResult` is ever
produced without a preceding `APPROVED` `AuditEvent`** (Flow §"Cross-cutting invariants" #2, C4).
`ApprovalGate` and `PolicyEngine` are consumed by `ToolRegistry.dispatch(...)` (Component 3) — this
module does not call tools itself; it gates them.

## Port interface(s)

Signatures copied verbatim from [01-component-model.md](../architecture/01-component-model.md)
(Component 7). Do not change them without an ADR.

```java
package com.eoiagent.safety;

public interface ApprovalGate {
    ApprovalDecision request(ApprovalRequest req);  // BLOCKS until approve/deny/timeout
    DryRunResult dryRun(ToolCall call);             // preview effect without committing
}
public interface PolicyEngine {
    boolean allows(Role role, Capability cap, DeploymentProfile profile);
    void check(AgentContext ctx, ToolSpec tool);    // throws PolicyViolation
}
```

Contract notes per method:

- **`ApprovalGate.request(ApprovalRequest)`** — blocks the calling thread until the host resolves
  the request or the configured timeout elapses. Returns `ApprovalDecision.APPROVED`,
  `DENIED`, or `TIMED_OUT`. MUST NOT itself perform the mutation; it only collects the decision.
  Caller (the `ToolRegistry`) records an `AuditEvent(APPROVAL, ...)` with the returned decision
  before acting. Idempotent per `ApprovalRequest.run()`+`call`: re-requesting an already-decided
  request returns the cached decision (prevents double-prompting on retries).
- **`ApprovalGate.dryRun(ToolCall)`** — returns a `DryRunResult` previewing predicted effects
  without committing. If the tool/adapter cannot preview, returns
  `DryRunResult(supported=false, preview="<reason>", predictedEffects=Map.of())`. Never mutates
  state. Read-only tools need not be dry-run (callers only invoke this for `mutating` tools).
- **`PolicyEngine.allows(Role, Capability, DeploymentProfile)`** — pure, side-effect-free predicate
  over the RBAC matrix ANDed with the profile capability matrix
  ([03-deployment-profiles.md](../architecture/03-deployment-profiles.md)). Deterministic; safe to
  call for UI filtering (`ToolRegistry.visibleTo`).
- **`PolicyEngine.check(AgentContext, ToolSpec)`** — enforcement entry point. Throws
  `PolicyViolation` if `allows(ctx.role(), tool.capability(), ctx.profile())` is false, OR if
  `tool.requiredRole()` outranks `ctx.role()`. Returns normally (void) when permitted. Records no
  audit itself; the caller logs `DECISION`/`ERROR`.

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `CallbackApprovalGate` | ours — `eoiagent-safety` (no third-party) | Phase 2 | Implements `ApprovalGate`. Delegates `request` to a host-supplied `ApprovalHandler`; enforces timeout; caches decisions per request; delegates `dryRun` to a `DryRunProvider` resolved from the `ToolCall` (falls back to `unsupported`). |
| `RoleBasedPolicyEngine` | ours — `eoiagent-safety` (no third-party) | Phase 2 | Implements `PolicyEngine`. Backed by an immutable `Role × Capability` grant table, intersected with the `DeploymentProfile` capability matrix. |

Both adapters are pure-Java (no LangChain4j) so they may live in `eoiagent-safety` alongside the
ports without violating the dependency rule (conventions §1–2). Neither touches the network.

### Host-supplied SPI (defined in this module, implemented by the host)

```java
package com.eoiagent.safety;

@FunctionalInterface
public interface ApprovalHandler {
    // Host renders the request (UI prompt / queue / operator-token check) and returns a decision.
    ApprovalDecision decide(ApprovalRequest req);
}

// Optional: a tool/adapter implements this to make dry-run meaningful; resolved by tool name.
public interface DryRunProvider {
    DryRunResult preview(ToolCall call);
}
```

`CallbackApprovalGate` is constructed via builder with: an `ApprovalHandler`, a timeout
(`Duration`), a default-on-timeout policy (`TIMED_OUT` vs auto-`DENIED`), an optional
`Map<String,DryRunProvider>` keyed by tool name, and the `AuditSink` is NOT injected here (the
`ToolRegistry` owns audit emission around the gate).

## Maven coordinates

This module: `com.eoiagent:eoiagent-safety` (version `0.1.0-SNAPSHOT`, inherits `eoiagent-bom`).

Dependencies:

| Coordinate | Version source | Scope | Why |
|------------|----------------|-------|-----|
| `com.eoiagent:eoiagent-core` | project (`0.1.0-SNAPSHOT`) | compile | ports + domain types (`Role`, `Capability`, `ApprovalRequest`, etc.) |
| `org.junit.jupiter:junit-jupiter` | `eoiagent-bom` | test | JUnit 5 (conventions §8) |
| `org.assertj:assertj-core` | `eoiagent-bom` | test | assertions |
| `org.mockito:mockito-core` | `eoiagent-bom` | test | stub `ApprovalHandler` only where a hand-written fake is impractical |

No third-party agent library. No hardcoded versions — all via `eoiagent-bom` (conventions §1,
02-domain-model §Maven coordinates).

## Inputs / Outputs

Consumes (from `com.eoiagent.core` / 02-domain-model):

- `ApprovalRequest(RunId run, ToolCall call, String humanSummary, DryRunResult preview)`
- `ToolCall(String toolName, Map<String,Object> arguments, RunId run)`
- `ToolSpec(String name, String description, String jsonSchema, boolean mutating, Role requiredRole, Capability capability)`
- `AgentContext(SessionId, UserId, Role, DeploymentProfile, PageContext, Map<String,String>)`
- `Role { ADMIN, SUPPORT, ANALYST, USER }`, `Capability { … }`, `DeploymentProfile { OFFLINE, ON_PREM_HOSTED, CLOUD }`

Produces:

- `ApprovalDecision { APPROVED, DENIED, TIMED_OUT }`
- `DryRunResult(boolean supported, String preview, Map<String,Object> predictedEffects)`
- `boolean` (from `allows`); throws `PolicyViolation` (from `check`).

The `APPROVAL` / `MUTATION` `AuditEvent`s are emitted by the **caller** (`ToolRegistry`), using the
decision returned here — see [audit-observability.md](audit-observability.md).

## Behavior / algorithm

Reference flow: [04-sequence-flows.md](../architecture/04-sequence-flows.md) **Flow C — Plan →
approve → act**.

**`RoleBasedPolicyEngine.allows(role, cap, profile)`**
1. Look up `role` in the static grant table → set of `Capability` granted to that role.
2. If `cap` not in the role's set → return `false`.
3. Look up the profile's capability gating: if `cap` requires a `Feature` that the profile
   disables, return `false`. Specifically, mutating capabilities
   (`RUN_PIPELINE`, `EDIT_CONFIG`, `WRITE_DATASTORE`, `TRIGGER_JOB`, `AUTHOR_PIPELINE`) require
   `MUTATING_ACTIONS` (enabled in all profiles, gated) — but a client may further restrict.
4. Return `true`.

**Role × Capability grant table (default; a client may further restrict, never loosen):**

| Capability | ADMIN | SUPPORT | ANALYST | USER |
|------------|:-----:|:-------:|:-------:|:----:|
| `READ_DOCS` | ✓ | ✓ | ✓ | ✓ |
| `READ_METADATA` | ✓ | ✓ | ✓ | ✓ |
| `READ_SCHEMA` | ✓ | ✓ | ✓ | ✗ |
| `RUN_SQL_READONLY` | ✓ | ✓ | ✓ | ✗ |
| `GENERATE_SQL` | ✓ | ✓ | ✓ | ✗ |
| `INVESTIGATE` | ✓ | ✓ | ✗ | ✗ |
| `AUTHOR_PIPELINE` | ✓ | ✗ | ✗ | ✗ |
| `RUN_PIPELINE` | ✓ | ✗ | ✗ | ✗ |
| `EDIT_CONFIG` | ✓ | ✗ | ✗ | ✗ |
| `WRITE_DATASTORE` | ✓ | ✗ | ✗ | ✗ |
| `TRIGGER_JOB` | ✓ | ✓ | ✗ | ✗ |

(This default table is the spec's recommendation; it is data, loaded into the adapter, and itself
auditable. The exact grants are confirmable against product user tiers in glossary §Product-surface.)

**`RoleBasedPolicyEngine.check(ctx, tool)`**
1. If `tool.requiredRole()` outranks `ctx.role()` (rank order `ADMIN > SUPPORT > ANALYST > USER`)
   → throw `PolicyViolation` (`"role <X> below required <Y> for tool <name>"`).
2. If `!allows(ctx.role(), tool.capability(), ctx.profile())` → throw `PolicyViolation`.
3. Return.

**`CallbackApprovalGate.dryRun(call)`**
1. Resolve a `DryRunProvider` by `call.toolName()`.
2. If none → return `DryRunResult(false, "no dry-run available for " + name, Map.of())`.
3. Else delegate to `provider.preview(call)` (provider MUST be read-only); return its result.

**`CallbackApprovalGate.request(req)` (Flow C steps 3a–3c, gate portion)**
1. If a cached decision exists for `(req.run(), req.call())` → return it (idempotency on retry).
2. Pre-checks: if profile gating disabled mutation, this should not have been reached — defensively
   return `DENIED` (fail-closed, conventions §5). 
3. Call `approvalHandler.decide(req)` on the calling thread with a timeout (`Duration`); if the
   handler does not respond within the timeout → `TIMED_OUT` (or auto-`DENIED` per config, default
   `TIMED_OUT`).
4. Cache and return the decision. The caller (`ToolRegistry.dispatch`) then records
   `AuditEvent(APPROVAL, decision)`; only on `APPROVED` does it proceed to the mutating
   `tool.invoke(...)` and record `AuditEvent(MUTATION)`.

**Where this sits in Flow C:** Planner flags steps `mutating`; for each mutating step the
`ToolRegistry`/`Orchestrator` calls `dryRun` (3a), builds an `ApprovalRequest` carrying the preview,
calls `request` (3b), audits the decision, and on `APPROVED` performs the mutation (3c). This module
guarantees the *gate*; the *ordering* invariant is verified by a contract test (AC4) and asserted in
the `ToolRegistry` spec.

## Configuration keys

Keys read by this module (prefix `eoiagent.`; registered in `ConfigProvider` defaults per
conventions §9.4). Defaults shown per profile.

| Key | Type | OFFLINE | ON_PREM_HOSTED | CLOUD | Meaning |
|-----|------|---------|----------------|-------|---------|
| `eoiagent.approval.required` | boolean | `true` | `true` | `true` | Master switch (matches 02-domain-model + 03-profiles). When `true`, all mutating tools gate. Must never be defaultable to `false` for a mutating capability without an ADR. |
| `eoiagent.approval.timeout` | Duration | `PT0S` (no auto-resolve; headless → deny) | `PT5M` | `PT5M` | How long `request` blocks before timing out. |
| `eoiagent.approval.onTimeout` | enum `TIMED_OUT`/`DENIED` | `DENIED` | `TIMED_OUT` | `TIMED_OUT` | Disposition on timeout (OFFLINE fails closed). |
| `eoiagent.approval.headless` | boolean | `true` | `false` | `false` | Headless/OFFLINE: require an explicit operator token or auto-deny (Flow C note). |
| `eoiagent.policy.grants` | resource ref | (built-in table) | (built-in table) | (built-in table) | Optional override path to a stricter grant table; may only *remove* grants. |

Fail-closed rule (conventions §5): in `OFFLINE`/headless with no handler resolution, the gate
returns `DENIED`, never silently proceeds.

## Error handling

Typed exceptions from conventions §5 (rooted at `EoiAgentException` in core):

- **`PolicyViolation`** — thrown by `PolicyEngine.check` when role/capability/profile disallows the
  tool. Also the exception used for the offline fail-closed rule (a disabled-by-profile feature
  throws `PolicyViolation`, never silently falls back).
- **`ApprovalDeniedException`** — thrown by the *caller* (`ToolRegistry`) when it must convert a
  `DENIED`/`TIMED_OUT` decision into a hard failure for a step that cannot proceed. This module
  *returns* the decision; it throws `ApprovalDeniedException` only from convenience helpers that
  callers opt into (e.g. `requireApproved(req)`).
- **`ConfigException`** — bad/contradictory config (e.g. a grant override that tries to *add* a
  grant, `approval.required=false` for a mutating capability).

Failure modes: handler throws → wrap and audit `ERROR`, treat as `DENIED` (fail-closed); handler
blocks past timeout → `onTimeout` disposition; dry-run provider throws → return
`DryRunResult(false, ...)` and let approval proceed with no preview (the human still decides).
Never swallow silently — record `AuditEvent(ERROR, ...)` and rethrow/convert (conventions §5, §7).

## Acceptance criteria

- **AC1** `RoleBasedPolicyEngine.allows` returns the exact grant table above for every
  `Role × Capability` pair, intersected with the profile matrix; e.g. `allows(USER, RUN_PIPELINE, *)`
  is `false`, `allows(ADMIN, RUN_PIPELINE, OFFLINE)` is `true`.
- **AC2** `PolicyEngine.check` throws `PolicyViolation` when (a) `requiredRole` outranks `ctx.role`,
  or (b) capability not granted, or (c) profile disables the capability; returns normally otherwise.
- **AC3** `CallbackApprovalGate.request` blocks until the `ApprovalHandler` returns, and returns the
  handler's `ApprovalDecision` unchanged for `APPROVED`/`DENIED`.
- **AC4** (C4 invariant) Given a mutating `ToolCall`, the gate yields no path to mutation on a
  non-`APPROVED` decision: the contract test drives the gate + a recording `AuditSink` fake and
  asserts that whenever a `MUTATION` audit event exists, an `APPROVED` `APPROVAL` event with the
  same `(run, call)` precedes it; and that a `DENIED`/`TIMED_OUT` decision yields zero `MUTATION`
  events.
- **AC5** On handler timeout, `request` returns the disposition from `eoiagent.approval.onTimeout`
  (default `TIMED_OUT`; `DENIED` in OFFLINE).
- **AC6** Re-requesting the same `(run, call)` returns the cached decision without re-invoking the
  handler (idempotency on retry).
- **AC7** `dryRun` returns `supported=false` with a reason when no `DryRunProvider` is registered,
  and never mutates state; returns the provider's preview when one is registered.
- **AC8** In OFFLINE/headless with no resolvable handler, `request` returns `DENIED` (fail-closed),
  not `APPROVED` and not a network call.

## Test plan

All default tests run with **no network and no live LLM** (this module has no LLM dependency at
all). Use hand-written fakes (`RecordingAuditSink`, `StubApprovalHandler`) over Mockito where
practical (conventions §8).

- **Unit** — `RoleBasedPolicyEngineTest` (AC1, AC2), `CallbackApprovalGateTest` (AC3, AC5, AC6,
  AC7, AC8). Drive `request` on a worker thread with a latch-based `StubApprovalHandler` to test
  blocking and timeout deterministically.
- **Contract** — `ApprovalGateContractTest` and `PolicyEngineContractTest`: abstract JUnit 5 test
  classes (one `@Test` per AC) that any future adapter must extend and pass (conventions §8).
- **Invariant/integration** — `ApprovalAuditOrderingTest` (AC4): wire `CallbackApprovalGate` +
  `RecordingAuditSink` through a minimal fake `ToolRegistry.dispatch` and assert the C4 ordering
  invariant over the recorded `AuditEvent` stream.

Run: `mvn -pl eoiagent-safety -am test`

## Dependencies on other modules

- **`eoiagent-core`** — all domain types and the typed-exception hierarchy.
- **`eoiagent-tool`** (Component 3) — *consumer*: `ToolRegistry.dispatch` calls `dryRun` + `request`
  and emits the `APPROVAL`/`MUTATION` audit events around them (Flow B/C). This module does not
  depend back on `eoiagent-tool`.
- **`eoiagent-observability`** (Component 9) — the caller emits `AuditEvent`s via `AuditSink`;
  contract tests use a fake `AuditSink`. No compile dependency required by this module's ports.
- **`eoiagent-config`** (Component 11) — reads `eoiagent.approval.*` and the profile/capability
  matrix via `ConfigProvider`.

## Out of scope / deferred

- The `Guardrail` port and adapters — see [guardrails.md](guardrails.md) (same component, separate
  spec).
- Approval *transport/UI* (queue, web prompt) — the host implements `ApprovalHandler`; we ship only
  the callback gate. (Phase 2 host integration.)
- Multi-approver / quorum approval, approval delegation, time-boxed standing approvals — **deferred,
  Phase 4 hardening.**
- Persisting approval decisions across restart — relies on `CheckpointStore`/audit replay;
  **deferred to Phase 3** (Flow E breakpoints).
- Data-classification / column-level policy beyond `Capability` granularity — **deferred, Phase 4.**

## Related ADRs & flows

- [ADR-0008 — Mutating actions: approval gate & dry-run](../adr/0008-mutating-actions-approval-gate-dryrun.md)
- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- [ADR-0009 — Audit trail & observability](../adr/0009-audit-trail-and-observability.md)
- [04-sequence-flows.md](../architecture/04-sequence-flows.md) — Flow C, Cross-cutting invariants #2, #4
- [03-deployment-profiles.md](../architecture/03-deployment-profiles.md) — capability matrix
- [01-component-model.md](../architecture/01-component-model.md) — Component 7
