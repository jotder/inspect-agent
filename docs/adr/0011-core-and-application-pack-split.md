---
type: adr
title: "ADR-0011: Split the platform into a reusable Core and a project-specific Application Pack"
description: "Architecture decision: Split the platform into a reusable Core and a project-specific Application Pack."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["core-and-application-pack-split"]
---
# ADR-0011: Split the platform into a reusable Core and a project-specific Application Pack

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The platform must be **reusable across multiple products**, not built once for a single host
application. Each product differs in: which models it runs, its domain knowledge corpus, the
host Java-API methods exposed as tools, its pages/KPI routes for `NavigationIntent`, its
domain prompts/persona, its user-role mapping, and its deployment configuration. The hard
constraints (C1 embeddable, C2/C3 offline+online, C4 approval, C5 audit) and the Hexagonal
structure ([ADR-0004](0004-hexagonal-ports-and-adapters.md)) already separate mechanism from
binding — but "the host wires it up" was left implicit. We need that wiring to be a first-class,
typed, copy-to-start contract so a new product can be onboarded (largely by AI coding agents)
without forking the engine.

## Decision

Split the codebase into two parts:

1. **CORE (reusable):** all ports + domain types + the generic adapters (the 11 components in
   [01-component-model.md](../architecture/01-component-model.md)), plus a new SPI module
   `eoiagent-app-api` (`com.eoiagent.app`) and an assembly module `eoiagent-platform`
   (`AgentPlatform` / `PlatformBuilder`). Core is product-agnostic and reused unchanged.

2. **APPLICATION PACK (project-specific):** one module per product that implements the
   `ApplicationPack` SPI and supplies eight providers — `PackMetadata`, `ModelProfile`,
   `KnowledgeSource[]`, `ToolProvider`, `NavigationCatalog`, `PromptProfile`, `PolicyProfile`,
   `PackConfig` (see [02-domain-model.md §Application Pack SPI](../architecture/02-domain-model.md#application-pack-spi--comeoiagentapp)).

**One pack per deployment:** a running instance loads exactly one `ApplicationPack`, assembled by
`eoiagent-platform` into a ready `AgentService`. `AppId` (from `PackMetadata`) is threaded through
`AgentContext` and `AuditEvent`. Mechanism is core; **policy, content, and bindings are the pack**.
A bundled reference pack (`eoiagent-app-reference`) is the copy-to-start template. Full design:
[05-core-and-application-packs.md](../architecture/05-core-and-application-packs.md).

## Consequences

**Positive**
- One engine serves N products; core upgrades benefit every product.
- All per-product specifics live in one well-typed module → ideal for agent-built onboarding.
- Strong isolation: a product's knowledge/tools/prompts never leak into core or another product.
- Audit/`AgentContext` carry `AppId`, so provenance is always recorded.

**Negative / risks**
- One extra contract (the SPI) and an assembly module to maintain. Mitigated: the SPI is small
  and the bootstrap is mechanical; both are covered by contract tests.
- The SPI surface must stay stable; changing it is ADR-worthy like any port.

**Follow-ups**
- Extend ArchUnit rules: `eoiagent-app-api` imports only core domain types; no `com.eoiagent.app`
  reference inside core; a pack imports only the SPI + domain types (not core adapters).
- Phase 0 adds `eoiagent-app-api` + `eoiagent-platform`; Phase 1 ships the reference pack and
  demos the MVP through it.

## Alternatives considered

- **Keep wiring implicit in the host.** Rejected: not reusable or testable; every product
  re-discovers how to assemble the agent; no copy-to-start path.
- **Multi-app in one runtime now** (one instance hosts many packs, routed by `AppId`). Rejected
  for v1 as unnecessary complexity (routing, per-pack isolation, lifecycle). The design keeps the
  seam open — `AgentContext`/`AuditEvent` already carry `AppId` — so an `AppPackRegistry` is a
  purely additive future change.
- **Config-only customization (no typed SPI).** Rejected: tools, navigation, and knowledge
  sources are code/structured data, not flat config; a typed SPI is safer and agent-friendlier.
- **Fork core per product.** Rejected: defeats reuse; multiplies maintenance and security surface.
