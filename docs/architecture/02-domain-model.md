---
type: architecture
title: "02 — Domain Model & Maven Coordinates"
description: "The canonical vocabulary."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["domain-model"]
---
# 02 — Domain Model & Maven Coordinates

> The canonical vocabulary. Every spec, ADR, and ticket uses **these exact type names, package
> names, config keys, and Maven coordinates**. If something here changes, it changes everywhere.

> **Two-part structure** (see [05-core-and-application-packs.md](05-core-and-application-packs.md)):
> everything below in `com.eoiagent.*` **except** `com.eoiagent.app.*` is **CORE** — the reusable
> platform, product-agnostic. A product supplies its specifics through an **Application Pack**
> (`com.eoiagent.app` SPI), and the platform is assembled by `com.eoiagent.platform`.

## Package roots

```
CORE (reusable platform — knows nothing about any specific product)
com.eoiagent                      root
com.eoiagent.core                 ports + domain types (no third-party agent deps)
com.eoiagent.model                Model Access port + adapters
com.eoiagent.knowledge            RAG ports + adapters
com.eoiagent.tool                 Tool ports + adapters
com.eoiagent.runtime              Orchestration ports + adapters
com.eoiagent.memory               Memory ports + adapters
com.eoiagent.scratchpad           Scratchpad port + adapters
com.eoiagent.safety               ApprovalGate / Guardrail / PolicyEngine
com.eoiagent.persistence          CheckpointStore
com.eoiagent.observability        AuditSink / TraceCollector
com.eoiagent.host                 Host integration API
com.eoiagent.config               Config / profiles
com.eoiagent.app                  Application Pack SPI (interfaces a product implements)
com.eoiagent.platform             Bootstrap/assembly (AgentPlatform, PlatformBuilder)

PROJECT-SPECIFIC (one per product — implements the com.eoiagent.app SPI)
com.<product>...                  the product's Application Pack (any groupId/package the product owns)
com.eoiagent.app.reference        the bundled reference Application Pack (copy-to-start template)
```

## Core domain types

These live in `com.eoiagent.core` (or the owning component package) and are shared across
modules. Prefer **Java `record`s** for value types; enums for closed sets.

### Identity & context
```java
record AppId(String value) {}                         // which Application Pack is running
record SessionId(String value) {}
record RunId(String value) {}
record TaskId(String value) {}
record UserId(String value) {}

enum Role { ADMIN, SUPPORT, ANALYST, USER }          // RBAC tiers (brainstorm: product user levels)

record AgentContext(                                  // threaded through every call
    AppId app, SessionId session, UserId user, Role role,
    DeploymentProfile profile, PageContext page,
    Map<String,String> attributes) {}                 // app is constant per deployment (one pack)
```

### Conversation & answers
```java
record UserMessage(String text, PageContext page, Instant at) {}
enum AnswerKind { TEXT, INLINE_ARTIFACT, NAVIGATION, CLARIFICATION, ERROR }

record AgentAnswer(
    AnswerKind kind,
    String text,                         // for TEXT / CLARIFICATION / ERROR
    InlineArtifact artifact,             // nullable: chart/table/data for INLINE_ARTIFACT
    NavigationIntent navigation,         // nullable: for NAVIGATION
    List<Citation> citations,            // RAG provenance
    RunId run) {}

record InlineArtifact(String mimeType, String title, byte[] data, Map<String,Object> meta) {}
record Citation(String sourceId, String title, String locator) {}
```

### NavigationIntent — the signature product behavior
The agent usually answers a help question by **routing the user to the correct KPI/report page
with the right parameters**, optionally with a short inline answer first.
```java
record NavigationIntent(
    String targetPageId,                 // host-defined page/route id
    Map<String,String> parameters,       // pre-filled filters/params for that page
    String rationale) {}                 // why this page (shown to user / audited)
```

### Page context (in on every ask)
```java
record PageContext(
    String pageId,
    Map<String,String> entityIds,        // e.g. pipelineId, datasetId, incidentId
    Map<String,String> filters) {}
```

### Planning & tasks
```java
record Goal(String text, GoalKind kind) {}
enum GoalKind { QA, ANALYSIS, SQL_GEN, PIPELINE_AUTHOR, INVESTIGATION, OPERATIONAL_ACTION }

record Plan(List<PlanStep> steps, String summary) {}
record PlanStep(TaskId id, String description, boolean mutating) {}

enum TaskStatus { PENDING, IN_PROGRESS, BLOCKED, DONE, FAILED, NEEDS_APPROVAL }
record Task(TaskId id, String description, TaskStatus status, String note) {}
record TaskList(List<Task> tasks) {}
```

### Tools
```java
record ToolSpec(
    String name, String description, String jsonSchema,
    boolean mutating, Role requiredRole, Capability capability) {}
record ToolCall(String toolName, Map<String,Object> arguments, RunId run) {}
record ToolResult(boolean ok, Object value, String error, Map<String,Object> meta) {}
```

### Models
```java
enum ModelRole { CHAT, EMBEDDING }
record ModelInfo(String provider, String modelId, boolean local) {}
record ChatRequest(List<ChatMessageRecord> messages, List<ToolSpec> tools, ChatOptions options) {}
record ChatResult(String text, List<ToolCall> toolCalls, ModelInfo model, Usage usage) {}
record EmbeddingRequest(List<String> inputs) {}
record EmbeddingResult(List<float[]> vectors, ModelInfo model) {}
```

### Knowledge / RAG
```java
record IngestRequest(List<DocumentSource> sources, IngestOptions options) {}
record IngestReport(int documents, int chunks, List<String> warnings) {}
record RetrievalQuery(String text, int k, MetadataFilter filter) {}
record RetrievedChunk(String text, double score, Citation citation) {}
```

### Safety & governance
```java
enum Capability { READ_METADATA, READ_SCHEMA, READ_DOCS, RUN_SQL_READONLY,
                  GENERATE_SQL, AUTHOR_PIPELINE, RUN_PIPELINE, EDIT_CONFIG,
                  WRITE_DATASTORE, TRIGGER_JOB, INVESTIGATE }

record ApprovalRequest(RunId run, ToolCall call, String humanSummary, DryRunResult preview) {}
enum ApprovalDecision { APPROVED, DENIED, TIMED_OUT }
record DryRunResult(boolean supported, String preview, Map<String,Object> predictedEffects) {}

enum GuardrailVerdict { PASS, FAIL, REDACTED, RETRY }
record GuardrailResult(GuardrailVerdict verdict, String message, String transformedText) {}
```

### Observability / audit
```java
enum AuditKind { MODEL_CALL, TOOL_CALL, DECISION, APPROVAL, MUTATION, RETRIEVAL, ERROR }
record AuditEvent(
    Instant at, AppId app, RunId run, SessionId session, UserId user,
    AuditKind kind, String summary, Map<String,Object> details) {}   // append-only
```

### Config / profiles
```java
enum DeploymentProfile { OFFLINE, ON_PREM_HOSTED, CLOUD }
enum Feature { HOSTED_MODELS, MUTATING_ACTIONS, MCP_TOOLS, PGVECTOR,
               LANGGRAPH_CHECKPOINTING, ADVANCED_RETRIEVAL, LONG_TERM_MEMORY }
record ConfigKey<T>(String name, Class<T> type, T defaultValue) {}
```

### Application Pack SPI  (`com.eoiagent.app`)
> The project-specific contract. A product implements `ApplicationPack` (and the providers it
> returns) to instantiate the agent for its domain. CORE consumes these — never the reverse.
> Full spec: [`../specs/application-pack.md`](../specs/application-pack.md).
```java
interface ApplicationPack {                  // the root SPI a product implements
    PackMetadata    metadata();
    ModelProfile    modelProfile();           // which models/endpoints + routing
    List<KnowledgeSource> knowledgeSources(); // domain corpus to ingest
    ToolProvider    toolProvider();           // host Java-API tools + MCP servers
    NavigationCatalog navigationCatalog();    // pages/KPI routes for NavigationIntent
    PromptProfile   promptProfile();          // system prompts / persona / domain glossary
    PolicyProfile   policyProfile();          // host-role → Role + capability grants
    PackConfig      config();                 // profile + feature overrides + config defaults
}

record PackMetadata(AppId appId, String name, String version) {}

interface ModelProfile {
    ModelSelection chat();
    ModelSelection embedding();
    RoutingPolicy  routing();                 // local/hosted preference + fallback
}
record ModelSelection(String provider, String modelId, String baseUrl, boolean local) {}
record RoutingPolicy(List<String> order, boolean allowHostedFallback) {}

interface KnowledgeSource {                    // drives DocumentIngestor
    String id();
    SourceKind kind();                         // PRODUCT_DOC | CONFIG_FILE | SCHEMA_CONFIG | CUSTOM
    IngestOptions options();
    List<DocumentSource> resolve();            // where the documents come from
}
enum SourceKind { PRODUCT_DOC, CONFIG_FILE, SCHEMA_CONFIG, CUSTOM }

interface ToolProvider {                        // drives ToolRegistry
    List<Tool> tools();                         // host @Tool-backed tools
    List<McpServerRef> mcpServers();            // external MCP tool servers (may be empty)
}
record McpServerRef(String id, String transport, String target) {}

interface NavigationCatalog {                   // drives NavigationIntent targeting
    List<PageDescriptor> pages();
    Optional<PageDescriptor> find(String pageId);
}
record PageDescriptor(String pageId, String title, String description, List<ParamSpec> params) {}
record ParamSpec(String name, String type, boolean required, String description) {}

interface PromptProfile {
    String systemPrompt(GoalKind kind);         // domain system prompt per goal kind
    String persona();
    Map<String,String> domainGlossary();        // domain terms the model should know
}

interface PolicyProfile {
    Role mapRole(String hostRole);              // product roles → platform Role
    Set<Capability> grants(Role role);          // capabilities allowed per role
}

interface PackConfig {
    DeploymentProfile profile();
    Map<Feature,Boolean> featureOverrides();    // within what the profile matrix permits
    Map<String,String> configDefaults();        // eoiagent.* defaults this pack ships
}
```

### Platform bootstrap  (`com.eoiagent.platform`)
> CORE assembly: takes an `ApplicationPack`, wires the core adapters it selects, ingests the
> pack's knowledge, registers its tools/navigation, and returns a ready `AgentService`.
```java
interface AgentPlatform extends AutoCloseable {
    AgentService agentService();
    PackMetadata pack();
}
final class PlatformBuilder {
    PlatformBuilder pack(ApplicationPack pack);          // required
    PlatformBuilder configProvider(ConfigProvider cfg);  // optional override
    PlatformBuilder auditSink(AuditSink sink);           // optional override (else pack/profile default)
    AgentPlatform start();                                // validate → wire → ingest → ready
}
```

## Config key namespace

All keys are dotted, prefix `eoiagent.`. Examples (full list per spec):
```
eoiagent.profile                       = OFFLINE | ON_PREM_HOSTED | CLOUD
eoiagent.model.chat.provider           = ollama | openai-compatible | anthropic | gemini
eoiagent.model.chat.baseUrl            = http://localhost:11434/v1
eoiagent.model.chat.modelId            = qwen2.5:14b-instruct
eoiagent.model.embedding.provider      = onnx-all-minilm | ollama
eoiagent.vectorstore.kind              = in-memory | pgvector
eoiagent.memory.kind                   = window | token-window | summarizing
eoiagent.approval.required             = true
eoiagent.audit.sink                    = slf4j | jdbc | file
eoiagent.app.id                        = <set by the Application Pack>
```

## Maven coordinates

**CORE — the reusable platform** (`groupId` `com.eoiagent`, version `0.1.0-SNAPSHOT`), one
module per grouping. A product depends on these but does **not** modify them:

```
com.eoiagent:eoiagent-bom            (our BOM; imports langchain4j-bom; also pins the app-api)
com.eoiagent:eoiagent-core           (ports + domain types)
com.eoiagent:eoiagent-model
com.eoiagent:eoiagent-knowledge
com.eoiagent:eoiagent-tool
com.eoiagent:eoiagent-runtime
com.eoiagent:eoiagent-memory
com.eoiagent:eoiagent-scratchpad
com.eoiagent:eoiagent-safety
com.eoiagent:eoiagent-persistence
com.eoiagent:eoiagent-observability
com.eoiagent:eoiagent-host
com.eoiagent:eoiagent-config
com.eoiagent:eoiagent-app-api        (Application Pack SPI — com.eoiagent.app)
com.eoiagent:eoiagent-platform       (bootstrap/assembly — AgentPlatform, PlatformBuilder)
com.eoiagent:eoiagent-eval           (eval harness; reusable, pack supplies the golden cases)
```

**PROJECT-SPECIFIC — one Application Pack per product** (the product owns the groupId):

```
com.eoiagent:eoiagent-app-reference  (bundled reference pack / copy-to-start template)
<product-groupId>:<product>-agent-pack   (each product's own Application Pack, depends on
                                          eoiagent-app-api + eoiagent-bom only)
```
A deployment = CORE jars + exactly **one** Application Pack on the classpath, assembled by
`eoiagent-platform`. See [`05-core-and-application-packs.md`](05-core-and-application-packs.md).

**Third-party (pin via BOMs — never hardcode versions in module poms):**

| Dependency | Coordinates | Version | Notes |
|------------|-------------|---------|-------|
| LangChain4j BOM | `dev.langchain4j:langchain4j-bom` | **1.16.3** | import-scope; aligns all LC4j artifacts |
| LangChain4j core | `dev.langchain4j:langchain4j` | (BOM) | AI Services, tools, in-memory store |
| Ollama | `dev.langchain4j:langchain4j-ollama` | (BOM) | local chat + embeddings |
| OpenAI-compatible | `dev.langchain4j:langchain4j-open-ai` | (BOM) | `baseUrl` for llama.cpp/vLLM/LM Studio |
| Anthropic | `dev.langchain4j:langchain4j-anthropic` | (BOM) | hosted |
| Gemini | `dev.langchain4j:langchain4j-google-ai-gemini` | (BOM) | hosted |
| ONNX embeddings | `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2` | (BOM) | in-process, offline |
| pgvector | `dev.langchain4j:langchain4j-pgvector` | (BOM) | **must be ≥1.16.3** (CVE fix) |
| MCP client | `dev.langchain4j:langchain4j-mcp` | (BOM) | external tools |
| Agentic (experimental) | `dev.langchain4j:langchain4j-agentic` | `1.16.x-betaNN` | resolve suffix at build time; adapter-only |
| Guardrails (experimental) | `dev.langchain4j:langchain4j-guardrails` | `1.16.x-betaNN` | adapter-only |
| LangGraph4j core | `org.bsc.langgraph4j:langgraph4j-core` | **1.8.19** | Phase 3; adapter-only |
| LangGraph4j LC4j bridge | `org.bsc.langgraph4j:langgraph4j-langchain4j` | 1.8.19 | Phase 3 |
| LangGraph4j Postgres saver | `org.bsc.langgraph4j:langgraph4j-checkpoint-postgres` | 1.8.19 | Phase 3 (confirm exact artifact id at adoption) |

> **Version policy:** every LangChain4j artifact version comes from `langchain4j-bom:1.16.3`.
> The two experimental modules ship `-betaNN` suffixes per release; resolve the suffix at build
> time and pin it in the `eoiagent-bom`. LangGraph4j is pinned directly at `1.8.19`. Do not mix
> LC4j versions.

## JDK & build
- **JDK 25**, **Maven** (3.9+). `maven.compiler.release = 25`.
- HTTP: prefer the **JDK `HttpClient`**-based LC4j client; avoid Netty-based transports on JDK
  25 (see [ADR-0002](../adr/0002-jdk25-maven-httpclient.md)).
