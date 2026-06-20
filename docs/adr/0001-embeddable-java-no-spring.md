# ADR-0001: Embeddable plain-Java library, no Spring/Quarkus

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The EOI Agent platform is an embeddable "Agent Operating System" that runs *inside* a
host Java application — a host product's web UI, services, CLI, or daemon. It is not a
standalone service we control end-to-end.

Constraint **C1** is hard: the library must drop into *any* host with no dependency-
injection framework requirement. Hosts vary — some are Spring Boot apps, some are
Quarkus, many are neither (plain `main`, an embedded daemon, a desktop app, a thin web
service). We cannot assume, mandate, or conflict with the host's wiring strategy.

If we adopted a DI-framework-native AI library, every non-matching host would be forced
to either pull in that framework or fight classpath/lifecycle conflicts. That breaks the
"embeddable anywhere" promise and the offline-first internal-tooling use case.

## Decision

Ship EOI Agent as a **plain Java library** usable from any host (CLI / daemon / desktop /
web service) with **no DI-framework requirement** (constraint C1).

- All components are constructed via **plain constructors and builders** and wired through
  **ports** (Java interfaces); see [ADR-0004](0004-hexagonal-ports-and-adapters.md).
- No static singletons for ports; the owning runtime constructs and owns adapters and
  closes the `AutoCloseable` ones (per [`../conventions.md`](../conventions.md) §4, §6).
- The host integrates only against `eoiagent-host` (`AgentService` / `AgentSession`) plus
  `eoiagent-core`. It may run inside Spring, Quarkus, or nothing at all — that is the
  host's choice, invisible to this library.

This explicitly **rules out Spring AI and the Quarkus LangChain4j extension** as the
foundation, consistent with the rejected-alternatives note in
[`../architecture/00-overview.md`](../architecture/00-overview.md) §6.

## Consequences

**Positive**
- Genuinely embeddable: drops into any host with zero framework coupling.
- No conflict with the host's lifecycle, classpath, or bean container.
- Plain constructors/builders are trivially testable and trivially wired by an AI agent
  building one module at a time.

**Negative / follow-ups**
- We forgo framework conveniences (auto-config, bean scanning, property binding). We
  replace them with our own `ConfigProvider` (`eoiagent-config`) and explicit wiring.
- Hosts that *are* Spring/Quarkus must write a small adapter layer to expose EOI Agent as
  a bean themselves; we document this but do not ship a starter (a starter could be a
  future, optional, separate module — it must never become a dependency of the core).

**Risks / mitigation**
- Risk: wiring boilerplate grows. Mitigation: builders with sensible per-profile defaults
  from the deployment-profile matrix, plus a documented "assembly" example per profile.

## Alternatives considered

- **Spring AI** — excellent developer experience *if and only if* the host is already a
  Spring Boot application. Most of our target hosts are not, and mandating Spring violates
  C1. Rejected as the foundation.
- **Quarkus LangChain4j extension** — tied to the Quarkus runtime and its build-time
  augmentation model. Irrelevant for an embeddable library that cannot assume Quarkus.
  Rejected.
