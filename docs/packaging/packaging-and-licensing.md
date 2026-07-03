---
type: packaging
title: "Packaging & Licensing (T-404)"
description: "How the platform is packaged for consumption: classpath jars with Automatic-Module-Name (ADR-0014), an optional shaded uber-jar, and the permissive-only third-party license report (ADR-0012 §4)."
timestamp: "2026-07-03T00:00:00+05:30"
tags: ["packaging", "licensing", "jpms", "uber-jar"]
---
# Packaging & Licensing (T-404)

> Delivers [backlog T-404](../roadmap/backlog.md#phase-4--hardening) as narrowed by
> [ADR-0014](../adr/0014-packaging-classpath-not-jpms.md) (classpath jars, no JPMS) and
> [ADR-0012 §4](../adr/0012-permissive-licensing-policy.md) (license report). No production code
> changed — this is build configuration.

## 1. Consuming the platform: classpath jars via the BOM

The platform ships as plain classpath jars. Import `eoiagent-bom` for version management and depend
on the modules you need (the reference pack and HOWTO already work this way):

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.eoiagent</groupId><artifactId>eoiagent-bom</artifactId>
      <version>0.1.0-SNAPSHOT</version><type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

## 2. Automatic-Module-Name (ADR-0014)

There is **no `module-info.java` anywhere** — ports and their adapters deliberately share packages
(e.g. `com.eoiagent.model` spans the core jar's ports and the model jar's adapters), which is
illegal under JPMS. Instead every jar carries a stable `Automatic-Module-Name` manifest entry
(`com.eoiagent.<module>`), so a JPMS host placing the jars on the module path gets stable names
rather than filename-derived ones. Each jar module declares an `<automatic.module.name>` property;
the root pom's `maven-jar-plugin` config writes it into the manifest.

**Caveat (documented, from ADR-0014):** automatic modules tolerate split packages only on the
**classpath**. A strict module-path host would still see the split — the real modularity guarantee
is the JDK Class-File API arch tests (port/adapter boundaries), not JPMS.

**Verify:** after `mvn -o install -DskipTests`, run

```
node tools/packaging/check-modules.mjs
```

which asserts every jar's `Automatic-Module-Name` matches `com.eoiagent.<module>` and that no jar
ships a `module-info.class`. Exits non-zero on any violation.

## 3. Optional shaded uber-jar (ADR-0014 §3)

For hosts that want a single dependency, an opt-in profile on `eoiagent-examples` builds a shaded
jar with all runtime dependencies (attached with classifier `all`, so the normal build is
untouched):

```
mvn -Puber-jar -pl eoiagent-examples -am package -DskipTests
java -jar eoiagent-examples/target/eoiagent-examples-0.1.0-SNAPSHOT-all.jar
```

Service files (SPI) are merged (`ServicesResourceTransformer`); signature files and any
`module-info.class` are stripped. Main class is `RunAllDemos`. Note: the artifact is large
(~200 MB) because it embeds the ONNX runtime native libraries and the all-MiniLM-L6-v2 weights.
First build needs network once to resolve `maven-shade-plugin`.

## 4. Third-party license report (ADR-0012 §4)

The `license-report` profile generates the third-party inventory and **fails the build** if any
distributed (compile/runtime-scope) dependency carries a license outside the permissive whitelist
(Apache-2.0 / MIT / BSD-2/3-Clause):

```
mvn -Plicense-report -pl eoiagent-platform -am license:aggregate-add-third-party
```

Configuration notes:

- **First-party** `com.eoiagent:*` modules are excluded (they carry no POM license; not third
  party to vet).
- **Test/provided** scope is excluded — e.g. JUnit is EPL-2.0 but never ships in a distributed
  artifact.
- **Dual-licensed** dependencies are resolved to their elected permissive option in
  [`tools/packaging/license-overrides.properties`](../../tools/packaging/license-overrides.properties)
  — currently JNA (`Apache-2.0 OR LGPL-2.1-or-later` → Apache-2.0, per ADR-0012 §3). Explicit and
  auditable, not silently merged.

A committed snapshot of the current (passing) inventory — **44 distributed dependencies, all
Apache-2.0 / MIT / BSD** — lives at [`docs/legal/THIRD-PARTY.txt`](../legal/THIRD-PARTY.txt);
regenerate it with the command above when the BOM changes.

## Related

- [ADR-0014 — Packaging: classpath, not JPMS](../adr/0014-packaging-classpath-not-jpms.md)
- [ADR-0012 — Permissive-only licensing](../adr/0012-permissive-licensing-policy.md)
- [conventions §1](../conventions.md) (versions resolve through the BOM)
