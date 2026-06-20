# 01 — Component Model (Ports & Adapters)

> Canonical list of the **11 components**, their **port interfaces**, and the **adapters** that
> implement them. Port signatures here are the contract every `docs/specs/*` file expands on.
> Domain types referenced below are defined in [`02-domain-model.md`](02-domain-model.md).

## How to read this

- A **port** is a Java interface in a `*-api` (or `core`) module. It is **stable**; changing it
  is an ADR-worthy event.
- An **adapter** implements a port in a `*-<adapter>` module and may depend on a third-party
  library. Adapters are the *only* place experimental deps appear.
- **Dependency rule:** adapters depend on ports; ports never depend on adapters; the host
  depends only on the host-integration API + `core`. See [`../conventions.md`](../conventions.md).
- **All 11 components below are CORE (reusable).** Their *inputs* — which model, which corpus,
  which tools, which pages, which prompts/roles/config — are supplied per-product by the
  **Application Pack** through a typed SPI (see [§Application Pack SPI & bootstrap](#application-pack-spi--platform-bootstrap-the-reuse-layer)
  and [`05-core-and-application-packs.md`](05-core-and-application-packs.md)). CORE never depends
  on a pack.

Signatures are illustrative Java (final API may add overloads/builders). Package root is
`com.eoiagent` (see conventions).

---

## Component 1 — Model Access  · port `LlmGateway`

Unified access to chat + embedding models with local/hosted routing and fallback. Spec:
[`../specs/model-gateway.md`](../specs/model-gateway.md).

```java
package com.eoiagent.model;

public interface LlmGateway {
    ChatResult chat(ChatRequest request);                 // blocking
    void chatStream(ChatRequest request, TokenSink sink); // streaming tokens
    EmbeddingResult embed(EmbeddingRequest request);
    ModelInfo activeChatModel();                           // which model answered (audit)
    boolean isAvailable(ModelRole role);                   // CHAT / EMBEDDING
}
```

| Adapter | Library | Use |
|---------|---------|-----|
| `OllamaChatAdapter` / `OllamaEmbeddingAdapter` | `langchain4j-ollama` | local |
| `OpenAiCompatibleChatAdapter` | `langchain4j-open-ai` (`baseUrl`) | llama.cpp / vLLM / LM Studio / Ollama `/v1` |
| `AnthropicChatAdapter` | `langchain4j-anthropic` | hosted |
| `GeminiChatAdapter` | `langchain4j-google-ai-gemini` | hosted |
| `RoutingLlmGateway` | (ours) | picks/falls back per `DeploymentProfile` |

All HTTP adapters use the **JDK `HttpClient`** transport (see [ADR-0002](../adr/0002-jdk25-maven-httpclient.md)).

---

## Component 2 — Knowledge / RAG  · ports `EmbeddingModel`, `VectorStore`, `Retriever`, `DocumentIngestor`

Ingest + retrieve over product docs, pipeline/job config files, schema/data-model configs.
Spec: [`../specs/rag-knowledge.md`](../specs/rag-knowledge.md).

```java
package com.eoiagent.knowledge;

public interface DocumentIngestor {            // load → split → embed → store
    IngestReport ingest(IngestRequest request);
}
public interface Retriever {
    List<RetrievedChunk> retrieve(RetrievalQuery query);  // top-k + filters
}
public interface VectorStore {                 // thin wrap over LC4j EmbeddingStore
    void add(List<EmbeddedChunk> chunks);
    List<Match> search(float[] queryVector, int k, MetadataFilter filter);
}
// EmbeddingModel: reuse LangChain4j dev.langchain4j.model.embedding.EmbeddingModel
```

| Adapter | Library | Use |
|---------|---------|-----|
| `OnnxEmbeddingAdapter` (`AllMiniLmL6V2`) | `langchain4j-embeddings-all-minilm-l6-v2` | **offline**, in-JVM |
| `InMemoryVectorStore` | `langchain4j` core | embedded, disk save/load |
| `PgVectorStore` | `langchain4j-pgvector` (≥1.16.3) | prod |
| loaders: `ProductDocLoader`, `ConfigFileLoader`, `SchemaConfigLoader` | LC4j loaders/splitters | corpus |
| `AdvancedRetriever` (rewrite/route/re-rank) | LC4j RAG building blocks | **deferred → Phase 2** |

---

## Component 3 — Tools  · ports `Tool`, `ToolRegistry`

Expose the host's Java API as agent tools; call external tools via MCP. Spec:
[`../specs/tool-registry.md`](../specs/tool-registry.md).

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

- Every tool declares `mutating` (boolean) and `requiredRole`. Mutating tools route through the
  `ApprovalGate` (Component 7) inside `dispatch`.
- Adapters: `JavaApiTool` (wraps `@Tool`-annotated host methods via LC4j AI Services),
  `McpToolAdapter` (`langchain4j-mcp`).

---

## Component 4 — Agent Runtime / Orchestration  · ports `Planner`, `Orchestrator`, `TaskManager`

The ReAct loop + workflow patterns + planning. Spec:
[`../specs/orchestration-runtime.md`](../specs/orchestration-runtime.md).

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

| Adapter | Library | Phase |
|---------|---------|-------|
| `AgenticOrchestrator` | `langchain4j-agentic` (experimental) | **MVP** — sequential/parallel/conditional/loop/supervisor |
| `LangGraphOrchestrator` | `org.bsc.langgraph4j` | **Phase 3** — cyclical, checkpointed, HITL, time-travel |
| `ReActOrchestrator` | LC4j tools + our loop | fallback / simple path |

Sub-agents (supervisor + isolated workers: analysis / SQL / pipeline) are nested
`Orchestrator`/AI-Service invocations; see spec.

---

## Component 5 — Memory  · ports `ChatMemory`, `MemoryStore`

Short-term windowed/summarized memory + persistence + (later) long-term cross-session memory.
Spec: [`../specs/memory.md`](../specs/memory.md).

```java
package com.eoiagent.memory;

public interface MemoryStore {                 // persistence behind LC4j ChatMemoryStore
    void put(SessionId id, List<ChatMessageRecord> messages);
    List<ChatMessageRecord> get(SessionId id);
    void delete(SessionId id);
}
public interface LongTermMemory {              // Phase 3
    void remember(SessionId scope, MemoryFact fact);
    List<MemoryFact> recall(String query, int k);
}
```

| Adapter | Library | Phase |
|---------|---------|-------|
| `WindowChatMemory` / `TokenWindowChatMemory` | LC4j `MessageWindowChatMemory` / `TokenWindowChatMemory` | MVP |
| `SummarizingChatMemory` | LC4j summarization eviction | Phase 2 |
| `InMemoryMemoryStore`, `FileMemoryStore`, `PostgresMemoryStore` | ours / JDBC | MVP→Phase 2 |
| `VectorLongTermMemory` | reuses Knowledge/RAG | Phase 3 |

---

## Component 6 — Scratchpad / Virtual FS  · port `Scratchpad`

Context offloading so the window doesn't blow up. Spec:
[`../specs/scratchpad.md`](../specs/scratchpad.md).

```java
package com.eoiagent.scratchpad;

public interface Scratchpad {
    String write(String key, String content);   // returns a handle/ref
    String read(String key);
    List<String> list(String prefix);
    void delete(String key);
}
```

Adapters: `InMemoryScratchpad` (Map), `TempDirScratchpad` (local temp dir, profile-gated).

---

## Component 7 — Safety / Governance  · ports `ApprovalGate`, `Guardrail`, `PolicyEngine`

Human-in-the-loop for mutating actions, I/O guardrails, RBAC. Specs:
[`../specs/approval-governance.md`](../specs/approval-governance.md),
[`../specs/guardrails.md`](../specs/guardrails.md).

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
public interface Guardrail {                         // both input + output
    GuardrailResult check(GuardrailInput in);        // pass | fail | redacted | retry
}
```

| Adapter | Library | Phase |
|---------|---------|-------|
| `CallbackApprovalGate` | ours (host supplies an `ApprovalHandler`) | Phase 2 |
| `RoleBasedPolicyEngine` | ours | Phase 2 |
| `Lc4jInputGuardrail` / `Lc4jOutputGuardrail` (prompt-injection, PII, schema) | `langchain4j-guardrails` (experimental) | MVP (input) → Phase 2 (output) |

---

## Component 8 — Persistence / Checkpointing  · port `CheckpointStore`

Make long-running tasks resumable across restart. Spec: covered in
[`../specs/orchestration-runtime.md`](../specs/orchestration-runtime.md) (§ checkpointing).

```java
package com.eoiagent.persistence;

public interface CheckpointStore {
    void save(RunId id, Checkpoint cp);
    Optional<Checkpoint> latest(RunId id);
    List<Checkpoint> history(RunId id);   // enables time-travel/replay
}
```

Adapters: `InMemoryCheckpointStore` (MVP), `PostgresCheckpointStore` (Phase 3, backed by
LangGraph4j Postgres saver when `LangGraphOrchestrator` is active).

---

## Component 9 — Observability / Audit  · ports `AuditSink`, `TraceCollector`

Persisted audit trail + traces. Spec: [`../specs/audit-observability.md`](../specs/audit-observability.md).

```java
package com.eoiagent.observability;

public interface AuditSink {
    void record(AuditEvent event);   // decision | tool-call | action | approval | model-call
}
public interface TraceCollector {
    Span start(String name, Map<String,Object> attrs);
    void end(Span span, SpanStatus status);
}
```

Adapters: `Slf4jAuditSink`, `JdbcAuditSink` (append-only table), `FileAuditSink`;
`NoopTraceCollector`, `OpenTelemetryTraceCollector` (optional). **Every mutating action and
model call MUST emit an `AuditEvent` (C5).**

---

## Component 10 — Host Integration  · ports `AgentService`, `AgentSession`

The "embedded under every page" surface. Spec:
[`../specs/host-integration.md`](../specs/host-integration.md).

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

- `AgentAnswer` carries one of: inline text, inline data/chart (`InlineArtifact`), or a
  **`NavigationIntent`** (target KPI/report page + parameters) — the primary product behavior.
- `PageContext` (current page, entity ids, filters) flows in on every `ask`.

---

## Component 11 — Configuration / Deployment Profiles  · port `ConfigProvider`

Profiles + per-profile feature/capability gating. Spec:
[`../specs/config-profiles.md`](../specs/config-profiles.md). Profiles and the capability
matrix are in [`03-deployment-profiles.md`](03-deployment-profiles.md).

```java
package com.eoiagent.config;

public interface ConfigProvider {
    DeploymentProfile profile();
    <T> T get(ConfigKey<T> key);
    boolean featureEnabled(Feature feature);   // gated by profile capability matrix
}
```

Adapters: `EnvConfigProvider`, `PropertiesConfigProvider`, `ProgrammaticConfigProvider`.

---

## Application Pack SPI & Platform bootstrap (the reuse layer)

The product-specific layer that feeds the 11 CORE components. Spec:
[`../specs/application-pack.md`](../specs/application-pack.md). Concept:
[`05-core-and-application-packs.md`](05-core-and-application-packs.md).

- **`ApplicationPack` SPI** (`com.eoiagent.app`, module `eoiagent-app-api`) — a product implements
  this and returns eight providers (`PackMetadata`, `ModelProfile`, `KnowledgeSource[]`,
  `ToolProvider`, `NavigationCatalog`, `PromptProfile`, `PolicyProfile`, `PackConfig`). Signatures
  in [`02-domain-model.md` §Application Pack SPI](02-domain-model.md#application-pack-spi--comeoiagentapp).
  This is **not** a core port (core never calls into a product); it is the contract the *product*
  fulfils.
- **`AgentPlatform` / `PlatformBuilder`** (`com.eoiagent.platform`, module `eoiagent-platform`) —
  CORE assembly: validates a pack, builds the gateway/RAG/tools/safety/runtime from it, ingests
  the corpus, and returns a ready `AgentService` (Component 10). One pack per deployment.

```
   ApplicationPack (product) ──► eoiagent-app-api (SPI) ──► consumed by eoiagent-platform
   eoiagent-platform ──► builds Components 1–11 ──► AgentService
```

---

## Dependency direction (must hold)

```
host app ──► eoiagent-platform ──► core (ports + domain types)
                  ▲                    ▲
   Application ───┘ (implements)       └── every adapter module (adapters import core;
   Pack  ───────► eoiagent-app-api ────┘      core imports no adapter; core imports no pack)
experimental deps (agentic, guardrails, langgraph4j, pgvector) live ONLY in core adapter modules
(a pack never sees them); the Pack depends on eoiagent-app-api + the BOM only.
```

## Module map (Maven artifacts)

See [`02-domain-model.md` §Maven coordinates](02-domain-model.md#maven-coordinates) for exact
group/artifact/version. One Maven module per component grouping; ports in `*-api`/`core`,
adapters in `*-<name>`.
