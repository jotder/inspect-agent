---
type: adr
title: "ADR-0014: v1 ships classpath jars + Automatic-Module-Name; no JPMS module-info"
description: "Architecture decision: the deliberate split-package design is incompatible with JPMS; v1 packaging is plain jars via the BOM with Automatic-Module-Name manifest entries."
timestamp: "2026-07-03T21:00:00+05:30"
tags: ["packaging-classpath-not-jpms"]
---
# ADR-0014: v1 ships classpath jars + Automatic-Module-Name; no JPMS module-info

- **Status:** Accepted
- **Date:** 2026-07-03

## Context

The backlog's T-404 ("shaded uber-jar / JPMS `module-info` as host requires") predates an
architectural fact established during implementation: ports live in `eoiagent-core` and their
adapters live in sibling modules **sharing the same Java packages** (e.g. `com.eoiagent.model`
spans the core jar's ports and the model jar's adapters; likewise `.config`, `.safety`,
`.persistence`, `.observability`). This split-package pattern was chosen deliberately so that a
port and its adapters read as one component (conventions §11-adjacent), and it is now pervasive
— **and split packages are illegal under JPMS**. Real `module-info.java` would require renaming
packages across every module: a breaking, high-churn reorganization with no current customer
demand.

## Decision

1. **v1 packaging is plain classpath jars**, consumed via `eoiagent-bom` (the reference pack
   and HOWTO already work this way). No `module-info.java` anywhere.
2. Every module adds an **`Automatic-Module-Name`** manifest entry (`com.eoiagent.<module>`),
   so a JPMS-based host can still place the jars on the module path as automatic modules
   without our names being derived (unstably) from filenames. This is cheap and non-breaking.
   *(Caveat: automatic modules tolerate split packages only on the classpath; a strict
   module-path host would still see the split — acceptable, documented limitation.)*
3. An optional **shaded uber-jar** (single artifact, relocated where needed) remains in T-404
   scope for hosts that want one dependency.
4. Full JPMS support is **re-opened only if a paying host requires it**, as a major-version
   package reorganization (ports move to unambiguous per-module packages) — never as a patch.

## Consequences

- T-404 narrows to: manifest entries, uber-jar option, license report (ADR-0012 §4), and
  install/usage docs — an achievable ticket instead of a hidden re-architecture.
- The split-package convention stays; the arch tests that enforce port/adapter boundaries
  (JDK Class-File API scanners) remain the real modularity guarantee, not JPMS.
- Hosts on the module path get stable automatic-module names today and a documented path to
  full JPMS later.
