---
type: spec
title: "Reference Application Pack — Spec"
description: "A worked, copy-to-start `ApplicationPack` for a fictional \"Acme Lakehouse Suite\", runnable fully offline."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["reference-app-pack"]
---
# Reference Application Pack — Spec

> A worked, copy-to-start `ApplicationPack` for a fictional **"Acme Lakehouse Suite"**, runnable
> fully offline. Implements the SPI from [application-pack.md](application-pack.md); concept in
> [05-core-and-application-packs.md](../architecture/05-core-and-application-packs.md).
> Types: [02-domain-model.md §Application Pack SPI](../architecture/02-domain-model.md#application-pack-spi--comeoiagentapp).
> Decision: [ADR-0011](../adr/0011-core-and-application-pack-split.md). Module:
> `com.eoiagent:eoiagent-app-reference` (`com.eoiagent.app.reference`).

## Purpose

Give an AI coding agent (or a human) a **complete, compiling, passing** example of every part of
the Application Pack SPI, so onboarding a real product is *copy this module → rename → replace the
sample content* (the workflow in [05 §7](../architecture/05-core-and-application-packs.md#7-onboarding-a-new-product-the-agent-workflow)).
It is the canonical answer to "what does a filled-in pack look like?". It ships **realistic but
generic** content for Acme Lakehouse Suite (datasets, ETL pipelines, KPI dashboards) and runs on
the **OFFLINE** profile so the demo needs no network, no API keys, and no external services.

The reference pack is **not** a product: it depends on `eoiagent-app-api` + `eoiagent-bom` **only**
(never on core adapters or experimental libs — [05 §6](../architecture/05-core-and-application-packs.md#6-dependency-direction-updated)),
and its golden set proves the assembled `AgentService` answers offline.

## Port interface(s)

The pack *implements* the SPI (it defines no new ports). Class skeleton + each provider sketch,
using the exact SPI types from the domain model:

```java
package com.eoiagent.app.reference;

import com.eoiagent.app.*;          // SPI interfaces + records (eoiagent-app-api)
import com.eoiagent.core.*;         // domain types

public final class ReferenceApplicationPack implements ApplicationPack {
    public PackMetadata          metadata()        { return new PackMetadata(
            new AppId("acme-lakehouse"), "Acme Lakehouse Suite", "0.1.0"); }
    public ModelProfile          modelProfile()    { return new ReferenceModelProfile(); }
    public List<KnowledgeSource> knowledgeSources(){ return List.of(
            new ProductDocSource(), new SchemaConfigSource(), new PipelineConfigSource()); }
    public ToolProvider          toolProvider()    { return new ReferenceToolProvider(); }
    public NavigationCatalog     navigationCatalog(){ return new ReferenceNavigationCatalog(); }
    public PromptProfile         promptProfile()   { return new ReferencePromptProfile(); }
    public PolicyProfile         policyProfile()   { return new ReferencePolicyProfile(); }
    public PackConfig            config()          { return new ReferencePackConfig(); }
}
```

```java
// ModelProfile — OFFLINE: local chat via Ollama, in-JVM ONNX embeddings, no hosted fallback.
final class ReferenceModelProfile implements ModelProfile {
    public ModelSelection chat() {
        return new ModelSelection("ollama", "qwen2.5:14b-instruct",
                                  "http://localhost:11434/v1", /*local=*/true); }
    public ModelSelection embedding() {
        return new ModelSelection("onnx-all-minilm", "all-MiniLM-L6-v2", null, true); }
    public RoutingPolicy routing() {
        return new RoutingPolicy(List.of("ollama"), /*allowHostedFallback=*/false); }  // OFFLINE
}
```

```java
// KnowledgeSource — one per SourceKind the corpus uses (no dynamic data — that is tools).
final class SchemaConfigSource implements KnowledgeSource {
    public String     id()      { return "acme-schemas"; }
    public SourceKind kind()    { return SourceKind.SCHEMA_CONFIG; }
    public IngestOptions options() { return IngestOptions.defaults(); }   // 1000/200, idempotent
    public List<DocumentSource> resolve() {                               // explicit file URIs (no dir factory)
        return List.of(
            new DocumentSource("/acme/schemas/orders.yaml",    "application/yaml", Map.of("title", "orders schema")),
            new DocumentSource("/acme/schemas/customers.yaml", "application/yaml", Map.of("title", "customers schema")));
    }
}   // ProductDocSource → PRODUCT_DOC (/acme/docs/*.md); PipelineConfigSource → CONFIG_FILE (/acme/pipelines/*.yaml)
```

```java
// ToolProvider — three read-only tools that implement the core com.eoiagent.tool.Tool PORT directly.
// The pack depends on eoiagent-app-api + eoiagent-bom ONLY (AC8), so it CANNOT use JavaApiTool — that
// helper lives in the eoiagent-tool ADAPTER module. No MCP servers in OFFLINE.
final class ReferenceToolProvider implements ToolProvider {
    public List<Tool> tools()              { return AcmeReadTools.tools(); }
    public List<McpServerRef> mcpServers() { return List.of(); }          // none offline
}

// AcmeReadTools — builds the three Tools. Each is a CannedTool: a Tool-port impl pairing a fixed
// ToolSpec with a pure function that returns a canned ToolResult, so the demo runs without a live
// lakehouse. An unknown id yields an ok=false ToolResult, never a throw (tool-registry contract).
final class AcmeReadTools {
    static List<Tool> tools() { return List.of(listDatasets(), describeSchema(), getPipelineStatus()); }

    private static Tool listDatasets() {
        ToolSpec spec = new ToolSpec("listDatasets", "List datasets in a lakehouse zone",
            "{\"type\":\"object\",\"properties\":{\"zone\":{\"type\":\"string\"}}}",
            /*mutating=*/false, Role.USER, Capability.READ_METADATA);
        return new CannedTool(spec, call ->
            ok(Map.of("zone", arg(call, "zone", "curated"),
                      "datasets", List.of("orders", "customers", "revenue_daily"))));
    }   // describeSchema → Role.ANALYST/READ_SCHEMA; getPipelineStatus → Role.USER/READ_METADATA

    /** A Tool whose behaviour is a fixed spec plus a pure function over the call. */
    private record CannedTool(ToolSpec spec, Function<ToolCall, ToolResult> body) implements Tool {
        public ToolResult invoke(ToolCall call) { return body.apply(call); }
    }
    private static ToolResult ok(Object v)      { return new ToolResult(true, v, null, Map.of()); }
    private static ToolResult error(String msg) { return new ToolResult(false, null, msg, Map.of()); }
    // arg(call, key, fallback): reads call.arguments().get(key) with a default (see AcmeReadTools.java)
}
```

```java
// NavigationCatalog — the host's routable KPI/report pages (targets for NavigationIntent).
final class ReferenceNavigationCatalog implements NavigationCatalog {
    private static final List<PageDescriptor> PAGES = List.of(
        new PageDescriptor("kpi-dashboard", "KPI Dashboard", "Revenue/usage KPIs by period",
            List.of(new ParamSpec("metric","string",true,"e.g. revenue"),
                    new ParamSpec("period","string",false,"e.g. last-quarter"))),
        new PageDescriptor("pipeline-detail", "Pipeline Detail", "One ETL pipeline's runs",
            List.of(new ParamSpec("pipelineId","string",true,"pipeline id"))),
        new PageDescriptor("incident-detail", "Incident Detail", "A data-quality incident",
            List.of(new ParamSpec("incidentId","string",true,"incident id"))));
    public List<PageDescriptor> pages() { return PAGES; }
    public Optional<PageDescriptor> find(String pageId) {
        return PAGES.stream().filter(p -> p.pageId().equals(pageId)).findFirst(); }
}
```

```java
// PromptProfile — domain persona + per-GoalKind system prompts + glossary the model should know.
final class ReferencePromptProfile implements PromptProfile {
    public String persona() {
        return "You are the Acme Lakehouse assistant embedded in the product."; }
    public String systemPrompt(GoalKind kind) {
        String base = persona() + " Prefer routing the user to an existing KPI/report page "
            + "(emit a NavigationIntent) over re-deriving data inline. Cite sources.";
        return switch (kind) {                                   // total: never null
            case QA, ANALYSIS    -> base;
            case SQL_GEN         -> base + " Generate read-only SQL against Acme schemas only.";
            default              -> base;                         // sensible default for all kinds
        };
    }
    public Map<String,String> domainGlossary() { return Map.of(
        "lakehouse","Unified storage+warehouse layer holding Acme datasets",
        "zone","raw | curated | mart storage tier",
        "pipeline","An ETL job that materializes datasets on a schedule"); }
}
```

```java
// PolicyProfile — Acme host roles → platform Role, and per-Role capability grants.
final class ReferencePolicyProfile implements PolicyProfile {
    public Role mapRole(String hostRole) {
        return switch (hostRole == null ? "" : hostRole.toLowerCase()) {
            case "admin"    -> Role.ADMIN;
            case "engineer" -> Role.ANALYST;     // Acme "engineer" → ANALYST tier
            case "viewer"   -> Role.USER;
            default         -> Role.USER;         // total → least-privileged
        };
    }
    public Set<Capability> grants(Role role) {
        return switch (role) {
            case USER    -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA);
            case ANALYST -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA,
                                   Capability.READ_SCHEMA, Capability.RUN_SQL_READONLY);
            case SUPPORT -> Set.of(Capability.READ_DOCS, Capability.READ_METADATA, Capability.INVESTIGATE);
            case ADMIN   -> EnumSet.allOf(Capability.class);   // full set (mutating still gated by profile)
        };
    }
}
```

```java
// PackConfig — OFFLINE profile; only restricts within the matrix; ships eoiagent.* defaults.
final class ReferencePackConfig implements PackConfig {
    public DeploymentProfile profile() { return DeploymentProfile.OFFLINE; }
    public Map<Feature,Boolean> featureOverrides() {
        return Map.of(Feature.MUTATING_ACTIONS, false, Feature.MCP_TOOLS, false); }   // restrict-only
    public Map<String,String> configDefaults() { return Map.of(
        "eoiagent.profile","OFFLINE",
        "eoiagent.model.chat.provider","ollama",
        "eoiagent.model.chat.baseUrl","http://localhost:11434/v1",
        "eoiagent.model.chat.modelId","qwen2.5:14b-instruct",
        "eoiagent.model.embedding.provider","onnx-all-minilm",
        "eoiagent.vectorstore.kind","in-memory",
        "eoiagent.host.navigation.preferOverInline","true"); }
}
```

## Adapters to build

| Class | Role | Notes |
|-------|------|-------|
| `ReferenceApplicationPack` | root SPI impl | returns the eight providers |
| `ReferenceModelProfile` | `ModelProfile` | OFFLINE: Ollama `qwen2.5` chat + ONNX `all-MiniLM` embedding, no hosted fallback |
| `ProductDocSource` / `SchemaConfigSource` / `PipelineConfigSource` | `KnowledgeSource` | classpath sample corpus (`PRODUCT_DOC` / `SCHEMA_CONFIG` / `CONFIG_FILE`) |
| `ReferenceToolProvider` + `AcmeReadTools` | `ToolProvider` + `Tool`-port impls (`CannedTool`) | 3 read-only tools: `listDatasets`, `describeSchema`, `getPipelineStatus` |
| `ReferenceNavigationCatalog` | `NavigationCatalog` | `kpi-dashboard`, `pipeline-detail`, `incident-detail` |
| `ReferencePromptProfile` | `PromptProfile` | persona + per-`GoalKind` prompts + glossary |
| `ReferencePolicyProfile` | `PolicyProfile` | viewer/engineer/admin → `Role`, capability grants |
| `ReferencePackConfig` | `PackConfig` | OFFLINE profile + restrict-only overrides + `eoiagent.*` defaults |
| `AcmeGoldenCases` (test) | golden set | `EvalSuite` fixtures for the assembled platform |

Sample resources (under `src/main/resources/acme/`): `docs/` (product help text), `schemas/`
(dataset/data-model YAML), `pipelines/` (ETL job config). All small, generic, copy-and-replace.

## Maven coordinates

- **This module:** `com.eoiagent:eoiagent-app-reference` (version via parent `0.1.0-SNAPSHOT`).
- **Dependencies (only):** `com.eoiagent:eoiagent-app-api` (the SPI) + `com.eoiagent:eoiagent-bom`
  (import-scope, pins everything). **No** core adapter module, **no** LangChain4j, **no**
  experimental libs — enforced by ArchUnit ([05 §6](../architecture/05-core-and-application-packs.md#6-dependency-direction-updated)).
  Versions never hardcoded ([conventions §1](../conventions.md#1-module-layout-maven-multi-module)).
- Built/assembled by `com.eoiagent:eoiagent-platform`; tests pull `eoiagent-platform` +
  `eoiagent-eval` (test scope) to run the golden set against an assembled `AgentService`.

## Inputs / Outputs

- **In:** nothing at runtime — the pack is pure declaration; its `KnowledgeSource.resolve()` reads
  bundled classpath resources.
- **Out:** the eight providers consumed by `PlatformBuilder.start()` ([application-pack.md §Behavior](application-pack.md#behavior--algorithm)),
  yielding a wired `AgentPlatform`/`AgentService` whose `AppId` is `acme-lakehouse`.

## Behavior / algorithm

What each provider returns, end to end:

- **`metadata()`** → `AppId("acme-lakehouse")`, name, version `0.1.0`. Stamped into every
  `AgentContext`/`AuditEvent`.
- **`modelProfile()`** → OFFLINE chat `ollama qwen2.5:14b-instruct` @ `localhost:11434/v1` +
  embedding `onnx-all-minilm` (`all-MiniLM-L6-v2`, 384-dim, in-JVM); `routing().order()=["ollama"]`,
  `allowHostedFallback()==false` (required for OFFLINE — see Error handling).
- **`knowledgeSources()`** → three sources (`PRODUCT_DOC`, `SCHEMA_CONFIG`, `CONFIG_FILE`) of
  bundled sample files; the core `DocumentIngestor` loads/splits/embeds into the in-memory
  `VectorStore`. No dynamic data ([rag-knowledge.md](rag-knowledge.md): events/incidents come via
  tools, never RAG).
- **`toolProvider()`** → three **read-only** `Tool`s with honest `ToolSpec`s
  (`mutating=false`, a `requiredRole`, a `Capability`); `mcpServers()` empty (OFFLINE).
  Returning canned sample results so the demo runs without a live lakehouse.
- **`navigationCatalog()`** → three `PageDescriptor`s; `find(pageId)` powers
  `NavigationIntent` validation. Runtime routing of a "show me revenue" query to `kpi-dashboard`
  with `{metric=revenue}` is **Phase 2** — the Phase-1 orchestrator emits no `NavigationIntent`
  (see Acceptance criteria) ([host-integration.md §Navigation answer](host-integration.md#navigation-answer-the-primary-path)).
- **`promptProfile()`** → persona + a non-null `systemPrompt` for **every** `GoalKind` (Flow-A
  navigation heuristic baked in) + a small glossary.
- **`policyProfile()`** → `mapRole` total (viewer→`USER`, engineer→`ANALYST`, admin→`ADMIN`,
  unknown→`USER`); `grants` returns read-only capabilities per tier.
- **`config()`** → `OFFLINE`; `featureOverrides()` only **restricts** (mutating/MCP off — never
  enabling what the profile forbids); `configDefaults()` ships the `eoiagent.*` OFFLINE defaults.

## Configuration keys

The `eoiagent.*` defaults this pack ships via `configDefaults()` (host config may override, profile
matrix is the hard ceiling — [application-pack.md §Configuration keys](application-pack.md#configuration-keys)):

| Key | Value |
|-----|-------|
| `eoiagent.profile` | `OFFLINE` |
| `eoiagent.model.chat.provider` | `ollama` |
| `eoiagent.model.chat.baseUrl` | `http://localhost:11434/v1` |
| `eoiagent.model.chat.modelId` | `qwen2.5:14b-instruct` |
| `eoiagent.model.embedding.provider` | `onnx-all-minilm` |
| `eoiagent.vectorstore.kind` | `in-memory` |
| `eoiagent.host.navigation.preferOverInline` | `true` |

`eoiagent.app.id` is informational; the authoritative value is `PackMetadata.appId`
(`acme-lakehouse`).

## Error handling

The pack itself returns only data, but it is written to **pass `PackValidator`**
([application-pack.md §Error handling](application-pack.md#error-handling)):

- OFFLINE + `allowHostedFallback()==false` → no `PolicyViolation` at validation (the failing
  inverse is exercised by a deliberately-broken test fixture, not shipped).
- All required providers non-null; `pageId`s unique; `mapRole`/`systemPrompt(...)` total.
- `featureOverrides()` only restrict within the OFFLINE matrix (never enable a forbidden feature).
- Knowledge sources are bundled and readable; an unreadable optional source surfaces as an
  `IngestReport.warning` (non-fatal), not a `ConfigException`.
- Tools return `ToolResult{ok=false, error=...}` for expected failures (unknown dataset id), never
  throwing ([tool-registry.md](tool-registry.md)).

## Acceptance criteria

> **Phase-1 scope vs Phase 2.** Phase 1 ships the pack as a complete, validated SPI implementation
> and proves it **assembles into a working offline `AgentService`** that answers in
> **`AnswerKind.TEXT`**. The Phase-1 runtime is the `ReActOrchestrator` (T-111, Flow B): it returns
> TEXT only and does **not** consume the `NavigationCatalog`/`PromptProfile` or a retriever. So
> **RAG-in-runtime citations** (the cited half of AC2) and **`NavigationIntent` emission** (AC3) are
> **Phase 2**. In Phase 1 the pack proves the navigation contract at the **pack level** only — a
> populated, well-formed `NavigationCatalog` (AC5) — and ships an **all-`TEXT`** golden set.

1. **AC1** `new PlatformBuilder().pack(new ReferenceApplicationPack()).start()` returns an
   `AgentPlatform` whose `pack().appId()` equals `AppId("acme-lakehouse")` — **no network**.
2. **AC2 [P1]** The assembled `AgentService` answers a golden QA question offline (stub
   `LlmGateway`): `kind` is `AnswerKind.TEXT` and the answer text contains the expected fact.
   **[P2]** Returning that answer *with a `Citation`* from the ingested Acme corpus is deferred to
   Phase 2 (RAG-in-runtime); the Phase-1 `ReActOrchestrator` emits no citations.
3. **AC3 [P2 — deferred]** A "show me revenue" style ask returns `AnswerKind.NAVIGATION` with
   `NavigationIntent(targetPageId="kpi-dashboard", parameters⊇{metric=revenue}, rationale!=null)`.
   Not achievable in Phase 1: the `ReActOrchestrator` (T-111, Flow B) returns TEXT only and does not
   consume the `NavigationCatalog`/`PromptProfile`. Phase 1 proves the navigation contract at the
   **pack level** only (catalog populated + `find()` lookup — AC5); the shipped golden set is all
   `TEXT`.
4. **AC4** `toolProvider().tools()` are all read-only (`spec().mutating()==false`) and each carries
   a non-null `requiredRole` + `Capability`; `mcpServers()` is empty.
5. **AC5** `navigationCatalog().find("pipeline-detail")` is present with a required `pipelineId`
   `ParamSpec`; all three `pageId`s are unique.
6. **AC6** `policyProfile().mapRole("engineer")==Role.ANALYST`, `mapRole("nope")==Role.USER`;
   `grants(Role.USER)` contains only read capabilities (no mutating capability).
7. **AC7** `config().profile()==OFFLINE` and `modelProfile().routing().allowHostedFallback()==false`
   (the pack passes `PackValidator` — AC3 of [application-pack.md](application-pack.md#acceptance-criteria)).
8. **AC8** `eoiagent-app-reference` declares **no** dependency on any core adapter module or
   third-party agent lib — only `eoiagent-app-api` + `eoiagent-bom` (ArchUnit).
9. **AC9** `promptProfile().systemPrompt(k)` is non-null for **every** `GoalKind k`.

## Test plan

All tests run **offline, no live LLM** — the platform is assembled with a `StubLlmGateway`
(recorded responses) and the real in-JVM ONNX embedding + in-memory vector store. JUnit 5 + AssertJ.

- **Unit** — `ReferenceApplicationPackTest` (provider non-nullity, mappings — AC4/AC5/AC6/AC9);
  `ReferencePackConfigTest` (OFFLINE + restrict-only overrides — AC7).
- **Contract** — runs the reusable `ApplicationPackContractTest`
  ([application-pack.md §Test plan](application-pack.md#test-plan)) against `ReferenceApplicationPack`.
- **Assembly** — `ReferencePlatformBootstrapTest`: `PlatformBuilder.pack(...).start()` →
  `AgentService` (AC1/AC2), asserts `AppId` on emitted `AuditEvent`s; `AgentPlatform.close()`
  releases adapters.
- **Eval** — `AcmeGoldenCases` (an `EvalSuite`, [eval-harness.md](eval-harness.md)): a small
  **all-`TEXT`** golden set run under OFFLINE (Phase 1). The navigation case (AC3) and a cited QA
  case (AC2) are **deferred to Phase 2** — see the Phase-1 scope note above.
- **ArchUnit** — `ReferencePackDependencyRulesTest` (AC8).
- **Verify:** `mvn -q -pl eoiagent-app-reference test` (default profile, no network).

## Dependencies on other modules

- **Compile:** `eoiagent-app-api` (the SPI interfaces/records) + `eoiagent-bom` only.
- **Test (only):** `eoiagent-platform` (assemble), `eoiagent-eval` (golden harness), plus the core
  adapters they pull transitively at test runtime (`eoiagent-model` stub, `eoiagent-knowledge`
  ONNX + in-memory store) — never compile-scoped into the pack.
- The pack consumes **no** core code directly; it talks to core entirely through the SPI.

## Out of scope / deferred

- Mutating tools / approval flows, MCP servers — disabled in OFFLINE; a real product re-enables via
  profile + `featureOverrides()` (Phase 2).
- Hosted models (Anthropic/Gemini), pgvector, advanced retrieval — not in the OFFLINE reference.
- A second example pack and multi-pack runtime (`AppPackRegistry`) — deferred
  ([ADR-0011](../adr/0011-core-and-application-pack-split.md) §Alternatives).
- Real Acme data sources — the corpus and tool results are illustrative samples to replace.

## Related ADRs & flows

- [ADR-0011 — Core & Application Pack split](../adr/0011-core-and-application-pack-split.md)
- [05-core-and-application-packs.md](../architecture/05-core-and-application-packs.md) — the split + onboarding workflow
- [application-pack.md](application-pack.md) — the SPI + bootstrap this pack implements
- [host-integration.md](host-integration.md) — `NavigationIntent` (the primary answer mode)
- [rag-knowledge.md](rag-knowledge.md), [tool-registry.md](tool-registry.md), [model-gateway.md](model-gateway.md) — the core components the providers configure
- Flow: [Flow 0 — Platform bootstrap](../architecture/04-sequence-flows.md#flow-0--platform-bootstrap-startup), [Flow A — page-context help](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1)
