# 04 — Sequence Flows

> The runtime behaviors as step-by-step flows. These are the reference an agent implements the
> `Orchestrator` and `Host Integration` against. Types: [`02-domain-model.md`](02-domain-model.md).

## Flow 0 — Platform bootstrap (startup)

> How a product's **Application Pack** becomes a running `AgentService`. Runs once at host
> startup. Spec: [`../specs/application-pack.md`](../specs/application-pack.md); concept:
> [`05-core-and-application-packs.md`](05-core-and-application-packs.md).

```
host startup → new PlatformBuilder().pack(productPack).start()
  1. PackValidator.validate(pack)              // providers present; OFFLINE↔fallback consistent;
                                               //   featureOverrides within profile matrix
  2. ConfigProvider ← merge(PackConfig defaults, host overrides)     (Config)
  3. LlmGateway     ← ModelProfile             // offline fail-closed                 (Model)
  4. Knowledge      ← ingest(KnowledgeSource[]) into VectorStore                      (Knowledge)
  5. ToolRegistry   ← ToolProvider.tools + mcpServers (classify read-only/mutating)   (Tools)
  6. PolicyEngine ← PolicyProfile; Guardrails/ApprovalGate/AuditSink ← config         (Safety/Audit)
  7. Orchestrator   ← PromptProfile (system prompts/persona) + NavigationCatalog      (Runtime)
  8. AgentService   ← bound to the above; return AgentPlatform (AutoCloseable)        (Host)
→ AppId (from PackMetadata) is stamped into every AgentContext + AuditEvent thereafter.
```

## Flow A — Page-context product help (the common case, Phase 1)

> User on a page asks a question; agent answers inline and/or routes to the right KPI/report
> page with parameters.

```
User ─ask(UserMessage + PageContext)─► AgentSession
  AgentSession → AgentService → Orchestrator.run(Goal=QA, ctx)
    1. Guardrail(input): prompt-injection / PII check        (Safety)
    2. Retriever.retrieve(query + pageContext filters)       (Knowledge) → chunks + citations
    3. LlmGateway.chat(prompt + chunks + visible tools)      (Model)
       - tools visible = ToolRegistry.visibleTo(ctx)  (role + profile filtered, read-only)
    4. Model decides:
        a. answer inline (TEXT)                              → AgentAnswer(TEXT, citations)
        b. inline data/chart: call a read-only data tool     → AgentAnswer(INLINE_ARTIFACT)
        c. route the user: emit NavigationIntent             → AgentAnswer(NAVIGATION)
    5. Guardrail(output): schema/validation                  (Safety)
    6. AuditSink.record(MODEL_CALL, RETRIEVAL, DECISION)     (Observability)
  AgentSession ◄─ AgentAnswer ─ (streamed via AnswerSink if askStream)
```

Decision heuristic (in the system prompt + enforced by output schema): *prefer routing the user
to an existing KPI/report page over re-deriving data inline*; answer inline only for quick facts
or when no page fits. The valid `targetPageId`s and their parameters come from the Application
Pack's `NavigationCatalog`; the host validates the model's proposed `NavigationIntent` against it
(unknown page → fall back to TEXT/CLARIFICATION). The system prompt/persona come from the pack's
`PromptProfile`.

## Flow B — ReAct loop with read-only tools (Phase 1)

```
Orchestrator.run(goal, ctx):
  loop (bounded by maxSteps):
    thought, action = LlmGateway.chat(history + tools)
    if action is final → return AgentAnswer
    if action is toolCall:
        ToolRegistry.dispatch(call, ctx):
            PolicyEngine.check(ctx, toolSpec)        // RBAC + profile
            (read-only → no approval)
            result = tool.invoke(call)
            AuditSink.record(TOOL_CALL)
        append result to history; continue
    Scratchpad.write(...) for large intermediate results   // context offloading
```

## Flow C — Plan → approve → act (mutating actions, Phase 2)

> The single most safety-critical flow. No mutation happens without a recorded approval.

```
Orchestrator.run(goal=OPERATIONAL_ACTION / PIPELINE_AUTHOR, ctx):
  1. Planner.plan(goal, ctx) → Plan (steps flagged mutating=true/false)
  2. TaskManager.create(plan) → TaskList   (visible to user / host)
  3. for each step:
       read-only step  → execute as Flow B
       mutating step:
         a. DryRunResult = ApprovalGate.dryRun(toolCall)        // preview effects
         b. ApprovalRequest{call, humanSummary, preview}
            decision = ApprovalGate.request(req)   // BLOCKS for human
            AuditSink.record(APPROVAL, decision)
         c. if APPROVED:
               ToolRegistry.dispatch(call, ctx)    // performs mutation
               AuditSink.record(MUTATION)
               TaskManager.update(step, DONE)
            else DENIED/TIMED_OUT:
               TaskManager.update(step, BLOCKED); Planner.revise(plan, obs)
  4. return AgentAnswer(action-taken summary + citations)
```

`ApprovalGate.request` is satisfied by the host supplying an `ApprovalHandler` (callback /
UI prompt / queue). In OFFLINE/headless contexts a configured policy may auto-deny or require an
explicit operator token.

## Flow D — Supervisor + sub-agents (delegation, Phase 2)

```
SupervisorOrchestrator.run(goal, ctx):
  supervisor (LLM) picks next worker by sub-goal:
    ├─ AnalysisAgent   (schema/metadata analysis, read-only tools)
    ├─ SqlAgent        (generate + read-only validate SQL)
    └─ PipelineAgent   (author/explain pipeline spec; mutations via Flow C)
  each worker = isolated AgentContext + own tool subset + own scratchpad scope
  supervisor aggregates worker results → final AgentAnswer
```

Implemented on `langchain4j-agentic` supervisor/planner in MVP/Phase 2.

## Flow E — Long-running issue investigation with checkpointing (Phase 3)

> Cyclical: investigate → find something → revise plan → retry → maybe pause for a human →
> resume tomorrow. This is where `LangGraphOrchestrator` replaces `AgenticOrchestrator`.

```
LangGraphOrchestrator.run(goal=INVESTIGATION, ctx):
  graph nodes: gatherSignals → hypothesize → testHypothesis → (loop back | escalate | conclude)
  - CheckpointStore.save(runId, checkpoint) after each node     // survives restart
  - breakpoint before mutating/escalation node → ApprovalGate (Flow C)
  - on restart: CheckpointStore.latest(runId) → resume
  - time-travel: CheckpointStore.history(runId) → replay/inspect
  tools: events/alerts/incidents/cases Java-API tools + logs/config retrieval
  → AgentAnswer(root-cause hypothesis + evidence citations + recommended/!taken actions)
```

## Cross-cutting invariants (assert in tests)

1. **Every** `MODEL_CALL`, `TOOL_CALL`, mutation, and approval emits an `AuditEvent` (C5).
2. **No** mutating `ToolResult` is produced without a preceding `APPROVED` `AuditEvent` (C4).
3. In `OFFLINE`, no flow performs a network call (C2) — enforced by profile checks, asserted by
   a network-deny test harness.
4. `ToolRegistry.visibleTo(ctx)` never returns a tool whose `requiredRole` exceeds `ctx.role` or
   whose capability the profile disables.
