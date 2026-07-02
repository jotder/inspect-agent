---
type: spec
title: "Application Pack SPI & Platform Bootstrap — Spec"
description: "The project-specific contract (`eoiagent-app-api`, `com.eoiagent.app`) a product implements to instantiate the agent for its domain, plus the core assembly module (`eoiagent-platform`)."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["application-pack"]
---
# Application Pack SPI & Platform Bootstrap — Spec

> The project-specific contract (`eoiagent-app-api`, `com.eoiagent.app`) a product implements to
> instantiate the agent for its domain, plus the core assembly module (`eoiagent-platform`).
> Concept: [05-core-and-application-packs.md](../architecture/05-core-and-application-packs.md).
> Types: [02-domain-model.md §Application Pack SPI / §Platform bootstrap](../architecture/02-domain-model.md#application-pack-spi--comeoiagentapp).
> Decision: [ADR-0011](../adr/0011-core-and-application-pack-split.md).

## Purpose

Make every per-product specific a typed, testable, copy-to-start contract so a new product is
onboarded by filling **one module** — no fork of core. `eoiagent-platform` consumes a pack and
assembles a ready `AgentService`. CORE never depends on a pack.

## Port interface(s)

`eoiagent-app-api` is itself the SPI (the "ports" a *product* implements). Signatures are copied
verbatim from the domain model; contract notes added here.

```java
package com.eoiagent.app;

public interface ApplicationPack {
    PackMetadata          metadata();
    ModelProfile          modelProfile();
    List<KnowledgeSource> knowledgeSources();
    ToolProvider          toolProvider();
    NavigationCatalog     navigationCatalog();
    PromptProfile         promptProfile();
    PolicyProfile         policyProfile();
    PackConfig            config();
}
```

Contract notes (all methods are called once at `start()` unless noted):

- **`metadata()`** — non-null; `appId` unique per product, stable across versions. Stamped into
  every `AgentContext`/`AuditEvent`.
- **`modelProfile()`** — non-null. `chat()`/`embedding()` non-null `ModelSelection`;
  `routing().order()` lists provider ids in preference order; `allowHostedFallback()` MUST be
  `false` whenever `config().profile() == OFFLINE` (validated at `start()` — see Error handling).
- **`knowledgeSources()`** — may be empty (a pack with no RAG corpus is valid); no null elements.
  Each `resolve()` returns the `DocumentSource`s the core `DocumentIngestor` loads. Called at
  `start()` and re-callable for re-ingestion.
- **`toolProvider()`** — `tools()` may be empty; each `Tool` must declare `mutating` +
  `requiredRole` in its `ToolSpec`. `mcpServers()` may be empty; entries are gated by
  `Feature.MCP_TOOLS` + profile.
- **`navigationCatalog()`** — `pages()` may be empty; `pageId`s unique; `find(pageId)` is the
  lookup the Host Integration uses when validating a `NavigationIntent` the model proposes.
- **`promptProfile()`** — `systemPrompt(GoalKind)` non-null for every `GoalKind` (return a sensible
  default rather than null); `domainGlossary()` may be empty.
- **`policyProfile()`** — `mapRole(hostRole)` total (never null; default to least-privileged
  `Role.USER`); `grants(role)` returns the capabilities allowed for that role (subset enforced by
  the core `PolicyEngine`).
- **`config()`** — `profile()` non-null; `featureOverrides()` may only *restrict* within the
  profile's capability matrix (never enable a feature the profile forbids — validated);
  `configDefaults()` are `eoiagent.*` keys, overridable by host config.

The provider interfaces (`ModelProfile`, `KnowledgeSource`, `ToolProvider`, `NavigationCatalog`,
`PromptProfile`, `PolicyProfile`, `PackConfig`) and their records are defined verbatim in the
domain model; implement them in the pack module.

### Platform bootstrap (`com.eoiagent.platform`, module `eoiagent-platform`)
```java
public interface AgentPlatform extends AutoCloseable {
    AgentService agentService();   // the host facade, fully wired
    PackMetadata pack();           // which pack is running
}
public final class PlatformBuilder {
    public PlatformBuilder pack(ApplicationPack pack);           // required
    public PlatformBuilder configProvider(ConfigProvider cfg);   // optional host override
    public PlatformBuilder auditSink(AuditSink sink);            // optional override
    public AgentPlatform start();                                // validate → wire → ingest → ready
}
```
`start()` is the single bootstrap entry point (see Behavior). `AgentPlatform.close()` releases all
`AutoCloseable` adapters (HTTP clients, DB pools, model handles).

## Adapters to build

| Adapter / class | Module | Library | Phase | Notes |
|-----------------|--------|---------|-------|-------|
| `ApplicationPack` + 7 provider interfaces + records | `eoiagent-app-api` | none (core domain only) | **0** | the SPI; no framework deps |
| `PlatformBuilder` / `DefaultAgentPlatform` | `eoiagent-platform` | core adapters (runtime deps) | **0–1** | wires core from the pack |
| `PackValidator` | `eoiagent-platform` | none | **0** | validates the pack at `start()` |
| `eoiagent-app-reference` (worked pack) | `eoiagent-app-reference` | `eoiagent-app-api` | **1** | see [reference-app-pack.md](reference-app-pack.md) |

## Maven coordinates

- This SPI: `com.eoiagent:eoiagent-app-api` (depends on `eoiagent-core` domain types only).
- Bootstrap: `com.eoiagent:eoiagent-platform` (depends on core ports + adapter modules + `app-api`).
- A product pack: `<product-groupId>:<product>-agent-pack` → depends on `eoiagent-app-api` +
  `eoiagent-bom` only. Versions via BOM ([02-domain-model.md](../architecture/02-domain-model.md#maven-coordinates));
  never hardcode.

## Inputs / Outputs

- **In:** an `ApplicationPack` implementation (+ optional host `ConfigProvider`/`AuditSink`).
- **Out:** a wired `AgentPlatform` exposing `AgentService` (Component 10).
- Threads `AppId` from `PackMetadata` into every `AgentContext` and `AuditEvent`.

## Behavior / algorithm

Implements **Flow 0 — Platform bootstrap** ([04-sequence-flows.md](../architecture/04-sequence-flows.md#flow-0--platform-bootstrap-startup)):

1. `PackValidator.validate(pack)` — required providers non-null; OFFLINE↔hosted-fallback
   consistency; `featureOverrides` within the profile matrix; `pageId`/`appId` uniqueness.
2. Build `ConfigProvider` = merge(`PackConfig.configDefaults`, host overrides); resolve profile +
   feature gates.
3. Build `LlmGateway`/`RoutingLlmGateway` from `ModelProfile` (offline fail-closed).
4. Ingest `knowledgeSources()` via `DocumentIngestor` into the configured `VectorStore`.
5. Register `ToolProvider` tools + MCP servers into `ToolRegistry` (classify read-only/mutating).
6. Build `PolicyEngine` from `PolicyProfile`; build `Guardrail`s, `ApprovalGate`, `AuditSink`
   per config.
7. Build the `Orchestrator` with `PromptProfile` (system prompts/persona/glossary) +
   `NavigationCatalog`.
8. Build `AgentService` bound to the above; return `AgentPlatform`.

## Configuration keys

- `eoiagent.app.id` — informational; the authoritative value is `PackMetadata.appId`.
- The pack ships `eoiagent.*` defaults via `PackConfig.configDefaults()`; host config overrides
  them; the profile capability matrix is the hard ceiling.

## Error handling

- `PackValidator` failures throw `ConfigException` with the specific provider/field (fail fast at
  `start()` — never start a misconfigured pack).
- OFFLINE with `allowHostedFallback()==true` or a `featureOverride` exceeding the matrix →
  `PolicyViolation` at validation.
- A pack returning null for a required provider → `ConfigException` (not NPE).
- Knowledge ingestion errors surface as `IngestReport.warnings` (non-fatal) unless a source is
  unreadable and marked required → `ConfigException`.

## Acceptance criteria

- **AC1** `PlatformBuilder.pack(refPack).start()` returns an `AgentPlatform` whose
  `agentService()` answers a question end-to-end (offline, stub `LlmGateway`). In Phase 1 the answer
  is `AnswerKind.TEXT` via the `ReActOrchestrator` (Flow B); the cited / `NavigationIntent` Flow-A
  answer is **Phase 2** (see [reference-app-pack.md §Acceptance criteria](reference-app-pack.md#acceptance-criteria)).
- **AC2** `AppId` from the pack appears in every emitted `AuditEvent` and in `AgentContext`.
- **AC3** A pack with `OFFLINE` + `allowHostedFallback()==true` fails `start()` with
  `PolicyViolation` (no platform built).
- **AC4** A pack omitting a required provider fails `start()` with `ConfigException` naming it.
- **AC5** Two distinct packs (reference + a second stub pack) each `start()` independently and
  produce isolated tool/navigation/knowledge sets (no cross-leakage).
- **AC6** `eoiagent-app-api` has **no** dependency on any core adapter module or third-party agent
  lib (architecture test).
- **AC7** `AgentPlatform.close()` closes all `AutoCloseable` adapters.

## Test plan

- **Contract test** `ApplicationPackContractTest` — a reusable suite any pack runs against
  (validates provider non-nullity, OFFLINE consistency, pageId uniqueness).
- **Unit** `PackValidatorTest`, `PlatformBuilderTest` (with a `StubApplicationPack` +
  `StubLlmGateway`, no network/LLM).
- **Architecture test** `AppApiDependencyRulesTest` (AC6) + the core/pack direction rules from
  [05 §6](../architecture/05-core-and-application-packs.md#6-dependency-direction-updated).
- **Eval** the pack's golden set runs through the assembled platform (see
  [eval-harness.md](eval-harness.md)).
- **Verify:** `mvn -q -pl eoiagent-app-api,eoiagent-platform test`

## Dependencies on other modules

Consumes **all** core components at assembly time (model, knowledge, tool, runtime, memory,
scratchpad, safety, persistence, observability, host, config). The SPI module itself depends only
on `eoiagent-core` domain types.

## Out of scope / deferred

- **Multi-pack in one runtime** (`AppPackRegistry`, routing by `AppId`, per-pack store isolation)
  — deferred; the `AppId` seam keeps it additive. ([ADR-0011](../adr/0011-core-and-application-pack-split.md) §Alternatives)
- **Hot-reload of a pack** without restart — deferred, Phase 4+.
- **Dynamic pack discovery** (ServiceLoader/classpath scan) — optional; explicit
  `PlatformBuilder.pack(...)` is the v1 path.

## Related ADRs & flows

- [ADR-0011 — Core & Application Pack split](../adr/0011-core-and-application-pack-split.md)
- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- [ADR-0010 — Isolate experimental dependencies](../adr/0010-isolate-experimental-deps.md)
- [05-core-and-application-packs.md](../architecture/05-core-and-application-packs.md)
- [04-sequence-flows.md](../architecture/04-sequence-flows.md) — Flow 0 (bootstrap)
- [host-integration.md](host-integration.md), [config-profiles.md](config-profiles.md)
