---
type: spec
title: "Config / Deployment Profiles — Spec"
description: "Resolve typed configuration and enforce the per-profile capability matrix so a disabled feature is unreachable, not merely hidden."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["config-profiles"]
---
# Config / Deployment Profiles — Spec

> Resolve typed configuration and enforce the per-profile capability matrix so a disabled feature
> is unreachable, not merely hidden.
> Component 11 in [01-component-model.md](../architecture/01-component-model.md).
> Port(s): `ConfigProvider`.

## Purpose

Every other module asks this component two things: *"what is my configured value for key K?"* and
*"is feature F allowed in this deployment?"*. It is the single authority for the
`DeploymentProfile` and the capability matrix from
[03-deployment-profiles.md](../architecture/03-deployment-profiles.md), and it is the mechanism
that makes the platform **fail closed offline** (constraint C2). A feature is enabled only when
the profile's matrix allows it **and** config turns it on — never by classpath presence.

This is **Phase 0/1** foundation: nothing else can be wired without it.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-11--configuration--deployment-profiles---port-configprovider), copied verbatim:

```java
package com.eoiagent.config;

public interface ConfigProvider {
    DeploymentProfile profile();
    <T> T get(ConfigKey<T> key);
    boolean featureEnabled(Feature feature);   // gated by profile capability matrix
}
```

Contract notes:

- **`profile()`** — Returns the active `DeploymentProfile` (`OFFLINE` / `ON_PREM_HOSTED` /
  `CLOUD`); never null. Fixed for the lifetime of the provider (resolved once at construction from
  `eoiagent.profile`). Thread-safe (immutable).
- **`get(ConfigKey<T>)`** — Returns the typed value for `key`. Resolution order: explicit source
  (env/properties/programmatic) → `key.defaultValue()`. Never returns null if `key.defaultValue()`
  is non-null; may return null only when both source and default are null (document per key).
  Coerces string sources to `key.type()` (Integer/Boolean/Long/String/enum); a malformed value
  throws `ConfigException`. Pure and thread-safe.
- **`featureEnabled(Feature)`** — Returns `true` only when **both** the capability matrix for
  `profile()` permits `feature` **and** the corresponding enabling config key is on (where one
  exists). A profile marked ✗ for a feature returns `false` regardless of config — config can
  restrict further, never loosen. Never throws; never performs I/O. Thread-safe. This is the
  gate the whole platform consults before touching the network or a mutating path.

`ConfigKey<T>(String name, Class<T> type, T defaultValue)` is the core domain type (see
[02-domain-model.md](../architecture/02-domain-model.md#config--profiles)); modules declare their
keys as `public static final ConfigKey<?>` constants and the provider reads them.

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `EnvConfigProvider` | (ours, JDK only) | **Phase 0/1** | reads `EOIAGENT_*` env vars (`eoiagent.model.chat.provider` → `EOIAGENT_MODEL_CHAT_PROVIDER`) |
| `PropertiesConfigProvider` | (ours, JDK `Properties`) | **Phase 0/1** | reads a `.properties` file / classpath resource with dotted keys |
| `ProgrammaticConfigProvider` | (ours, builder) | **Phase 0/1** | values set in code via a builder; the test/embed default |
| `CapabilityMatrix` (internal) | (ours) | **Phase 0/1** | the `Feature × DeploymentProfile` table backing `featureEnabled` |
| `LayeredConfigProvider` (optional) | (ours) | Phase 1 | precedence chain: programmatic > env > properties > defaults |

All adapters share `CapabilityMatrix` and the same coercion/`featureEnabled` logic (extract a
common `AbstractConfigProvider`). No third-party dependency — JDK only.

## Maven coordinates

- **This module:** `com.eoiagent:eoiagent-config` (version `0.1.0-SNAPSHOT`).
- **Ports + domain types:** `com.eoiagent:eoiagent-core` (`ConfigProvider`, `ConfigKey`,
  `DeploymentProfile`, `Feature`). Per [conventions §2](../conventions.md#2-dependency-direction-enforced),
  `ConfigProvider` is a port and may live in `core`/`*-api`; adapters live in `eoiagent-config`.
- **Third-party:** **none** — JDK 25 only (no LangChain4j, no framework). This keeps the
  foundation framework-free per [conventions §1](../conventions.md#1-module-layout-maven-multi-module).

## Inputs / Outputs

Consumed (from [02-domain-model.md](../architecture/02-domain-model.md#config--profiles)):
`ConfigKey<T>(String name, Class<T> type, T defaultValue)`, plus raw env/properties/programmatic
sources.

Produced:
`DeploymentProfile{OFFLINE, ON_PREM_HOSTED, CLOUD}`, typed config values `T`, and `boolean`
feature decisions over `Feature{HOSTED_MODELS, MUTATING_ACTIONS, MCP_TOOLS, PGVECTOR,
LANGGRAPH_CHECKPOINTING, ADVANCED_RETRIEVAL, LONG_TERM_MEMORY}`.

## Behavior / algorithm

Construction:

1. Resolve `eoiagent.profile` from the source; coerce to `DeploymentProfile`. Missing →
   default `OFFLINE` (the safest, fail-closed default). Invalid → `ConfigException`.
2. Build the in-memory `CapabilityMatrix` for the resolved profile (the table below).
3. Validate that no configured value contradicts the profile (e.g. a hosted chat provider while
   `OFFLINE`) → `ConfigException` at construction (fail fast, not at first use).

`get(key)`:

1. Look up `key.name()` in the source (env → properties → programmatic per adapter).
2. If absent, return `key.defaultValue()`.
3. Coerce the raw string to `key.type()` (Boolean/Integer/Long/enum/String). Malformed →
   `ConfigException`.

`featureEnabled(feature)` (implements the capability matrix from
[03-deployment-profiles.md](../architecture/03-deployment-profiles.md#capability-matrix)):

1. `if (!matrix.permits(profile, feature)) return false;` — the hard gate (✗ cells).
2. Otherwise consult the feature's enabling key (if any) via `get(...)`; return its boolean.
3. Never performs I/O; never opens a socket — this is what makes OFFLINE provably network-free.

**Capability matrix** (the table this component encodes verbatim):

| `Feature` | OFFLINE | ON_PREM_HOSTED | CLOUD |
|-----------|:-------:|:--------------:|:-----:|
| `HOSTED_MODELS` | ✗ | ✗ | ✓ |
| `MUTATING_ACTIONS` | ✓ (gated) | ✓ (gated) | ✓ (gated) |
| `MCP_TOOLS` | local stdio only | ✓ | ✓ |
| `PGVECTOR` | ✓ (local PG) | ✓ | ✓ |
| `LANGGRAPH_CHECKPOINTING` | ✓ | ✓ | ✓ |
| `ADVANCED_RETRIEVAL` | ✓ | ✓ | ✓ |
| `LONG_TERM_MEMORY` | ✓ | ✓ | ✓ |

`MCP_TOOLS` in OFFLINE is `true` but constrained to `stdio` transport — callers must additionally
honor `eoiagent.tools.mcp.transport` (the registry enforces "no remote MCP offline").

## Configuration keys

This module **owns the profile key and the defaults registry**; per
[conventions §9](../conventions.md#9-definition-of-done-per-ticket) every new key elsewhere must
be registered here with a default.

| Key | Type | Default |
|-----|------|---------|
| `eoiagent.profile` | enum `DeploymentProfile` | `OFFLINE` |
| `eoiagent.features.hostedModels.enabled` | Boolean | matrix-gated (✗ offline/on-prem) |
| `eoiagent.features.mutatingActions.enabled` | Boolean | `true` (all profiles, still approval-gated) |
| `eoiagent.features.mcpTools.enabled` | Boolean | `false` offline / `true` else |
| `eoiagent.features.pgvector.enabled` | Boolean | `false` offline default / `true` else |
| `eoiagent.features.advancedRetrieval.enabled` | Boolean | `false` (Phase 2) |
| `eoiagent.features.langgraphCheckpointing.enabled` | Boolean | `false` (Phase 3) |
| `eoiagent.features.longTermMemory.enabled` | Boolean | `false` (Phase 3) |

Defaults for *other* modules' keys (e.g. `eoiagent.model.chat.provider`, `eoiagent.vectorstore.kind`,
`eoiagent.approval.required`, `eoiagent.audit.sink`) follow the per-profile table in
[03-deployment-profiles.md](../architecture/03-deployment-profiles.md#defaults-per-profile) and
are registered here as `ConfigKey` constants so each owning spec maps to a default.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- `ConfigException` — invalid/missing `eoiagent.profile`; a value that cannot be coerced to
  `key.type()`; or a configuration that contradicts the profile (e.g. hosted provider while
  `OFFLINE`, remote pgvector/MCP while `OFFLINE`). Thrown at construction (fail fast) where
  possible.
- `PolicyViolation` is **not** thrown here — this component only *reports* (`featureEnabled`);
  the calling module fails closed by throwing `PolicyViolation` when it tries to use a disabled
  feature (per [conventions §5](../conventions.md#5-error-handling) "offline fail-closed").
- `featureEnabled` and `get` never throw at call time for a well-constructed provider (coercion
  errors surface at construction for known keys); `featureEnabled` never performs I/O.

Offline guarantee: `featureEnabled(HOSTED_MODELS)` is **always** `false` in `OFFLINE` and
`ON_PREM_HOSTED`; this is the assertion every network-touching adapter relies on (invariant 3 in
[04-sequence-flows.md](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)).

## Acceptance criteria

1. **AC1** A provider constructed with `eoiagent.profile=OFFLINE` returns
   `featureEnabled(HOSTED_MODELS) == false`, regardless of any
   `eoiagent.features.hostedModels.enabled=true` override.
2. **AC2** `featureEnabled(HOSTED_MODELS)` is `false` for `OFFLINE` and `ON_PREM_HOSTED` and
   `true` for `CLOUD` (when its enabling key is on).
3. **AC3** Missing `eoiagent.profile` defaults to `OFFLINE` (fail-closed default).
4. **AC4** An invalid profile value or a non-coercible typed value throws `ConfigException` at
   construction.
5. **AC5** `get(ConfigKey)` returns the source value when present, else the key's default, with
   correct type coercion (String/Integer/Boolean/enum).
6. **AC6** `EnvConfigProvider` maps `eoiagent.model.chat.provider` to env var
   `EOIAGENT_MODEL_CHAT_PROVIDER` and reads it.
7. **AC7** `featureEnabled` performs no network or file I/O (verified by the network-deny / no-IO
   harness).
8. **AC8** Configuring a hosted chat provider while `OFFLINE` throws `ConfigException` at
   construction (profile/config contradiction).
9. **AC9** In `OFFLINE`, `featureEnabled(MCP_TOOLS)` reflects "stdio only": `true` for local stdio
   config, and the transport key still constrains remote use.
10. **AC10** `LayeredConfigProvider` precedence is programmatic > env > properties > default for
    the same key.

## Test plan

All tests run with **no network and no live LLM** (this module has no LLM dependency at all).
JUnit 5 + AssertJ; no Mockito needed (pure logic + in-memory sources).

- **Unit** — `CapabilityMatrixTest` (every `Feature × DeploymentProfile` cell asserted against
  the table), `EnvConfigProviderTest` (env-var name mapping + coercion),
  `PropertiesConfigProviderTest`, `ProgrammaticConfigProviderTest`,
  `LayeredConfigProviderTest` (precedence), `ConfigCoercionTest` (typed + malformed values).
- **Contract** — `ConfigProviderContractTest` (shared suite every adapter passes: default
  fallback, coercion, `featureEnabled` matrix behavior, fail-closed defaults).
- **Profile/offline** — `OfflineFailClosedTest` asserts AC1/AC2/AC7/AC8; `NoIoTest` asserts
  `featureEnabled`/`get` open no socket and read no file at call time.
- **Eval** — n/a (no LLM); but other modules' eval profiles construct providers via this module.

Command: `mvn -pl eoiagent-config -am test` (default, no network, no live LLM).

## Dependencies on other modules

- `eoiagent-core` — domain types (`ConfigProvider` port, `ConfigKey`, `DeploymentProfile`,
  `Feature`) and the typed exception hierarchy. **No other module dependency** — this is the base
  of the dependency graph (everything else depends on it, per
  [01-component-model.md](../architecture/01-component-model.md#dependency-direction-must-hold)).
- Consumers: **every** other module (model, knowledge, tool, runtime, memory, safety,
  persistence, observability, host) reads keys and calls `featureEnabled` through this port.

## Out of scope / deferred

- Hot-reload / live config change at runtime — Phase 4 (providers are immutable per construction).
- Secret management / vault integration for `apiKey` keys — Phase 2+ (MVP reads env/properties).
- Remote/centralized config server — out of scope (embeddable library, constraint C1).
- Phase-3/Phase-4 feature keys (`LANGGRAPH_CHECKPOINTING`, `LONG_TERM_MEMORY`) are declared but
  their backing features ship later.

## Related ADRs & flows

- [ADR-0001 — Embeddable Java, no Spring](../adr/0001-embeddable-java-no-spring.md)
- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- [ADR-0010 — Isolate experimental deps](../adr/0010-isolate-experimental-deps.md)
- [03 — Deployment profiles & capability matrix](../architecture/03-deployment-profiles.md)
- Cross-cutting invariant 3 (no network in OFFLINE):
  [04-sequence-flows.md](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)
