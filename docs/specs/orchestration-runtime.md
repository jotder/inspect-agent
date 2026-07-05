---
type: spec
title: "Agent Runtime / Orchestration — Spec"
description: "Drives a run (plan → act → observe → reflect) for the EOI Agent."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["orchestration-runtime"]
---
# Agent Runtime / Orchestration — Spec

> Drives a run (plan → act → observe → reflect) for the EOI Agent. Component 4 in [01-component-model.md](../architecture/01-component-model.md). Port(s): `Planner`, `Orchestrator`, `TaskManager` (this module), plus `CheckpointStore` from Component 8 (Persistence) consumed here for checkpoint/replay.

## Purpose

This module is the control plane of the Agent OS. Given a `Goal` and an `AgentContext`, it produces an `AgentRun` by orchestrating the model, tools, knowledge, memory, scratchpad, and safety components into one of several control-flow shapes: a bounded ReAct loop, a plan→approve→act sequence, a supervisor delegating to isolated sub-agents, or (Phase 3) a cyclical, checkpointed investigation graph with breakpoints and time-travel.

The orchestration framework choices (`langchain4j-agentic` for MVP, `org.bsc.langgraph4j` for Phase 3) are **experimental / single-maintainer** and are quarantined behind the `Orchestrator` port — no `langchain4j-agentic` or `org.bsc.langgraph4j` type may appear in `eoiagent-core` or in any non-adapter package (ADR-0010). Swapping orchestration engines must not change a single line of host or core code.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-4--agent-runtime--orchestration---ports-planner-orchestrator-taskmanager), package `com.eoiagent.runtime`. Copied verbatim:

```java
package com.eoiagent.runtime;

public interface Planner {
    Plan plan(Goal goal, AgentContext ctx);     // produce a multistep plan
    Plan revise(Plan plan, Observation obs);     // update plan as it learns
}
public interface Orchestrator {
    AgentRun run(Goal goal, AgentContext ctx);   // drives plan → act → observe → reflect
}
public interface TaskManager {                   // the write_todos equivalent
    TaskList create(Plan plan);
    void update(TaskId id, TaskStatus status, String note);
    TaskList current();
}
```

Checkpointing is consumed from Component 8, package `com.eoiagent.persistence` (used by `LangGraphOrchestrator`):

```java
public interface CheckpointStore {
    void save(RunId id, Checkpoint cp);
    Optional<Checkpoint> latest(RunId id);
    List<Checkpoint> history(RunId id);   // enables time-travel/replay
}
```

Contract notes:

- **`Planner.plan(goal, ctx)`** — Pre: `goal` and `ctx` non-null; `ctx.profile()` set. Post: returns a non-null `Plan` with ≥1 `PlanStep`; each `PlanStep.id` is unique within the plan and each `mutating` flag is correct (a step is `mutating=true` iff it dispatches a tool whose `ToolSpec.mutating()` is true). For a pure-QA goal the plan may be a single non-mutating step. Throws `ModelUnavailableException` if the planner LLM is unreachable. Threading: a single `Planner` instance is callable concurrently across sessions; it holds no per-run state.
- **`Planner.revise(plan, obs)`** — Pre: `plan` is a plan previously returned by this planner; `obs` non-null. Post: returns a new `Plan` (do not mutate the input). Used after a step fails, an approval is DENIED/TIMED_OUT, or an observation invalidates remaining steps. Must be idempotent-safe: revising with an empty/no-op `Observation` returns an equivalent plan.
- **`Orchestrator.run(goal, ctx)`** — Pre: non-null args. Post: returns a non-null `AgentRun` carrying a fresh `RunId`, the final `AgentAnswer`, the `TaskList`, and the accumulated `Citation`s. Never returns null and never returns an `AgentAnswer` with `kind=null`; on failure it returns `AgentAnswer(kind=ERROR, ...)` and emits an `ERROR` `AuditEvent` rather than throwing past the host boundary — except for unrecoverable config faults (`ConfigException`) which propagate. Honors `ctx.profile()`: in `OFFLINE` it must not perform any network call (fail-closed). Threading: runs of *different* sessions may execute in parallel; the runtime must not share mutable per-run state across `run` invocations.
- **`TaskManager.create(plan)`** — Pre: non-null `Plan`. Post: returns a `TaskList` with one `Task` per `PlanStep`, each `status=PENDING`, ids preserved from the plan. The list is the host-visible source of truth for progress (Flow C / D).
- **`TaskManager.update(id, status, note)`** — Pre: `id` exists in the current list; `status` non-null; `note` may be empty (never null). Post: the task transitions; illegal transitions (e.g. `DONE`→`IN_PROGRESS`) throw `IllegalStateException`. A single `TaskManager` is scoped to one run and is **not** thread-safe across concurrent runs.
- **`TaskManager.current()`** — Returns a snapshot (copy) of the current `TaskList`; callers must not mutate it.
- **`CheckpointStore.save/latest/history`** — `save` is append-only (never overwrites prior checkpoints for the same `RunId`); `latest` returns `Optional.empty()` for an unknown `RunId`; `history` returns checkpoints oldest→newest enabling replay and time-travel.

`AgentRun`, `Observation`, and `Checkpoint` are runtime/persistence types not in the core domain table; define them in `eoiagent-runtime` / `eoiagent-persistence` respectively (see Inputs / Outputs).

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `ReActOrchestrator` | `dev.langchain4j:langchain4j` (tools/AI-Services) + our loop | MVP | Simple bounded reason+act loop (Flow B). Default/fallback path; no external orchestration engine. |
| `AgenticOrchestrator` | `dev.langchain4j:langchain4j-agentic` (experimental, `1.16.x-betaNN`) | MVP | Sequential / parallel / conditional / loop / supervisor workflows (Flows C, D). Experimental — adapter-only (ADR-0010). |
| `LangGraphOrchestrator` | `org.bsc.langgraph4j:langgraph4j-core` + `langgraph4j-langchain4j` (1.8.19) | Phase 3 | Cyclical graph, checkpoint/replay, breakpoints, time-travel, HITL (Flow E). Backed by `PostgresCheckpointStore`. |
| `ReflectionOrchestrator` | (ours) | T-500 | Evaluator-critic refinement (Flow F, §6): draft → critique → revise, our own bounded loop, ports only. For generation goals (`SQL_GEN`, `PIPELINE_AUTHOR`). |
| `DefaultPlanner` | `dev.langchain4j:langchain4j` (structured output via AI Services) | MVP→Phase 2 | LLM-driven plan generation; emits typed `Plan`. Single-step pass-through for QA goals. |
| `InMemoryTaskManager` | (ours) | MVP | Per-run, in-memory `TaskList`; host reads via `TaskManager.current()`. |

Orchestrator selection is config-driven (`eoiagent.runtime.orchestrator`), defaulting per goal kind (see Behavior §1). `AgenticOrchestrator` and `LangGraphOrchestrator` are constructed only when their feature/profile allows; otherwise the runtime falls back to `ReActOrchestrator`.

## Maven coordinates

This module: `com.eoiagent:eoiagent-runtime` (version `0.1.0-SNAPSHOT`). Depends on `com.eoiagent:eoiagent-core` (ports + domain types) and `com.eoiagent:eoiagent-persistence` (for `CheckpointStore`).

Third-party (all versions via BOM per [02-domain-model.md](../architecture/02-domain-model.md#maven-coordinates) — never hardcode versions in this module's `pom.xml`):

- `dev.langchain4j:langchain4j` — AI Services, tools, ReAct loop primitives (version from `langchain4j-bom:1.16.3`).
- `dev.langchain4j:langchain4j-agentic` — experimental workflow/supervisor engine; `1.16.x-betaNN` suffix resolved at build time and pinned in `eoiagent-bom`. **Adapter package only.**
- `org.bsc.langgraph4j:langgraph4j-core`, `org.bsc.langgraph4j:langgraph4j-langchain4j` — pinned `1.8.19` in `eoiagent-bom`; Phase 3, **adapter package only**. (Postgres checkpoint saver `org.bsc.langgraph4j:langgraph4j-checkpoint-postgres:1.8.19` is pulled in by `eoiagent-persistence`, not here.)

This module does not consume any model/vectorstore artifact directly — it reaches those capabilities only through their ports (`LlmGateway`, `Retriever`, `ToolRegistry`, `ChatMemory`, `Scratchpad`, `ApprovalGate`, `PolicyEngine`, `AuditSink`).

## Inputs / Outputs

Consumes (from `com.eoiagent.core`): `Goal` + `GoalKind`, `AgentContext` (carrying `SessionId`, `UserId`, `Role`, `DeploymentProfile`, `PageContext`), `Plan` + `PlanStep`, `TaskList` + `Task` + `TaskStatus`, `TaskId`, `RunId`, `ToolSpec`, `ToolCall`, `ToolResult`, `ApprovalRequest`/`ApprovalDecision`/`DryRunResult`, `Citation`.

Produces: `AgentAnswer` (with `AnswerKind` TEXT / INLINE_ARTIFACT / NAVIGATION / CLARIFICATION / ERROR, plus `NavigationIntent` / `InlineArtifact` / `Citation`s as appropriate), `TaskList` updates, `AuditEvent`s (kinds MODEL_CALL, TOOL_CALL, DECISION, APPROVAL, MUTATION, RETRIEVAL, ERROR).

New types defined in this module (not in the core domain table):

```java
package com.eoiagent.runtime;
record AgentRun(RunId id, AgentAnswer answer, TaskList tasks, List<Citation> citations, int steps) {}
record Observation(TaskId step, boolean ok, String summary, ToolResult result) {}  // feeds Planner.revise
```

New type in `com.eoiagent.persistence` (consumed here):

```java
record Checkpoint(RunId run, String nodeId, byte[] state, Instant at, int seq) {}  // opaque serialized run state
```

## Behavior / algorithm

### 1. Orchestrator selection (entry to every `run`)

1. Resolve adapter from `eoiagent.runtime.orchestrator` (`auto` default). For `auto`:
   - `GoalKind.QA` → `ReActOrchestrator`.
   - `ANALYSIS`, `SQL_GEN`, `PIPELINE_AUTHOR`, `OPERATIONAL_ACTION` → `AgenticOrchestrator` (sequential/supervisor as needed); falls back to `ReActOrchestrator` if `langchain4j-agentic` adapter unavailable.
   - `INVESTIGATION` → `LangGraphOrchestrator` **iff** `featureEnabled(LANGGRAPH_CHECKPOINTING)` (Phase 3); else `AgenticOrchestrator`.
2. Allocate a fresh `RunId`. Open per-run scopes: a `ChatMemory` view for `ctx.session()`, a `Scratchpad` scope keyed by `RunId`, a `TaskManager` instance.
3. Run the selected adapter (§2–§5). Wrap the whole body so any uncaught fault → `AgentAnswer(ERROR)` + `AuditSink.record(ERROR, ...)`.

### 2. ReAct loop — Flow B ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1))

1. Initialize history from `ChatMemory.messages()` for the session, append the goal.
2. Loop, bounded by `eoiagent.runtime.maxSteps`:
   1. `thought, action = LlmGateway.chat(history + ToolRegistry.visibleTo(ctx))`; record `MODEL_CALL`.
   2. If the model returns a final answer → assemble `AgentAnswer` (TEXT / INLINE_ARTIFACT / NAVIGATION per Flow A heuristic), return.
   3. If a `ToolCall`: `ToolRegistry.dispatch(call, ctx)` (which runs `PolicyEngine.check`, approval if mutating, and `AuditSink.record(TOOL_CALL)`); append the `ToolResult` to history.
   4. **Context offloading:** if a `ToolResult.value` (or any intermediate) exceeds `eoiagent.runtime.offloadThresholdBytes`, write it to the `Scratchpad` and append only the returned handle + a short synopsis to history (see [scratchpad.md](scratchpad.md)).
3. If `maxSteps` is exhausted → return `AgentAnswer(CLARIFICATION or TEXT)` summarizing progress; record `DECISION`. Never loop unbounded.

### 3. Plan → approve → act — Flow C ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2))

This is the most safety-critical path; no mutation occurs without a recorded `APPROVED`.

1. `Plan plan = Planner.plan(goal, ctx)` (steps flagged `mutating`).
2. `TaskList tasks = TaskManager.create(plan)` (host-visible).
3. For each `PlanStep` in order:
   - **read-only step** → execute as a bounded ReAct sub-loop (§2); `TaskManager.update(id, IN_PROGRESS→DONE)`.
   - **mutating step**:
     1. `TaskManager.update(id, NEEDS_APPROVAL, ...)`.
     2. `DryRunResult preview = ApprovalGate.dryRun(toolCall)`.
     3. `ApprovalDecision d = ApprovalGate.request(new ApprovalRequest(run, call, humanSummary, preview))` — **blocks** for the human; `AuditSink.record(APPROVAL, d)`.
     4. If `APPROVED`: `ToolRegistry.dispatch(call, ctx)` performs the mutation; `AuditSink.record(MUTATION)`; `TaskManager.update(id, DONE)`.
     5. If `DENIED` / `TIMED_OUT`: `TaskManager.update(id, BLOCKED)`; `Planner.revise(plan, Observation(step, false, ...))`; continue with the revised plan (or conclude if no path remains).
4. Return `AgentAnswer` summarizing actions taken (with citations). Invariant: no mutating `ToolResult` exists without a preceding `APPROVED` `AuditEvent` ([04 invariant 2](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)).

### 4. Supervisor + sub-agents — Flow D ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-d--supervisor--sub-agents-delegation-phase-2))

1. The `AgenticOrchestrator` runs an LLM **supervisor** that picks the next worker by sub-goal. Workers:
   - `AnalysisAgent` — schema/metadata analysis; read-only tool subset (`READ_METADATA`, `READ_SCHEMA`, `READ_DOCS`).
   - `SqlAgent` — generate + read-only-validate SQL (`GENERATE_SQL`, `RUN_SQL_READONLY`).
   - `PipelineAgent` — author/explain pipeline specs; any mutation routes through Flow C.
2. Each worker is an isolated nested orchestration: its **own** `AgentContext` (same session/user/role, narrowed tool subset), its **own** `Scratchpad` scope (`runId/worker/...`), and its **own** bounded step budget. Workers cannot see each other's scratchpad or memory.
3. The supervisor aggregates worker results into one final `AgentAnswer`. Each worker invocation and each delegation `DECISION` is audited.
4. Implemented on the `langchain4j-agentic` supervisor/planner primitives; the worker boundary (isolated context + tool subset) is enforced in our adapter, not assumed from the library.

### 5. Investigation + checkpointing — Flow E ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-e--long-running-issue-investigation-with-checkpointing-phase-3))

1. `LangGraphOrchestrator` builds a cyclical graph: `gatherSignals → hypothesize → testHypothesis → (loop back | escalate | conclude)`. Tools: events/alerts/incidents/cases Java-API tools + logs/config retrieval.
2. After **each node** transition: `CheckpointStore.save(runId, checkpoint)` (append-only) — the run survives JVM restart.
3. **Breakpoint** before any mutating or `escalate` node → route to `ApprovalGate` (Flow C semantics) before proceeding (HITL).
4. **Resume after restart:** `CheckpointStore.latest(runId)` → rehydrate graph state → continue from the saved node.
5. **Time-travel / replay:** `CheckpointStore.history(runId)` → inspect or re-run from any prior checkpoint; replay must be deterministic given the same recorded tool/model responses.
6. Concludes with `AgentAnswer` = root-cause hypothesis + evidence citations + recommended/taken actions.

### 6. Reflection (evaluator-critic) loop — Flow F (T-500)

The "reflect" phase of the control loop made explicit for the agent's signature generation goals, and
the evaluator-critic refinement piece named in the original design. `ReflectionOrchestrator` is our
own bounded loop (no external engine), reaching the model only through `LlmGateway`:

1. **Draft** — one model call produces a first answer for the goal; audit `MODEL_CALL` + `DECISION`
   (`reflection: drafted`).
2. Loop:
   1. **Critique** — a model call reviews the current draft (strict-reviewer prompt); it replies with
      the leading verdict `APPROVED` or with specific revisions. Audit `MODEL_CALL`.
   2. If **approved** → `DECISION` (`reflection round N: approved`); return the current draft.
   3. If the revision budget (`eoiagent.runtime.reflection.maxRevisions`) is spent → `DECISION`
      (`reflection: max revisions (N) reached`); return the latest draft (graceful, never an error).
   4. Else **revise** — a model call rewrites the draft against the critique; audit `MODEL_CALL` +
      `DECISION` (`reflection round N: revising`); loop.

Bounded by `maxRevisions` (never revises unbounded, mirroring §2.3). Offline fail-closed (AC11): the
adapter issues no network call itself. Orchestrator selection (routing generation goals here) is a
wiring concern and stays out of the adapter; it composes with the other orchestrators through the
`Orchestrator` port.

## Configuration keys

Keys this module reads (prefix `eoiagent.`, per [02-domain-model.md](../architecture/02-domain-model.md#config-key-namespace)):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) | Meaning |
|-----|------|--------------------------------------------|---------|
| `eoiagent.runtime.orchestrator` | String | `auto` / `auto` / `auto` | `auto` \| `react` \| `agentic` \| `langgraph` |
| `eoiagent.runtime.maxSteps` | int | `12` / `16` / `16` | hard upper bound on ReAct/worker iterations |
| `eoiagent.runtime.offloadThresholdBytes` | int | `8192` / `8192` / `8192` | results bigger than this go to the `Scratchpad` |
| `eoiagent.runtime.supervisor.maxWorkers` | int | `3` / `4` / `4` | max sub-agent delegations per run |
| `eoiagent.runtime.checkpoint.everyNode` | boolean | `true` / `true` / `true` | Phase 3; save a checkpoint after each graph node |
| `eoiagent.runtime.reflection.maxRevisions` | int | `2` / `2` / `2` | T-500; hard upper bound on evaluator-critic revise rounds |
| `eoiagent.approval.required` | boolean | `true` / `true` / `true` | gates every mutating step (read from Safety config) |

Feature gates consumed via `ConfigProvider.featureEnabled(...)`: `LANGGRAPH_CHECKPOINTING` (selects `LangGraphOrchestrator`), `MUTATING_ACTIONS` (allows Flow C mutating steps). In `OFFLINE`, no orchestrator may issue a network call; any worker/tool that would must check `featureEnabled(HOSTED_MODELS)` and refuse.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling), all rooted at `EoiAgentException` (in core):

- `ModelUnavailableException` — planner or loop LLM unreachable. The `Orchestrator` converts it to `AgentAnswer(ERROR, ...)` at the run boundary after recording an `ERROR` `AuditEvent`; `Planner.plan` may rethrow.
- `PolicyViolation` — a step requires a capability the role/profile forbids; surfaces from `PolicyEngine.check` inside `dispatch`. **Offline fail-closed:** a step needing the network in `OFFLINE` throws `PolicyViolation` (never a silent fallback).
- `ApprovalDeniedException` — distinguishes an explicit DENY from a TIMED_OUT where the flow must abort rather than revise.
- `ToolExecutionException` — unexpected tool fault (vs. expected failures, which arrive as `ToolResult(ok=false)` and feed `Planner.revise`).
- `ConfigException` — invalid orchestrator selection or missing required key; propagates (not converted to ERROR answer).

Failure modes: `maxSteps` exhaustion → graceful CLARIFICATION/summary, never an exception; checkpoint save failure (Phase 3) → record `ERROR`, abort the run rather than continue un-checkpointed; revised-plan exhaustion → conclude with what was accomplished. Never swallow an exception silently — always `AuditSink.record(ERROR, ...)` then rethrow or convert.

## Acceptance criteria

- **AC1** — `ReActOrchestrator.run` with a stub `LlmGateway` that emits one read-only `ToolCall` then a final answer returns `AgentAnswer(TEXT)` and emits exactly the expected `MODEL_CALL`/`TOOL_CALL`/`DECISION` `AuditEvent`s (Flow B).
- **AC2** — The ReAct loop terminates at `eoiagent.runtime.maxSteps` and returns a non-error `AgentAnswer` (CLARIFICATION or TEXT) without throwing.
- **AC3** — A `ToolResult.value` larger than `offloadThresholdBytes` is written to the `Scratchpad` and the model history contains the handle, not the raw payload.
- **AC4** — In Flow C, a mutating step with a stub `ApprovalGate` returning `DENIED` results in the task `BLOCKED`, a `Planner.revise` call, and **no** `MUTATION` `AuditEvent`.
- **AC5** — In Flow C, an `APPROVED` decision produces a `MUTATION` `AuditEvent` that is strictly preceded by an `APPROVED` `APPROVAL` `AuditEvent` for the same `ToolCall` (invariant 2).
- **AC6** — `Planner.plan` flags a step `mutating=true` iff its tool's `ToolSpec.mutating()` is true; verified against a registry containing both kinds.
- **AC7** — In Flow D, each worker receives an `AgentContext` with a strictly narrowed tool subset and a distinct `Scratchpad` scope; a worker cannot read another worker's scratchpad keys.
- **AC8** — Supervisor delegation never exceeds `eoiagent.runtime.supervisor.maxWorkers`.
- **AC9** (Phase 3) — `LangGraphOrchestrator` saves a `Checkpoint` after each node; killing and recreating the orchestrator then calling `run` resumes from `CheckpointStore.latest(runId)` rather than restarting.
- **AC10** (Phase 3) — A breakpoint before a mutating/escalate node invokes `ApprovalGate.request` before the node executes; `CheckpointStore.history(runId)` returns checkpoints oldest→newest enabling replay.
- **AC11** — With `profile=OFFLINE`, no orchestrator path performs a network call (asserted by the network-deny harness); an orchestrator configured to a network-only worker throws `PolicyViolation`.
- **AC12** — `Orchestrator.run` never returns null nor an `AgentAnswer` with `kind=null`; an injected runtime fault yields `AnswerKind.ERROR` plus an `ERROR` `AuditEvent`.
- **AC13** (T-500) — `ReflectionOrchestrator` returns the draft unchanged when the critic replies `APPROVED` on the first pass; revises and returns the improved draft when the critic rejects then approves; and when `maxRevisions` is spent without approval it returns the latest draft (never `ERROR`), having performed at most `maxRevisions` revise calls. Every draft/critique/revise emits a `MODEL_CALL`.

## Test plan

All default tests run with **NO network and NO live LLM**, using a deterministic stub `LlmGateway` (scripted thoughts/tool-calls/final-answers) and in-memory adapters for every other port. Framework: JUnit 5 + AssertJ; Mockito only where a stub is impractical.

- **Unit** — `ReActOrchestratorTest` (AC1–AC3, AC12), `DefaultPlannerTest` (AC6), `InMemoryTaskManagerTest` (status transitions, illegal-transition rejection), `AgenticOrchestratorTest` (Flow C: AC4–AC5; Flow D: AC7–AC8) driving `langchain4j-agentic` with the stub gateway.
- **Contract** — `OrchestratorContractTest` (a shared suite every `Orchestrator` adapter must pass: returns non-null answer, audits model calls, respects `maxSteps`, fail-closed in OFFLINE — AC11/AC12) and `TaskManagerContractTest`.
- **Phase 3** — `LangGraphOrchestratorTest` + `CheckpointResumeTest` (AC9–AC10) using `InMemoryCheckpointStore` and recorded tool/model responses for deterministic replay.
- **Eval** — golden goals per `GoalKind` with expected `AnswerKind` and tool-call sequences, run via the eval harness ([../specs/eval-harness.md](eval-harness.md)) under the OFFLINE profile.

Run: `mvn -pl eoiagent-runtime -am test` (Phase 3 graph tests tagged `@Tag("phase3")`, included by default with the stub store).

## Dependencies on other modules

- `eoiagent-core` — all domain types and the `Planner`/`Orchestrator`/`TaskManager` port declarations.
- `eoiagent-model` (`LlmGateway`) — reasoning + planning LLM calls.
- `eoiagent-tool` (`ToolRegistry`) — tool visibility filtering + audited dispatch (including approval routing for mutating tools).
- `eoiagent-knowledge` (`Retriever`) — context for QA/analysis goals.
- `eoiagent-memory` (`ChatMemory`, `MemoryStore`) — per-session history.
- `eoiagent-scratchpad` (`Scratchpad`) — context offloading of large intermediates.
- `eoiagent-safety` (`ApprovalGate`, `PolicyEngine`) — Flow C gating + RBAC/profile checks.
- `eoiagent-persistence` (`CheckpointStore`) — Phase 3 checkpoint/replay/time-travel.
- `eoiagent-observability` (`AuditSink`) — mandatory audit on every model call, tool call, decision, approval, mutation.
- `eoiagent-config` (`ConfigProvider`) — orchestrator selection, step bounds, feature gates.

## Out of scope / deferred

- `LangGraphOrchestrator`, `PostgresCheckpointStore`, breakpoints, time-travel, HITL-on-graph → **Phase 3**.
- `SummarizingChatMemory`-driven long-run compaction is owned by [memory.md](memory.md) (Phase 2); this module only consumes the `ChatMemory` port.
- Mutating Flows C/D → **Phase 2** (read-only ReAct, Flow B, is Phase 1/MVP).
- Multi-run parallel scheduling / a run queue across sessions → Phase 4 hardening.
- Cost/budget accounting per run (token ceilings beyond `maxSteps`) → Phase 4.

## Related ADRs & flows

- ADR: [../adr/0004-hexagonal-ports-and-adapters.md](../adr/0004-hexagonal-ports-and-adapters.md), [../adr/0005-orchestration-agentic-then-langgraph4j.md](../adr/0005-orchestration-agentic-then-langgraph4j.md), [../adr/0010-isolate-experimental-deps.md](../adr/0010-isolate-experimental-deps.md), [../adr/0002-jdk25-maven-httpclient.md](../adr/0002-jdk25-maven-httpclient.md).
- Flows: [Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1), [Flow C](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2), [Flow D](../architecture/04-sequence-flows.md#flow-d--supervisor--sub-agents-delegation-phase-2), [Flow E](../architecture/04-sequence-flows.md#flow-e--long-running-issue-investigation-with-checkpointing-phase-3).
- Component model: [01-component-model.md §Component 4 & §Component 8](../architecture/01-component-model.md#component-4--agent-runtime--orchestration---ports-planner-orchestrator-taskmanager).
