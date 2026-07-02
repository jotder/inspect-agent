---
type: spec
title: "Host Integration — Spec"
description: "The \"embedded under every page\" product surface: the host opens a session, asks page-aware questions, and gets back a typed `AgentAnswer` (often a `NavigationIntent`)."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["host-integration"]
---
# Host Integration — Spec

> The "embedded under every page" product surface: the host opens a session, asks page-aware questions, and gets back a typed `AgentAnswer` — most often a `NavigationIntent` routing the user to the right KPI/report page. Component 10 in [01-component-model.md](../architecture/01-component-model.md). Port(s): `AgentService`, `AgentSession`.

## Purpose

This is the only module the host application is expected to touch directly. The EOI Agent sits embedded beneath every page of the host product; a small chat/ask affordance lets a user ask a question in the context of whatever page they are on. The host calls `AgentService.open(...)` once per user/session and then `AgentSession.ask(...)` (or `askStream`) per question, supplying the current `PageContext` every time.

The **signature product behavior** is navigation: rather than re-deriving data inline, the agent usually answers a help question by **routing the user to an existing KPI/report page with pre-filled parameters** (`AnswerKind.NAVIGATION` + `NavigationIntent`). Inline text, inline artifacts, clarification requests, and errors are the other answer kinds. This is Flow A. Phase 1.

The host also supplies an `ApprovalHandler` (for Phase 2 mutating flows) and consumes streaming tokens via an `AnswerSink`. Per [conventions.md §2](../conventions.md#2-dependency-direction-enforced), the host depends only on `eoiagent-host` (this API) + `eoiagent-core`; no adapter or framework type is ever exposed across this boundary.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-10--host-integration---ports-agentservice-agentsession), package `com.eoiagent.host`. Copied verbatim:

```java
package com.eoiagent.host;

public interface AgentService {
    AgentSession open(SessionRequest req);   // carries user, role, page-context, profile
}
public interface AgentSession {
    AgentAnswer ask(UserMessage msg);                 // blocking
    void askStream(UserMessage msg, AnswerSink sink); // streaming
    void close();
}
```

Contract notes:

- **`AgentService.open(req)`** — Pre: `req` non-null with `user`, `role`, `profile`, and an initial `PageContext`. Post: returns a live `AgentSession` bound to a fresh `SessionId`, with a `ChatMemory` view and a system prompt assembled for the role/profile. Threading: `AgentService` is a singleton-safe, thread-safe factory; many sessions may be opened concurrently. Throws `ConfigException` if the profile/config is invalid, `PolicyViolation` if the role is not permitted any capability under the profile.
- **`AgentSession.ask(msg)`** — Pre: `msg` non-null; `msg.page()` is the **current** `PageContext` (must be supplied on every ask — see [glossary "Page context"](../glossary.md#product-surface-concepts)). Post: returns a non-null `AgentAnswer` whose `kind` is never null; never throws past this boundary for runtime faults — failures arrive as `AgentAnswer(ERROR)`. Blocks until the run completes. Threading: a single `AgentSession` is **single-threaded from the caller's perspective** ([conventions.md §6](../conventions.md#6-concurrency--resources)); concurrent `ask` on the same session is undefined.
- **`AgentSession.askStream(msg, sink)`** — Same pre/post as `ask`, but emits partial output to `sink` as it is produced and completes the sink with the final `AgentAnswer`. Pre: `sink` non-null. The sink is invoked on the run's executing thread; the host must treat callbacks as potentially off the UI thread.
- **`AgentSession.close()`** — Post: flushes memory via `MemoryStore.put`, closes per-session resources (`AutoCloseable` adapters), idempotent. After close, further `ask` throws `IllegalStateException`.

`SessionRequest`, `AnswerSink`, `ApprovalHandler`, and `PageContext` are defined/owned at this boundary (see Inputs / Outputs); `AgentAnswer`, `AnswerKind`, `NavigationIntent`, `InlineArtifact`, `UserMessage`, `PageContext`, `Citation`, `Role`, `DeploymentProfile` are core domain types.

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `DefaultAgentService` | (ours) | Phase 1 | Wires `ConfigProvider`, `ToolRegistry`, `Retriever`, `Orchestrator`, `Memory`, `Safety`, `AuditSink` into sessions; the host's single entry point. |
| `DefaultAgentSession` | (ours) | Phase 1 | Per-session: holds `AgentContext`, `ChatMemory`, system prompt; delegates each `ask` to `Orchestrator.run`. |
| `CallbackAnswerSink` | (ours) | Phase 1 | Bridges streaming tokens from `LlmGateway.chatStream` (`TokenSink`) into the host's `AnswerSink`. |

The host implements `ApprovalHandler` and `AnswerSink` (they are SPIs the host plugs in). The `CallbackApprovalGate` ([01 §Component 7](../architecture/01-component-model.md#component-7--safety--governance---ports-approvalgate-guardrail-policyengine)) adapts the host's `ApprovalHandler` for Flow C.

## Maven coordinates

This module: `com.eoiagent:eoiagent-host` (version `0.1.0-SNAPSHOT`). The host-facing API (`AgentService`, `AgentSession`, `SessionRequest`, `AnswerSink`, `ApprovalHandler`) lives here; per [01-component-model.md](../architecture/01-component-model.md#dependency-direction-must-hold) it is the `host-integration-api` the host app depends on, alongside `eoiagent-core`.

Depends on `com.eoiagent:eoiagent-core` and (at runtime wiring) the port modules it composes: `eoiagent-runtime`, `eoiagent-model`, `eoiagent-tool`, `eoiagent-knowledge`, `eoiagent-memory`, `eoiagent-safety`, `eoiagent-observability`, `eoiagent-config`. It depends on **ports**, not on specific adapters — concrete adapters are chosen by config and injected at construction.

No third-party agent library appears in this module (no LangChain4j, no agentic, no LangGraph4j) — that is the whole point of the boundary (ADR-0001, ADR-0010). The only third-party code reachable is via the ports it consumes. Nothing to pin here beyond the project BOM.

## Inputs / Outputs

Consumes (from `com.eoiagent.core`): `UserMessage(text, page, at)`, `PageContext(pageId, entityIds, filters)`, `Role`, `DeploymentProfile`, `UserId`, `SessionId`.

Produces (from `com.eoiagent.core`): `AgentAnswer(kind, text, artifact, navigation, citations, run)` carrying one of:

- `AnswerKind.TEXT` — a short inline textual answer (quick fact); `text` set, `artifact`/`navigation` null.
- `AnswerKind.INLINE_ARTIFACT` — a chart/table/data answer rendered on the current page; `artifact` = `InlineArtifact(mimeType, title, data, meta)`.
- `AnswerKind.NAVIGATION` — **the primary behavior**: route the user to a target page; `navigation` = `NavigationIntent(targetPageId, parameters, rationale)`.
- `AnswerKind.CLARIFICATION` — the agent needs more input; `text` holds the clarifying question.
- `AnswerKind.ERROR` — a failure surfaced as data (not an exception); `text` holds a safe message; `run` still set for audit correlation.

`NavigationIntent` ([02-domain-model.md](../architecture/02-domain-model.md#navigationintent--the-signature-product-behavior)): `targetPageId` is a **host-defined page/route id**, `parameters` are pre-filled filters/params for that page, `rationale` is shown to the user and audited. The host is responsible for resolving `targetPageId` to an actual route and applying `parameters`.

Owned at this boundary (define in `com.eoiagent.host`):

```java
package com.eoiagent.host;

record SessionRequest(
    UserId user, Role role, DeploymentProfile profile,
    PageContext initialPage, Map<String,String> attributes) {}

interface AnswerSink {                       // host implements; streaming output
    void onToken(String token);              // partial text as it is generated
    void onArtifact(InlineArtifact artifact);// optional: streamed/early artifact
    void onComplete(AgentAnswer finalAnswer);// terminal: the full typed answer
    void onError(EoiAgentException error);    // terminal: stream failed
}

interface ApprovalHandler {                  // host implements; Phase 2 HITL
    ApprovalDecision onApprovalRequested(ApprovalRequest request);  // may block / prompt UI
}
```

## Behavior / algorithm

### Session open

1. `AgentService.open(req)`: validate `req` against the active profile (`ConfigProvider`), allocate a `SessionId`, build the `AgentContext(session, user, role, profile, page=req.initialPage(), attributes)`.
2. Construct the per-session `ChatMemory` (via Memory module) and assemble the system prompt for the role + profile, including the Flow A navigation heuristic: *prefer routing the user to an existing KPI/report page over re-deriving data inline; answer inline only for quick facts or when no page fits*.
3. Register the host's `ApprovalHandler` with the `CallbackApprovalGate` for this session (Phase 2).

### Ask — Flow A ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1))

1. On each `ask(msg)`, refresh `AgentContext.page` with `msg.page()` — **PageContext flows in on every ask**; `entityIds` and `filters` are passed to the retriever and tools so answers are scoped to what the user is looking at.
2. Build `Goal(msg.text(), GoalKind.QA)` (kind may be inferred for richer requests) and call `Orchestrator.run(goal, ctx)`. Inside the run (per Flow A):
   - input guardrail (prompt-injection / PII),
   - `Retriever.retrieve(query + page filters)` → chunks + citations,
   - `LlmGateway.chat(prompt + chunks + ToolRegistry.visibleTo(ctx))` — visible tools are role/profile-filtered and read-only for QA,
   - the model decides: inline TEXT, a read-only data tool → INLINE_ARTIFACT, or emit a `NavigationIntent` → NAVIGATION,
   - output guardrail (schema/validation),
   - `AuditSink.record(MODEL_CALL, RETRIEVAL, DECISION)`.
3. Map the `AgentRun.answer()` to the returned `AgentAnswer`, carrying `citations` and `run`. Persist the turn to memory.

### Navigation answer (the primary path)

- When the model determines an existing page best serves the request, it produces a `NavigationIntent` whose `targetPageId` is one the host advertised (the host registers its routable pages/KPIs, e.g. via tool/config metadata) and whose `parameters` derive from `PageContext.entityIds`/`filters` + the user's request. The agent may prepend a one-line inline `text` ("Opening the Pipeline Health report filtered to pipeline-42") and set `kind=NAVIGATION`. The host then performs the navigation and applies the parameters.

### Streaming

- `askStream(msg, sink)`: the session bridges `LlmGateway.chatStream`'s `TokenSink` to `AnswerSink.onToken` as text is generated, calls `onArtifact` if an inline artifact is produced mid-run, and finishes with `onComplete(finalAnswer)` (or `onError`). The terminal `AgentAnswer` is always the same typed object `ask` would have returned.

### Approval handoff (Phase 2)

- For mutating flows (C/D), the `CallbackApprovalGate` invokes the host's `ApprovalHandler.onApprovalRequested(request)`; the host shows a prompt/queues the request and returns an `ApprovalDecision`. The decision is audited (`APPROVAL`). In headless/OFFLINE contexts a configured policy may auto-deny or require an operator token.

## Configuration keys

Keys this module reads (prefix `eoiagent.`):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) | Meaning |
|-----|------|--------------------------------------------|---------|
| `eoiagent.profile` | enum | `OFFLINE` / `ON_PREM_HOSTED` / `CLOUD` | active `DeploymentProfile` (validated at `open`) |
| `eoiagent.host.streaming.enabled` | boolean | `true` / `true` / `true` | allow `askStream` token streaming |
| `eoiagent.host.navigation.preferOverInline` | boolean | `true` / `true` / `true` | bias the system prompt toward `NAVIGATION` answers (Flow A heuristic) |
| `eoiagent.host.maxAnswerChars` | int | `4000` / `8000` / `8000` | cap inline `TEXT`/`CLARIFICATION` length |
| `eoiagent.approval.required` | boolean | `true` / `true` / `true` | whether mutating flows require the `ApprovalHandler` (Phase 2) |

It also consumes `ConfigProvider.featureEnabled(...)` indirectly — e.g. `HOSTED_MODELS`, `MUTATING_ACTIONS` — but does not gate features itself; gating happens in the modules it composes.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- Runtime faults during `ask` (`ModelUnavailableException`, `ToolExecutionException`, `GuardrailViolation`) are caught at the session boundary and returned as `AgentAnswer(ERROR, safeMessage, run=...)` after an `ERROR` `AuditEvent` — the host never sees a raw exception from `ask`.
- `PolicyViolation` from RBAC/profile checks similarly surfaces as `AgentAnswer(ERROR)` with a user-safe message (do not leak which capability was blocked beyond what policy permits). **Offline fail-closed:** a request needing the network in `OFFLINE` yields `ERROR`, never a silent network attempt.
- `askStream` failures terminate the sink via `onError(EoiAgentException)`; partial tokens already delivered are the host's to discard.
- `ConfigException` from `AgentService.open` **propagates** (it is a misconfiguration, not a per-ask runtime failure) — the host must fail to start the session.
- `ApprovalDeniedException` is internal to Flow C; the host sees the outcome as an `AgentAnswer` summarizing that the action was not taken.
- Using a closed session throws `IllegalStateException`.

## Acceptance criteria

- **AC1** — `AgentService.open` returns an `AgentSession` bound to a fresh `SessionId`; opening with an invalid profile/config throws `ConfigException`.
- **AC2** — `ask(msg)` returns an `AgentAnswer` with non-null `kind`; with a stub orchestrator returning a navigation result, the answer is `kind=NAVIGATION` with a populated `NavigationIntent(targetPageId, parameters, rationale)`.
- **AC3** — The `PageContext` from `msg.page()` is propagated into the `AgentContext` and reaches the retriever/tools on **every** ask (verified by a spy retriever receiving the page filters).
- **AC4** — A quick-fact request yields `kind=TEXT`; a data/chart request yields `kind=INLINE_ARTIFACT` with a populated `InlineArtifact`; an ambiguous request yields `kind=CLARIFICATION`.
- **AC5** — With `navigation.preferOverInline=true`, when both a page route and an inline answer are viable, the answer is `NAVIGATION` (heuristic honored).
- **AC6** — A runtime fault injected into the orchestrator surfaces as `AgentAnswer(ERROR)` (no exception thrown from `ask`) plus an `ERROR` `AuditEvent`; the `run` id is set for correlation.
- **AC7** — `askStream` delivers ≥1 `onToken` then exactly one `onComplete(finalAnswer)`; an injected stream fault yields exactly one `onError` and no `onComplete`.
- **AC8** — `close()` flushes memory (a subsequent `open`+`get` on the same `SessionId` returns the prior turns) and is idempotent; `ask` after `close` throws `IllegalStateException`.
- **AC9** (Phase 2) — A mutating flow invokes the host's `ApprovalHandler.onApprovalRequested`; returning `DENIED` results in a no-mutation `AgentAnswer` and an `APPROVAL` `AuditEvent`.
- **AC10** — With `profile=OFFLINE`, an ask requiring a hosted model returns `AgentAnswer(ERROR)` and performs no network call (network-deny harness).

## Test plan

All default tests run **NO network, NO live LLM** — using a stub `Orchestrator` (and beneath it a stub `LlmGateway`/`Retriever`) so the host contract is exercised deterministically. Framework: JUnit 5 + AssertJ; Mockito for the host SPIs (`AnswerSink`, `ApprovalHandler`) where a spy is clearer than a hand-written stub.

- **Unit** — `DefaultAgentServiceTest` (AC1), `DefaultAgentSessionTest` (AC2–AC6, AC8), `CallbackAnswerSinkTest` (AC7), `NavigationIntentMappingTest` (AC2/AC5 — orchestrator result → `AgentAnswer(NAVIGATION)`).
- **Contract** — `AgentSessionContractTest`: a shared suite every `AgentSession` adapter must pass (non-null typed answer, page propagation, error-as-answer, streaming terminal semantics, close/idempotency — AC2/AC3/AC6/AC7/AC8). `PageContextPropagationTest` with a spy retriever (AC3).
- **Phase 2** — `ApprovalHandoffTest` (AC9) with a stub `ApprovalHandler`.
- **Offline** — `OfflineFailClosedTest` (AC10) under the network-deny harness.
- **Eval** — Flow A golden Q&A: page-context questions asserting expected `AnswerKind` and (for navigation) `targetPageId` + key `parameters`, run under OFFLINE via the eval harness ([../specs/eval-harness.md](eval-harness.md)).

Run: `mvn -pl eoiagent-host -am test`.

## Dependencies on other modules

- `eoiagent-core` — `AgentAnswer`, `AnswerKind`, `NavigationIntent`, `InlineArtifact`, `UserMessage`, `PageContext`, `Citation`, `Role`, `DeploymentProfile`, identity records, exception base.
- `eoiagent-runtime` (`Orchestrator`) — drives each `ask` (Flow A run).
- `eoiagent-knowledge` (`Retriever`) — page-scoped retrieval + citations.
- `eoiagent-model` (`LlmGateway` streaming `TokenSink`) — token streaming bridge.
- `eoiagent-tool` (`ToolRegistry`) — visible read-only tools for QA.
- `eoiagent-memory` (`ChatMemory`, `MemoryStore`) — per-session continuity; flush on `close`.
- `eoiagent-safety` (`CallbackApprovalGate`, `Guardrail`, `PolicyEngine`) — guardrails on ask; approval handoff (Phase 2).
- `eoiagent-observability` (`AuditSink`) — MODEL_CALL / RETRIEVAL / DECISION / APPROVAL / ERROR.
- `eoiagent-config` (`ConfigProvider`) — profile validation + host config keys.

## Out of scope / deferred

- Mutating-action approval handoff (`ApprovalHandler` wiring through Flow C) → **Phase 2** (the `ApprovalHandler` SPI is defined now so the contract is stable).
- Host page/route registry format (how the host advertises `targetPageId`s and their parameter schemas) → defined with the Tool registry; this spec assumes it exists.
- Multi-turn UI affordances (suggested follow-ups, answer feedback) → Phase 4.
- WebSocket/SSE transport specifics — `AnswerSink` is transport-agnostic; the host adapts it.
- i18n of answers — English only in v1 (constraint C8).

## Related ADRs & flows

- ADR: [../adr/0001-embeddable-java-no-spring.md](../adr/0001-embeddable-java-no-spring.md) (plain-Java embeddable API), [../adr/0004-hexagonal-ports-and-adapters.md](../adr/0004-hexagonal-ports-and-adapters.md), [../adr/0010-isolate-experimental-deps.md](../adr/0010-isolate-experimental-deps.md) (no framework types across this boundary).
- Flows: [Flow A](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1) (the common case), [Flow C](../architecture/04-sequence-flows.md#flow-c--plan--approve--act-mutating-actions-phase-2) (approval handoff).
- Component model: [01-component-model.md §Component 10](../architecture/01-component-model.md#component-10--host-integration---ports-agentservice-agentsession).
- Domain: [02-domain-model.md §NavigationIntent](../architecture/02-domain-model.md#navigationintent--the-signature-product-behavior), [§Conversation & answers](../architecture/02-domain-model.md#conversation--answers).
