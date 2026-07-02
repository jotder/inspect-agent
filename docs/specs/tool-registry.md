---
type: spec
title: "Tool Registry — Spec"
description: "Expose the host's Java API as agent tools and call external tools via MCP, with role/profile visibility filtering and approval+audit-enforced dispatch."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["tool-registry"]
---
# Tool Registry — Spec

> Expose the host's Java API as agent tools and call external tools via MCP, with role/profile
> visibility filtering and approval+audit-enforced dispatch.
> Component 3 in [01-component-model.md](../architecture/01-component-model.md).
> Port(s): `Tool`, `ToolRegistry`.

## Purpose

Tools are how the agent *acts* on the host (per constraint C6: reach the host via its Java API).
This module:

- Wraps host `@Tool`-annotated Java methods (`JavaApiTool`) and external MCP tools
  (`McpToolAdapter`) behind one `Tool` port.
- Classifies every tool **read-only vs mutating** and assigns a **`requiredRole`** and
  **`Capability`**.
- Exposes only the tools a given `AgentContext` may see (`visibleTo`) — filtered by role,
  profile capability matrix, and the read-only flag.
- Centralizes `dispatch`, which enforces `PolicyEngine` (RBAC + profile), routes mutating tools
  through the `ApprovalGate`, executes the tool, and emits `AuditEvent`s — the choke point for
  [Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1)
  and [Flow C](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2).

Phase 1 ships **read-only Java-API tools** only. Mutating tools and MCP arrive in Phase 2.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-3--tools---ports-tool-toolregistry), copied verbatim:

```java
package com.eoiagent.tool;

public interface Tool {
    ToolSpec spec();                  // name, description, JSON schema, mutating?, requiredRole
    ToolResult invoke(ToolCall call); // validated args in, structured result out
}
public interface ToolRegistry {
    void register(Tool tool);
    List<ToolSpec> visibleTo(AgentContext ctx);  // filtered by role + profile + read-only flag
    ToolResult dispatch(ToolCall call, AgentContext ctx); // enforces approval + audit
}
```

Contract notes:

- **`Tool.spec()`** — Returns a non-null, immutable `ToolSpec` (stable across calls). `name`
  unique within a registry; `jsonSchema` is a valid JSON Schema for `arguments`; `mutating`,
  `requiredRole`, and `capability` are mandatory and honest (a tool that changes state MUST set
  `mutating=true`). Cheap / side-effect-free.
- **`Tool.invoke(ToolCall)`** — Executes the tool with already-validated arguments. Returns a
  non-null `ToolResult`. For *expected* failures (bad args caught downstream, not-found) return
  `ToolResult{ok=false, error=...}`; **throw only for unexpected faults** (per
  [conventions §5](../conventions.md#5-error-handling)) — wrapped as `ToolExecutionException`.
  `invoke` MUST NOT perform its own policy/approval checks — that is `dispatch`'s job; callers
  must go through `dispatch`, not `invoke` directly. Single-call thread-safety is per-adapter.
- **`ToolRegistry.register(Tool)`** — Adds a tool; rejects a duplicate `name` with
  `ConfigException`. Not required to be thread-safe at runtime; registration happens at wiring.
- **`ToolRegistry.visibleTo(AgentContext)`** — Returns the `ToolSpec`s the context may use:
  excludes any tool whose `requiredRole` exceeds `ctx.role()`, whose `capability` the
  `PolicyEngine` disallows for `(role, profile)`, or (Phase-gated) whose `mutating`/MCP nature the
  profile disables. Never returns null; pure (no side effects, no audit). Satisfies invariant 4.
- **`ToolRegistry.dispatch(ToolCall, AgentContext)`** — The enforced execution path. Resolves the
  tool by `call.toolName()`, validates `arguments` against `jsonSchema`, runs
  `PolicyEngine.check(ctx, spec)` (throws `PolicyViolation`), and:
  - read-only → invoke directly;
  - mutating → `ApprovalGate.dryRun` then `ApprovalGate.request`; only on `APPROVED` invoke.
  Always records audit (`TOOL_CALL`, and `APPROVAL`/`MUTATION` for mutating tools). Returns the
  `ToolResult`. Threading: callable per session; a single `AgentSession` dispatches serially.

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `JavaApiTool` | `dev.langchain4j:langchain4j` core (BOM, AI Services / `@Tool`) | **Phase 1 (read-only)** → Phase 2 (mutating) | wraps a host `@Tool`-annotated method; derives JSON schema from the method signature |
| `DefaultToolRegistry` | (ours) | **Phase 1** | the enforced `ToolRegistry`; wires `PolicyEngine` + `ApprovalGate` + `AuditSink` |
| `McpToolAdapter` | `dev.langchain4j:langchain4j-mcp` (BOM) | **Phase 2** | MCP client; `MCP_TOOLS` profile-gated (OFFLINE = local stdio only) |
| `StubTool` / `RecordingApprovalGate` (test-fixtures) | (ours) | Phase 1 | deterministic tools + approval stub for tests |

`JavaApiTool` uses LangChain4j **AI Services** tool support to introspect `@Tool` methods and
generate the JSON schema; the *classification* (`mutating`, `requiredRole`, `capability`) is
supplied by the host at registration (an `@EoiTool`-style annotation or a builder) since LC4j's
`@Tool` does not carry it.

## Maven coordinates

- **This module:** `com.eoiagent:eoiagent-tool` (version `0.1.0-SNAPSHOT`).
- **Ports + domain types:** `com.eoiagent:eoiagent-core`.
- **Third-party (versions via `eoiagent-bom` → `langchain4j-bom:1.16.3`, never hardcoded):**
  `langchain4j` (core: AI Services / `@Tool`), `langchain4j-mcp` (Phase 2; appears **only** in
  `McpToolAdapter`, behind the `MCP_TOOLS` feature flag).

## Inputs / Outputs

Consumed (from [02-domain-model.md](../architecture/02-domain-model.md#tools)):
`ToolCall(String toolName, Map<String,Object> arguments, RunId run)`, `AgentContext`,
`ToolSpec(... boolean mutating, Role requiredRole, Capability capability)`.

Produced:
`ToolResult(boolean ok, Object value, String error, Map<String,Object> meta)`,
`List<ToolSpec>` (from `visibleTo`).

Cross-module types it drives: `Capability` (e.g. `READ_METADATA`, `READ_SCHEMA`, `READ_DOCS`,
`RUN_SQL_READONLY` for read-only; `RUN_PIPELINE`, `EDIT_CONFIG`, `WRITE_DATASTORE`,
`TRIGGER_JOB` for mutating), `ApprovalRequest`, `DryRunResult`, `ApprovalDecision`, `AuditEvent`.

## Behavior / algorithm

`DefaultToolRegistry.dispatch(call, ctx)` — implements
[Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1)
(read-only) and [Flow C step 3](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2)
(mutating):

1. Resolve `tool = byName(call.toolName())`; if missing → `ToolResult{ok=false, error="unknown tool"}`.
2. **Visibility/role+profile:** `PolicyEngine.check(ctx, tool.spec())` → throws `PolicyViolation`
   if `requiredRole` exceeds `ctx.role()` or the `Capability` is disabled for `ctx.profile()`.
3. **Validate args** against `spec().jsonSchema()`; on failure return
   `ToolResult{ok=false, error="invalid arguments: ..."}` (expected failure, not an exception).
4. **Read-only path** (`spec().mutating() == false`):
   a. `result = tool.invoke(call)`;
   b. `AuditSink.record(AuditEvent(TOOL_CALL, ...))`;
   c. return `result`.
5. **Mutating path** (`spec().mutating() == true`, Phase 2; profile must allow
   `MUTATING_ACTIONS`):
   a. `DryRunResult preview = approvalGate.dryRun(call)`;
   b. `decision = approvalGate.request(ApprovalRequest(run, call, humanSummary, preview))`
      (BLOCKS for a human);
   c. `AuditSink.record(AuditEvent(APPROVAL, decision...))`;
   d. if `APPROVED`: `result = tool.invoke(call)`;
      `AuditSink.record(AuditEvent(MUTATION, ...))`; return `result`;
   e. else (`DENIED`/`TIMED_OUT`): return `ToolResult{ok=false, error="approval denied"}` and let
      the orchestrator revise the plan (no mutation, no `MUTATION` audit).

Invariant enforced here: **no mutating `ToolResult` without a preceding `APPROVED` audit event**
(C4 / invariant 2 in [04-sequence-flows.md](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)).

`visibleTo(ctx)`: filter all registered specs by (a) `requiredRole <= ctx.role()`,
(b) `PolicyEngine.allows(ctx.role(), spec.capability(), ctx.profile())`, (c) Phase/profile gates
(mutating tools hidden unless `MUTATING_ACTIONS`; MCP tools hidden unless `MCP_TOOLS`). The
runtime passes the result into `LlmGateway.chat` as the visible tool set (Flow A step 3).

## Configuration keys

Read via `ConfigProvider`:

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) |
|-----|------|--------------------------------------------|
| `eoiagent.approval.required` | Boolean | `true` (all profiles) |
| `eoiagent.tools.mcp.enabled` | Boolean | `false` / `true` / `true` (only if `MCP_TOOLS`) |
| `eoiagent.tools.mcp.transport` | String | `stdio` (OFFLINE allows local stdio only) / `stdio`\|`http` |
| `eoiagent.tools.mcp.servers` | String | (comma-separated server configs; Phase 2) |
| `eoiagent.tools.argValidation` | Boolean | `true` |

`MUTATING_ACTIONS`, `MCP_TOOLS` come from the capability matrix
([03-deployment-profiles.md](../architecture/03-deployment-profiles.md#capability-matrix)); the
registry never enables a mutating or MCP tool by classpath presence alone.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- `PolicyViolation` — role/capability/profile denies the tool (thrown by `PolicyEngine.check`
  inside `dispatch`); also thrown when a mutating tool is dispatched while `MUTATING_ACTIONS` is
  disabled (offline fail-closed) and when MCP is invoked while `MCP_TOOLS` is disabled.
- `ApprovalDeniedException` *or* `ToolResult{ok=false}` for a denied/timed-out approval — choose
  per caller contract; the registry returns `ToolResult{ok=false, error="approval denied"}` and
  records the `APPROVAL` audit event (orchestrator decides whether to escalate).
- `ToolExecutionException` — an *unexpected* fault inside `Tool.invoke`; expected failures
  (bad args, not-found) are `ToolResult{ok=false, error=...}`, never exceptions.
- `ConfigException` — duplicate tool name at `register`, or invalid MCP config.
- Never swallow: every dispatch path emits a `TOOL_CALL` (and `APPROVAL`/`MUTATION`/`ERROR` as
  applicable) `AuditEvent`.

## Acceptance criteria

1. **AC1** `register` of two tools with the same `name` throws `ConfigException`.
2. **AC2** `visibleTo(ctx)` never returns a tool whose `requiredRole` exceeds `ctx.role()` nor one
   whose `capability` is disabled for `ctx.profile()` (invariant 4).
3. **AC3** Dispatching a read-only tool emits exactly one `TOOL_CALL` `AuditEvent` and returns the
   tool's `ToolResult`; no `ApprovalGate` interaction occurs.
4. **AC4** Dispatching a **mutating** tool calls `ApprovalGate.dryRun` then
   `ApprovalGate.request`; on `APPROVED` it invokes the tool and emits both `APPROVAL` and
   `MUTATION` audit events, in that order.
5. **AC5** On `DENIED`/`TIMED_OUT`, the mutating tool is **not** invoked, no `MUTATION` event is
   emitted, and an `APPROVAL` event records the decision (invariant 2 holds).
6. **AC6** Given `OFFLINE` profile, dispatching a mutating tool while `MUTATING_ACTIONS` for the
   role is denied throws `PolicyViolation` before any approval or invoke.
7. **AC7** Invalid arguments (schema mismatch) yield `ToolResult{ok=false, error=...}`, not an
   exception.
8. **AC8** A `JavaApiTool` wrapping a host `@Tool` method exposes a `ToolSpec` whose `jsonSchema`
   matches the method parameters and whose `mutating`/`requiredRole`/`capability` come from the
   host classification.
9. **AC9** Given `MCP_TOOLS` disabled (OFFLINE), `McpToolAdapter` tools are absent from
   `visibleTo` and dispatching one throws `PolicyViolation` (no network).

## Test plan

All default tests run with **no network and no live LLM**, using `StubTool` and a
`RecordingApprovalGate` (and `RecordingAuditSink`) to assert call order. JUnit 5 + AssertJ;
Mockito for `PolicyEngine`/`ApprovalGate` seams where a recording stub is impractical.

- **Unit** — `DefaultToolRegistryTest` (dispatch ordering for read-only vs mutating, audit
  emission, args validation, duplicate-name rejection), `JavaApiToolTest` (schema derivation +
  classification mapping).
- **Contract** — `ToolContractTest` (shared suite every `Tool` adapter passes: spec immutability,
  `ok=false` vs throw semantics), `ToolRegistryContractTest` (visibility + dispatch invariants).
- **Safety/invariants** — `ApprovalEnforcementTest` asserts invariant 2 (no mutation without
  `APPROVED`) and `VisibilityInvariantTest` asserts invariant 4.
- **Profile/offline** — `OfflineToolDenyTest` asserts AC6/AC9 (no network for MCP/mutating
  offline).
- **Integration (Phase 2, opt-in)** — `McpToolAdapterIT` against a local stdio MCP server.

Command: `mvn -pl eoiagent-tool -am test` (default, no network). Phase 2 MCP:
`mvn -pl eoiagent-tool -Pmcp-it verify`.

## Dependencies on other modules

- `eoiagent-core` — domain records (`ToolSpec`, `ToolCall`, `ToolResult`, `AgentContext`, `Role`,
  `Capability`, `ApprovalRequest`, `ApprovalDecision`, `DryRunResult`, `AuditEvent`) and the typed
  exception hierarchy.
- `eoiagent-safety` — `PolicyEngine` (RBAC + profile) and `ApprovalGate` (dry-run + request),
  injected into `DefaultToolRegistry`.
- `eoiagent-observability` — `AuditSink`, recorded by `dispatch` (`TOOL_CALL` / `APPROVAL` /
  `MUTATION` / `ERROR`).
- `eoiagent-config` — `ConfigProvider` for keys + `featureEnabled(MUTATING_ACTIONS / MCP_TOOLS)`.
- `eoiagent-runtime` — consumer: the Orchestrator calls `visibleTo` and `dispatch`.

## Out of scope / deferred

- Mutating Java-API tools and the full approval+dry-run path — **Phase 2** (`MUTATING_ACTIONS`).
- `McpToolAdapter` and remote MCP transport — **Phase 2** (`MCP_TOOLS`; remote needs network).
- Tool result caching / memoization and parallel tool fan-out — Phase 2+.
- Per-tool rate limiting and quota — Phase 4 hardening.
- Output guardrails on tool results (schema/PII) — Component 7, applied by the runtime.

## Related ADRs & flows

- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- [ADR-0003 — Foundation: LangChain4j BOM](../adr/0003-foundation-langchain4j-bom.md)
- [ADR-0010 — Isolate experimental deps](../adr/0010-isolate-experimental-deps.md)
- Flows: [B — ReAct read-only](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1),
  [C — plan→approve→act](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2),
  [D — supervisor + sub-agents](../architecture/04-sequence-flows.md#flow-d--supervisor--sub-agents-delegation-phase-2)
- [03 — Capability matrix](../architecture/03-deployment-profiles.md#capability-matrix)
