# Backlog — Agent-Sized Tickets

> Each ticket is sized for **one agent session**: a single module/adapter + its tests. Pick the
> lowest-numbered ticket in the current phase whose **Depends-on** are all done (see
> [`../../AGENTS.md`](../../AGENTS.md)). Every **Acceptance criterion (AC)** must map to a passing
> test; "done" = [`../conventions.md` §9](../conventions.md). Specs hold the full detail — tickets
> are the index.

**Ticket format:** `ID · Title · Module · Spec · Depends-on · Description · ACs · Verify`.
Test commands assume the Phase-0 Maven skeleton exists. Default tests run **no network, no live
LLM** (stub `LlmGateway`).

---

## Phase 0 — Foundations

### T-001 · Maven multi-module skeleton + BOM
- **Module:** (root) · **Spec:** [config-profiles](../specs/config-profiles.md), [02-domain-model](../architecture/02-domain-model.md#maven-coordinates) · **Depends-on:** —
- Build the `eoiagent-*` modules and `eoiagent-bom` (imports `langchain4j-bom:1.16.3`); set
  `maven.compiler.release=25`, Maven 3.9+. Empty modules compile.
- **AC1** `mvn -q verify` succeeds on JDK 25 with empty modules. **AC2** every module from
  [02-domain-model §Maven coordinates](../architecture/02-domain-model.md#maven-coordinates) exists.
  **AC3** no third-party version is pinned outside a BOM.
- **Verify:** `mvn -q verify`

### T-002 · Core domain types
- **Module:** `eoiagent-core` · **Spec:** [02-domain-model](../architecture/02-domain-model.md) · **Depends-on:** T-001
- Implement every record/enum in the domain model (`AgentContext`, `AgentAnswer`,
  `NavigationIntent`, `Plan`, `Task`, `ToolSpec`, `AuditEvent`, `DeploymentProfile`, `Feature`,
  `Capability`, …).
- **AC1** all types compile as `record`/`enum` with the exact names. **AC2** no agent-framework
  import in `eoiagent-core`. **AC3** value types are immutable.
- **Verify:** `mvn -q -pl eoiagent-core test`

### T-003 · Port interfaces (compile-only)
- **Module:** `eoiagent-core` + `*-api` · **Spec:** [01-component-model](../architecture/01-component-model.md) · **Depends-on:** T-002
- Declare all 11+ ports verbatim (`LlmGateway`, `Retriever`, `VectorStore`, `DocumentIngestor`,
  `Tool`, `ToolRegistry`, `Planner`, `Orchestrator`, `TaskManager`, `MemoryStore`, `Scratchpad`,
  `ApprovalGate`, `PolicyEngine`, `Guardrail`, `CheckpointStore`, `AuditSink`, `TraceCollector`,
  `AgentService`, `AgentSession`, `ConfigProvider`).
- **AC1** signatures match the component model exactly. **AC2** ports import only core/domain.
- **Verify:** `mvn -q -pl eoiagent-core test`

### T-004 · Exception hierarchy
- **Module:** `eoiagent-core` · **Spec:** [conventions §5](../conventions.md) · **Depends-on:** T-002
- `EoiAgentException` + `ModelUnavailableException`, `ToolExecutionException`, `PolicyViolation`,
  `ApprovalDeniedException`, `GuardrailViolation`, `ConfigException`, `AuditException`.
- **AC1** all extend `EoiAgentException`. **AC2** each carries a message + optional cause.
- **Verify:** `mvn -q -pl eoiagent-core test`

### T-005 · Architecture tests (ArchUnit)
- **Module:** `eoiagent-core` (test) · **Spec:** [conventions §2](../conventions.md), [ADR-0004](../adr/0004-hexagonal-ports-and-adapters.md) · **Depends-on:** T-003
- Rules: adapters → ports only; **no** `dev.langchain4j`/`org.bsc.langgraph4j` import in
  `eoiagent-core`; experimental deps only in adapter modules ([ADR-0010](../adr/0010-isolate-experimental-deps.md)).
- **AC1** rules pass for the skeleton. **AC2** a deliberate violation fails the build (negative test).
- **Verify:** `mvn -q test -pl eoiagent-core`

### T-006 · ConfigProvider + capability matrix
- **Module:** `eoiagent-config` · **Spec:** [config-profiles](../specs/config-profiles.md) · **Depends-on:** T-003
- `EnvConfigProvider`, `PropertiesConfigProvider`, `ProgrammaticConfigProvider`; implement
  `featureEnabled` against the matrix in [03-deployment-profiles](../architecture/03-deployment-profiles.md).
- **AC1** matrix returns documented values per profile. **AC2** OFFLINE disables `HOSTED_MODELS`.
  **AC3** unknown key returns its `ConfigKey` default.
- **Verify:** `mvn -q -pl eoiagent-config test`

### T-007 · Stub LlmGateway + test fixtures
- **Module:** `eoiagent-model` (test-support) · **Spec:** [model-gateway](../specs/model-gateway.md) · **Depends-on:** T-003
- Deterministic `StubLlmGateway` (scripted chat/tool-call/stream/embed responses) for all
  no-network tests.
- **AC1** returns scripted `ChatResult`/`EmbeddingResult`. **AC2** can emit tool calls + a final
  answer. **AC3** zero network usage.
- **Verify:** `mvn -q -pl eoiagent-model test`

### T-008 · Eval harness scaffold
- **Module:** `eoiagent-eval` · **Spec:** [eval-harness](../specs/eval-harness.md) · **Depends-on:** T-007
- Case-file loader (YAML/JSON schema) + runner + scoring (exact/contains/regex). Runs against the
  stub gateway.
- **AC1** loads a sample case set. **AC2** reports pass/fail per case. **AC3** runs in OFFLINE CI.
- **Verify:** `mvn -q -pl eoiagent-eval test`

### T-009 · Application Pack SPI (`eoiagent-app-api`)
- **Module:** `eoiagent-app-api` · **Spec:** [application-pack](../specs/application-pack.md), [05-core-and-application-packs](../architecture/05-core-and-application-packs.md), [ADR-0011](../adr/0011-core-and-application-pack-split.md) · **Depends-on:** T-002
- Declare `ApplicationPack` + the 7 provider interfaces + records (`PackMetadata`, `ModelProfile`,
  `KnowledgeSource`, `ToolProvider`, `NavigationCatalog`, `PromptProfile`, `PolicyProfile`,
  `PackConfig`) verbatim from the domain model. Compile-only SPI.
- **AC1** signatures match [02-domain-model §Application Pack SPI](../architecture/02-domain-model.md#application-pack-spi--comeoiagentapp). **AC2** `eoiagent-app-api` imports **only** `eoiagent-core` domain types (ArchUnit). **AC3** no agent-framework or core-adapter import.
- **Verify:** `mvn -q -pl eoiagent-app-api test`

### T-010 · Platform bootstrap (`eoiagent-platform`)
- **Module:** `eoiagent-platform` · **Spec:** [application-pack](../specs/application-pack.md) · **Depends-on:** T-009, T-006, T-007
- `PlatformBuilder` + `DefaultAgentPlatform` + `PackValidator`; implement Flow 0 wiring against a
  `StubApplicationPack` + `StubLlmGateway` (no network/LLM). Stamp `AppId` into `AgentContext`/`AuditEvent`.
- **AC1** `PlatformBuilder.pack(stubPack).start()` returns a usable `AgentPlatform`. **AC2** OFFLINE+hosted-fallback or out-of-matrix featureOverride → `PolicyViolation` at validate. **AC3** missing required provider → `ConfigException` naming it. **AC4** `close()` closes all `AutoCloseable` adapters.
- **Verify:** `mvn -q -pl eoiagent-platform test`

---

## Phase 1 — MVP

### T-101 · ONNX embedding adapter (offline)
- **Module:** `eoiagent-knowledge` · **Spec:** [rag-knowledge](../specs/rag-knowledge.md) · **Depends-on:** T-003
- `OnnxEmbeddingAdapter` over `all-MiniLM-l6-v2`, in-JVM.
- **AC1** OFFLINE: returns 384-dim vectors, **no network**. **AC2** stable vectors for identical input.
- **Verify:** `mvn -q -pl eoiagent-knowledge test`

### T-102 · In-memory vector store
- **Module:** `eoiagent-knowledge` · **Spec:** [rag-knowledge](../specs/rag-knowledge.md) · **Depends-on:** T-101
- `InMemoryVectorStore` (`VectorStore` port) with disk save/load + metadata filter.
- **AC1** top-k search ranked by similarity. **AC2** metadata filter narrows results. **AC3** save→load round-trips.
- **Verify:** `mvn -q -pl eoiagent-knowledge test`

### T-103 · Document loaders + ingestor
- **Module:** `eoiagent-knowledge` · **Spec:** [rag-knowledge](../specs/rag-knowledge.md) · **Depends-on:** T-102
- `ProductDocLoader`, `ConfigFileLoader`, `SchemaConfigLoader` + `DocumentIngestor` (load→split→embed→store).
- **AC1** ingests a sample doc + config + schema. **AC2** `IngestReport` counts docs/chunks. **AC3** chunks carry citations.
- **Verify:** `mvn -q -pl eoiagent-knowledge test`

### T-104 · Retriever
- **Module:** `eoiagent-knowledge` · **Spec:** [rag-knowledge](../specs/rag-knowledge.md) · **Depends-on:** T-103
- `Retriever` (top-k + filters) returning `RetrievedChunk` with `Citation`.
- **AC1** returns k chunks for a query. **AC2** honors page-context filters. **AC3** empty corpus → empty (no throw).
- **Verify:** `mvn -q -pl eoiagent-knowledge test`

### T-105 · Ollama adapters
- **Module:** `eoiagent-model` · **Spec:** [model-gateway](../specs/model-gateway.md) · **Depends-on:** T-007
- `OllamaChatAdapter` + `OllamaEmbeddingAdapter`.
- **AC1** unit tests vs a mocked endpoint. **AC2** profile-tagged integration test vs local Ollama (opt-in). **AC3** reports `ModelInfo.local=true`.
- **Verify:** `mvn -q -pl eoiagent-model test`

### T-106 · OpenAI-compatible chat adapter
- **Module:** `eoiagent-model` · **Spec:** [model-gateway](../specs/model-gateway.md), [ADR-0006](../adr/0006-local-llm-portability-openai-compatible.md) · **Depends-on:** T-007
- `OpenAiCompatibleChatAdapter` (`baseUrl`, JDK `HttpClient`) for llama.cpp/vLLM/LM Studio.
- **AC1** targets a configurable `baseUrl`, no API key required locally. **AC2** uses JDK HttpClient (no Netty). **AC3** streaming emits tokens to `TokenSink`.
- **Verify:** `mvn -q -pl eoiagent-model test`

### T-107 · RoutingLlmGateway
- **Module:** `eoiagent-model` · **Spec:** [model-gateway](../specs/model-gateway.md) · **Depends-on:** T-105, T-106, T-006
- Route/fallback per `DeploymentProfile`; **OFFLINE fails closed** (never network).
- **AC1** CLOUD: hosted→local fallback. **AC2** OFFLINE: never attempts egress; throws `PolicyViolation` if forced. **AC3** `activeChatModel()` reflects who answered.
- **Verify:** `mvn -q -pl eoiagent-model test`

### T-108 · Chat memory + in-memory store
- **Module:** `eoiagent-memory` · **Spec:** [memory](../specs/memory.md) · **Depends-on:** T-003
- `WindowChatMemory`/`TokenWindowChatMemory` + `InMemoryMemoryStore`.
- **AC1** window bounds message count/tokens. **AC2** store get/put/delete round-trips per `SessionId`.
- **Verify:** `mvn -q -pl eoiagent-memory test`

### T-109 · In-memory scratchpad
- **Module:** `eoiagent-scratchpad` · **Spec:** [scratchpad](../specs/scratchpad.md) · **Depends-on:** T-003
- `InMemoryScratchpad` (write/read/list/delete by handle).
- **AC1** write returns a stable handle; read returns content. **AC2** list honors prefix. **AC3** missing key handled per spec.
- **Verify:** `mvn -q -pl eoiagent-scratchpad test`

### T-110 · Java-API tools + registry (read-only)
- **Module:** `eoiagent-tool` · **Spec:** [tool-registry](../specs/tool-registry.md) · **Depends-on:** T-003
- `JavaApiTool` (wrap host `@Tool` methods via LC4j AI Services) + `ToolRegistry`;
  `visibleTo(ctx)` filters by role/profile; **read-only** dispatch + audit.
- **AC1** registers + lists tools filtered by role/profile. **AC2** dispatch validates args, returns `ToolResult`, emits `TOOL_CALL` audit. **AC3** a mutating tool is **rejected** in Phase 1 (no gate yet).
- **Verify:** `mvn -q -pl eoiagent-tool test`

### T-111 · ReAct + Agentic orchestrators
- **Module:** `eoiagent-runtime` · **Spec:** [orchestration-runtime](../specs/orchestration-runtime.md), [ADR-0005](../adr/0005-orchestration-agentic-then-langgraph4j.md) · **Depends-on:** T-107, T-110, T-104, T-108, T-109
- `ReActOrchestrator` (our loop) + `AgenticOrchestrator` (`langchain4j-agentic`, behind the port,
  experimental → quarantined).
- **AC1** drives Flow B with stub gateway to a final `AgentAnswer`. **AC2** offloads large results via `Scratchpad`. **AC3** `langchain4j-agentic` appears only in this adapter module.
- **Verify:** `mvn -q -pl eoiagent-runtime test`

### T-112 · Input guardrails
- **Module:** `eoiagent-safety` · **Spec:** [guardrails](../specs/guardrails.md), [ADR-0010](../adr/0010-isolate-experimental-deps.md) · **Depends-on:** T-003
- `Lc4jInputGuardrail` (prompt-injection + PII) on `langchain4j-guardrails` (experimental, behind port).
- **AC1** flags an injection probe (`FAIL`). **AC2** redacts PII (`REDACTED`). **AC3** clean input → `PASS`.
- **Verify:** `mvn -q -pl eoiagent-safety test`

### T-113 · Audit sink wired through runtime
- **Module:** `eoiagent-observability` · **Spec:** [audit-observability](../specs/audit-observability.md), [ADR-0009](../adr/0009-audit-trail-and-observability.md) · **Depends-on:** T-111
- `Slf4jAuditSink` + `FileAuditSink`; emit `AuditEvent` for model/tool/retrieval/decision.
- **AC1** a run produces `MODEL_CALL`+`TOOL_CALL`+`RETRIEVAL`+`DECISION` events. **AC2** append-only (no update/delete). **AC3** audit ≠ logging (separate path).
- **Verify:** `mvn -q -pl eoiagent-observability test`

### T-114 · Host integration + NavigationIntent (Flow A)
- **Module:** `eoiagent-host` · **Spec:** [host-integration](../specs/host-integration.md) · **Depends-on:** T-111, T-112, T-113
- `AgentService`/`AgentSession`; `ask`/`askStream`; build `AgentAnswer` (TEXT / INLINE_ARTIFACT /
  **NAVIGATION**) from page context.
- **AC1** a help question over the corpus returns a TEXT answer with citations. **AC2** a "take me to X" question returns a `NavigationIntent` with `targetPageId` + parameters. **AC3** streaming delivers tokens then a terminal answer. **AC4** every ask is audited.
- **Verify:** `mvn -q -pl eoiagent-host test`

### T-115 · MVP golden eval set
- **Module:** `eoiagent-eval` · **Spec:** [eval-harness](../specs/eval-harness.md) · **Depends-on:** T-114, T-008
- Golden product-help Q&A + navigation-intent assertions; runs OFFLINE + one online profile.
- **AC1** ≥20 cases covering Q&A + navigation. **AC2** OFFLINE CI green. **AC3** tool-call/navigation assertions read from the audit stream.
- **Verify:** `mvn -q -pl eoiagent-eval test`

### T-116 · Reference Application Pack + demo through platform
- **Module:** `eoiagent-app-reference` · **Spec:** [reference-app-pack](../specs/reference-app-pack.md), [application-pack](../specs/application-pack.md) · **Depends-on:** T-114, T-010, T-115
- Implement `ReferenceApplicationPack` (all 8 providers, OFFLINE profile) and wire the MVP demo
  through `PlatformBuilder.pack(new ReferenceApplicationPack()).start()`. Ship its own golden set.
- **AC1** the assembled `AgentService` answers a sample Q&A and returns a valid `NavigationIntent`
  to a catalog page, offline. **AC2** pack depends on `eoiagent-app-api` + `eoiagent-bom` only
  (ArchUnit). **AC3** the reference golden set passes in OFFLINE CI. **AC4** serves as the
  copy-to-start template for a real product pack.
- **Verify:** `mvn -q -pl eoiagent-app-reference test`

---

## Phase 2 — Planning + mutating actions + delegation

| ID | Title | Module | Spec | Depends-on | Key ACs |
|----|-------|--------|------|-----------|---------|
| T-201 | Planner + TaskManager (plan/todo) | runtime | [orchestration-runtime](../specs/orchestration-runtime.md) | T-111 | Plan with mutating flags; TaskList status transitions; plan revise-on-observation |
| T-202 | ApprovalGate + dry-run + ApprovalHandler SPI | safety | [approval-governance](../specs/approval-governance.md), [ADR-0008](../adr/0008-mutating-actions-approval-gate-dryrun.md) | T-003 | request() blocks for decision; dryRun() previews; APPROVED/DENIED/TIMED_OUT audited |
| T-203 | RoleBasedPolicyEngine + mutating dispatch | safety, tool | [approval-governance](../specs/approval-governance.md), [tool-registry](../specs/tool-registry.md) | T-110, T-202 | RBAC by Role×Capability×Profile; **no mutating ToolResult without prior APPROVED audit** |
| T-204 | Mutating Java-API tools | tool | [tool-registry](../specs/tool-registry.md) | T-203 | pipeline author/run, config edit, job trigger — all gated; dry-run supported |
| T-205 | Supervisor + sub-agents (Analysis/SQL/Pipeline) | runtime | [orchestration-runtime](../specs/orchestration-runtime.md) | T-201, T-204 | Flow D delegation + result aggregation; isolated worker contexts/scratchpads |
| T-206 | pgvector + JDBC audit + Postgres memory | knowledge, observability, memory | [rag-knowledge](../specs/rag-knowledge.md), [audit-observability](../specs/audit-observability.md), [memory](../specs/memory.md) | T-104, T-113, T-108 | `PgVectorStore` (≥1.16.3); append-only audit table; PG-backed memory; feature-gated |
| T-207 | Summarizing chat memory | memory | [memory](../specs/memory.md) | T-108 | condenses old turns via model instead of dropping |
| T-208 | Advanced retrieval | knowledge | [rag-knowledge](../specs/rag-knowledge.md) | T-104 | query rewrite/route/re-rank improves eval recall |
| T-209 | MCP tool adapter | tool | [tool-registry](../specs/tool-registry.md) | T-110 | `McpToolAdapter` (stdio local; HTTP gated by profile) |
| T-210 | Output guardrails | safety | [guardrails](../specs/guardrails.md) | T-112 | schema/validation; RETRY on violation; strips offending message |
| T-211 | Eval expansion (SQL/analysis/mutating) | eval | [eval-harness](../specs/eval-harness.md) | T-115, T-205 | SQL-gen + analysis + mutating-with-approval scenarios green |

---

## Phase 3 — Stateful investigation + durability

| ID | Title | Module | Spec | Depends-on | Key ACs |
|----|-------|--------|------|-----------|---------|
| T-301 | LangGraphOrchestrator behind Orchestrator port | runtime | [orchestration-runtime](../specs/orchestration-runtime.md), [ADR-0005](../adr/0005-orchestration-agentic-then-langgraph4j.md) | T-205 | `org.bsc.langgraph4j:*:1.8.19`; cyclical graph; quarantined in adapter |
| T-302 | CheckpointStore: InMemory + Postgres | persistence | [orchestration-runtime](../specs/orchestration-runtime.md) (§checkpointing) | T-301 | save/latest/history; resume after restart |
| T-303 | Breakpoints + HITL + time-travel (Flow E) | runtime | [orchestration-runtime](../specs/orchestration-runtime.md) | T-302, T-202 | pause at mutating/escalation node for approval; replay from history |
| T-304 | Investigation tools + playbooks | tool | [tool-registry](../specs/tool-registry.md) | T-204 | events/alerts/incidents/cases Java-API tools; root-cause playbook |
| T-305 | VectorLongTermMemory (cross-session) | memory | [memory](../specs/memory.md) | T-206 | remember/recall across sessions via RAG |
| T-306 | Eval: investigation + resume-after-restart | eval | [eval-harness](../specs/eval-harness.md) | T-303, T-211 | investigation scenario; restart→resume asserted |

---

## Phase 4 — Hardening

| ID | Title | Module | Spec | Depends-on | Key ACs |
|----|-------|--------|------|-----------|---------|
| T-401 | OpenTelemetry tracing | observability | [audit-observability](../specs/audit-observability.md) | T-113 | spans across runtime; `NoopTraceCollector` default |
| T-402 | Performance pass | (cross) | [eval-harness](../specs/eval-harness.md) | T-115 | latency budgets met for interactive Q&A; embedding/retrieval tuned |
| T-403 | Security review + offline network-deny test | safety, (cross) | [guardrails](../specs/guardrails.md), [03-deployment-profiles](../architecture/03-deployment-profiles.md) | T-210, T-303 | prompt-injection red-team; **OFFLINE proves zero egress**; audit completeness |
| T-404 | Packaging | (root) | [conventions](../conventions.md) | T-303 | shaded uber-jar / JPMS `module-info` as host requires |
| T-405 | Eval hardening + CI gates | eval | [eval-harness](../specs/eval-harness.md) | T-306 | regression baseline; CI gates across all profiles |

---

## Dependency snapshot (phase entry points)

```
T-001 → T-002 → T-003 → {T-004, T-005, T-006, T-007, T-009} → T-008      (Phase 0)
                         T-009 → T-010 (platform bootstrap)
Phase 1 fans out from T-003/T-007; converges at T-114 → T-115 → T-116 (reference pack)
Phase 2 builds on T-111 (planning) + T-110/T-202 (gated tools)
Phase 3 builds on T-205 (delegation) → T-301 (LangGraph4j)
Phase 4 hardens everything; gated by T-306
New product onboarding = copy T-116 reference pack → fill 8 providers (separate pack track)
```
