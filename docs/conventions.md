# Conventions

> Binding rules for everyone writing code in this repo — **especially AI coding agents**. These
> exist so independently-built modules compose without rework. If a spec contradicts this file,
> this file wins (and the spec should be fixed).

## 1. Module layout (Maven multi-module)

- One Maven module per component grouping (see
  [`architecture/02-domain-model.md`](architecture/02-domain-model.md#maven-coordinates)).
- **Ports + domain types** live in `eoiagent-core` (and component `*-api` packages). They have
  **no dependency on any agent framework** (no LangChain4j, no LangGraph4j, no MCP).
- **Adapters** live in their component module (e.g. `eoiagent-model`) and are the **only** place
  third-party agent libraries may be imported.
- Versions come from BOMs: `eoiagent-bom` (which imports `langchain4j-bom:1.16.3`). **Never**
  hardcode a third-party version in a module `pom.xml`.

## 2. Dependency direction (enforced)

```
adapters ──► ports (core)        ports ──► (nothing in this project except core/domain)
host app  ──► eoiagent-platform ──► core
Application Pack ──► eoiagent-app-api (SPI) ──► core domain types only
eoiagent-platform consumes a pack + core adapters; CORE never imports a pack.
```
- Ports never import adapters. Core never imports a framework. **Core never imports a pack**, and
  **core contains no product-specific content** (no product names, prompts, page ids, tools).
- The **Application Pack** depends on `eoiagent-app-api` + `eoiagent-bom` **only** — never on core
  adapter modules or experimental libs. `eoiagent-app-api` imports only core domain types. See
  [ADR-0011](adr/0011-core-and-application-pack-split.md) and
  [`architecture/05-core-and-application-packs.md`](architecture/05-core-and-application-packs.md).
- Experimental deps (`langchain4j-agentic`, `langchain4j-guardrails`, `org.bsc.langgraph4j:*`,
  vector-store/LLM-specific artifacts) appear **only** inside a core adapter module (a pack never
  sees them). See [ADR-0010](adr/0010-isolate-experimental-deps.md).
- Add architecture tests (ArchUnit) asserting all of these rules; part of Phase 0.

## 3. Naming

| Thing | Rule | Example |
|-------|------|---------|
| Port interface | noun, no `I` prefix | `LlmGateway`, `ToolRegistry` |
| Adapter class | `<Tech><Port>` or `<Variant><Port>` | `OllamaChatAdapter`, `InMemoryVectorStore` |
| Value type | `record`, noun | `AgentAnswer`, `NavigationIntent` |
| Enum | singular, UPPER_SNAKE constants | `Role.ANALYST`, `AnswerKind.NAVIGATION` |
| Config key | dotted, `eoiagent.` prefix | `eoiagent.model.chat.provider` |
| Maven module (core) | `eoiagent-<component>` | `eoiagent-knowledge`, `eoiagent-app-api`, `eoiagent-platform` |
| Application Pack module | `<product>-agent-pack` (product groupId) | `eoiagent-app-reference` (the template) |
| Pack class | `<Product>Pack implements ApplicationPack` | `AcmePack`, `ReferenceApplicationPack` |

Use the **exact** type/term names from
[`architecture/02-domain-model.md`](architecture/02-domain-model.md) and [`glossary.md`](glossary.md).
Do not invent synonyms.

## 4. Java style

- **JDK 25.** `maven.compiler.release=25`. Use records, sealed types, pattern matching, switch
  expressions where they clarify intent. No Lombok.
- Prefer immutability; pass `AgentContext` explicitly (no thread-locals for context).
- Public API: no nullable returns where an `Optional` or empty collection fits; document
  nullability of record components.
- No static singletons for ports — construct via builders, inject into the runtime.

## 5. Error handling

- Ports throw a small typed hierarchy rooted at `EoiAgentException` (in core):
  `ModelUnavailableException`, `ToolExecutionException`, `PolicyViolation`,
  `ApprovalDeniedException`, `GuardrailViolation`, `ConfigException`.
- Tools return `ToolResult{ok=false, error=...}` for *expected* failures (bad args, not-found);
  throw only for *unexpected* faults.
- Never swallow an exception silently — record an `AuditEvent(ERROR, ...)` and rethrow or convert
  to a typed result.
- Offline fail-closed: when a feature is disabled by profile, throw `PolicyViolation` — never
  silently fall back to the network.

## 6. Concurrency & resources

- Adapters that hold resources (HTTP clients, DB pools, model handles) implement
  `AutoCloseable`; the owning runtime closes them.
- HTTP: use the **JDK `HttpClient`** transport (see
  [ADR-0002](adr/0002-jdk25-maven-httpclient.md)); a shared client per gateway.
- `Orchestrator` runs may be parallel across sessions; a single `AgentSession` is single-threaded
  from the caller's perspective.

## 7. Logging & audit (distinct concerns)

- **Logging** = SLF4J, for developers. No secrets, no full prompts at INFO.
- **Audit** = `AuditSink`, for compliance — structured, append-only, never disabled. Audit is
  *not* logging; emit `AuditEvent`s per [`architecture/04-sequence-flows.md`](architecture/04-sequence-flows.md)
  invariants.

## 8. Testing (required for "done")

- **Unit tests** per adapter against its port contract; deterministic (no live LLM).
- **Contract tests**: a shared test suite per port that every adapter must pass.
- **LLM-dependent tests** use a stub/recorded `LlmGateway` by default; a profile-tagged
  integration test may hit a local Ollama in CI.
- **Eval tests**: golden Q&A + tool-call assertions (see
  [`specs/eval-harness.md`](specs/eval-harness.md)).
- Every spec's **Acceptance criteria** must map to at least one test. A ticket is not done until
  its acceptance tests pass.
- Test framework: JUnit 5 + AssertJ. Mocking: Mockito only where a stub is impractical.

## 9. Definition of Done (per ticket)

1. Code compiles on JDK 25 via Maven.
2. All acceptance criteria in the linked spec have passing tests.
3. Architecture tests (dependency direction, no-framework-in-core) pass.
4. New config keys documented in the owning spec and added to `ConfigProvider` defaults.
5. Audit events emitted where the flow requires them.
6. No new third-party version pinned outside a BOM.

## 10. Docs are the source of truth

- `docs/architecture/*` defines contracts; `docs/specs/*` defines modules; `docs/adr/*` records
  *why*. If you must deviate, **write/Update an ADR first**, then change the spec, then the code.
- Keep Maven coordinates, type names, and config keys identical across all docs (they are
  cross-referenced and grep-checked).

## 11. Configuration access & key ownership

- **One `ConfigProvider` per deployment.** It is constructed once at the composition root
  (`eoiagent-platform`, optionally overridden via `PlatformBuilder.configProvider(...)`) and
  **injected** into every component. A module never constructs its own provider — the single
  instance is what makes profile resolution, the capability matrix, and the offline fail-closed
  checks authoritative (invariant 3 in [`architecture/04-sequence-flows.md`](architecture/04-sequence-flows.md)).
- **A module owns its keys, not a config file.** Each module declares its own
  `public static final ConfigKey<?>` constants (defaults in code) and reads them through the
  injected `ConfigProvider`. It depends on `eoiagent-core` for the `ConfigProvider` port and
  `ConfigKey` — **never** on the `eoiagent-config` adapter module. `eoiagent-config` carries only
  the profile key, the feature-flag keys, and the cross-cutting keys its profile checks enforce
  (e.g. `eoiagent.model.chat.provider`, `eoiagent.tools.mcp.transport`).
- **There are no per-module config files.** Config *sources* (env / `.properties` / programmatic)
  are assembled once at the composition root — optionally layered, precedence
  programmatic > env > properties > defaults. Components do not read files; per-deployment defaults
  come from the Application Pack's `PackConfig.configDefaults()`. A module must be able to start
  **offline without loading any external file** (its `ConfigKey` defaults suffice).
