# 05 — Core & Application Packs (reuse across products)

> How the platform is split into a **reusable CORE** and a **project-specific Application Pack**,
> so one engine serves many products. Types: [`02-domain-model.md`](02-domain-model.md) (§Application
> Pack SPI, §Platform bootstrap). Decision: [ADR-0011](../adr/0011-core-and-application-pack-split.md).
> Module spec: [`../specs/application-pack.md`](../specs/application-pack.md). Reference template:
> [`../specs/reference-app-pack.md`](../specs/reference-app-pack.md).

## 1. The split

```
        ┌───────────────────────────── ONE DEPLOYMENT ─────────────────────────────┐
        │                                                                            │
  host  │   CORE (reusable, product-agnostic)            APPLICATION PACK            │
  app ──┼─► eoiagent-platform ── assembles ──► AgentService   (project-specific)      │
        │     │                                   ▲           implements com.eoiagent.app │
        │     │  drives core adapters:            │           one pack per product     │
        │     │   model · knowledge · tool ·      │ consumes  provides:                │
        │     │   runtime · memory · scratchpad · │◄──────────  • ModelProfile          │
        │     │   safety · audit · host · config  │             • KnowledgeSource[]     │
        │     └───────────────────────────────────┘             • ToolProvider          │
        │                                                        • NavigationCatalog     │
        │   CORE knows NOTHING about any product.                • PromptProfile         │
        │   The Pack contains ALL product specifics.             • PolicyProfile         │
        │                                                        • PackConfig            │
        └────────────────────────────────────────────────────────────────────────────┘
```

- **CORE** = the Agent OS: every port + domain type + the generic adapters (the 11 components in
  [`01-component-model.md`](01-component-model.md)). Shipped as libraries under `com.eoiagent`.
  It is built once and reused unchanged by every product.
- **APPLICATION PACK** = one module per product that implements the `ApplicationPack` SPI and
  supplies everything product-specific. A new product = a new pack; **no fork of core**.
- **One pack per deployment.** A running instance loads exactly one pack (see §6 for the
  multi-pack seam we deliberately leave open).

## 2. Why this shape

The platform is already Hexagonal ([ADR-0004](../adr/0004-hexagonal-ports-and-adapters.md)) — ports
in core, adapters behind them. Reuse across products is therefore a **packaging + SPI** concern,
not a rewrite:

- Product specifics were previously assumed to be "wired in by the host." We now name that wiring
  a first-class, typed contract — the `ApplicationPack` SPI — so it is discoverable, testable, and
  copy-to-start. ([ADR-0011](../adr/0011-core-and-application-pack-split.md))
- Everything an AI coding agent must change to onboard a new product lives in **one module**.

## 3. What is CORE vs what is in the Pack

| Concern | CORE (reusable) | Application Pack (project-specific) |
|---------|-----------------|-------------------------------------|
| Ports & domain types | ✅ all | — |
| Model **adapters** (Ollama, OpenAI-compat, Anthropic, Gemini, routing) | ✅ | — |
| Which model/endpoint/credentials to use | — | ✅ `ModelProfile` |
| RAG **engine** (embeddings, store, ingestor, retriever) | ✅ | — |
| The domain **corpus** + glossary to ingest | — | ✅ `KnowledgeSource[]`, `PromptProfile.domainGlossary` |
| Tool **registry + dispatch** + MCP client | ✅ | — |
| The host's **Java-API tools** + MCP servers | — | ✅ `ToolProvider` |
| Orchestration / planning / safety / audit **engine** | ✅ | — |
| **Navigation targets** (page ids, KPI/report routes, params) | — | ✅ `NavigationCatalog` |
| System prompts / persona | generic defaults | ✅ `PromptProfile` (domain) |
| Role mapping + capability grants | RBAC engine | ✅ `PolicyProfile` (product roles) |
| Deployment profile + feature gates | matrix engine | ✅ `PackConfig` (this install) |
| Eval **harness** | ✅ | golden **cases** in the pack |

Rule of thumb: **mechanism is core; policy/content/bindings are the pack.**

## 4. The Application Pack SPI

A product implements `ApplicationPack` (see [`02-domain-model.md` §Application Pack SPI](02-domain-model.md#application-pack-spi--comeoiagentapp)
and the [spec](../specs/application-pack.md)). It returns eight providers:

| Provider | Supplies | Drives core component |
|----------|----------|-----------------------|
| `PackMetadata` | `AppId`, name, version | identity / audit |
| `ModelProfile` | chat + embedding `ModelSelection`, `RoutingPolicy` | Model Access (`RoutingLlmGateway`) |
| `KnowledgeSource[]` | corpus sources (`PRODUCT_DOC`/`CONFIG_FILE`/`SCHEMA_CONFIG`/`CUSTOM`) | Knowledge/RAG (`DocumentIngestor`) |
| `ToolProvider` | host `@Tool`s + `McpServerRef`s | Tools (`ToolRegistry`) |
| `NavigationCatalog` | `PageDescriptor[]` (page id, params) | Host Integration (`NavigationIntent`) |
| `PromptProfile` | domain system prompts, persona, glossary | Runtime / Model |
| `PolicyProfile` | host-role→`Role`, `Capability` grants | Safety (`PolicyEngine`) |
| `PackConfig` | `DeploymentProfile`, `Feature` overrides, `eoiagent.*` defaults | Config |

The SPI lives in `eoiagent-app-api` (`com.eoiagent.app`). A pack depends on **only**
`eoiagent-app-api` + `eoiagent-bom` — never on core internals or adapter modules.

## 5. Bootstrap / assembly flow (`eoiagent-platform`)

> The "instantiate the agent for this product" entry point — the only code the host calls at
> startup. See [Flow 0 in `04-sequence-flows.md`](04-sequence-flows.md#flow-0--platform-bootstrap-startup).

```
host startup:
  AgentPlatform platform = new PlatformBuilder()
      .pack(productPack)            // the one ApplicationPack on the classpath
      .start();                     // validate → wire → ingest → ready
  AgentService svc = platform.agentService();

PlatformBuilder.start():
  1. validate(pack)                                   // metadata, required providers present
  2. ConfigProvider ← merge(PackConfig defaults, host overrides)   // profile + feature gates
  3. LlmGateway     ← build from ModelProfile (offline fail-closed per profile)
  4. Knowledge      ← ingest(KnowledgeSource[]) into the configured VectorStore
  5. ToolRegistry   ← register(ToolProvider.tools + mcpServers), classify read-only/mutating
  6. PolicyEngine   ← from PolicyProfile;  Guardrails, ApprovalGate, AuditSink ← per config
  7. Orchestrator   ← uses PromptProfile (system prompts/persona) + NavigationCatalog
  8. AgentService   ← host facade bound to the above;  return AgentPlatform (AutoCloseable)
```

`AppId` from `PackMetadata` is stamped into every `AgentContext` and `AuditEvent`, so the audit
trail always records which product/pack acted.

## 6. Dependency direction (updated)

```
host app ──► eoiagent-platform ──► core (ports + adapters)
                  ▲                        ▲
   Application ───┘ (implements)           │
   Pack  ─────────► eoiagent-app-api ──────┘ (SPI depends only on core domain types)

CORE never depends on any Application Pack. The Pack depends only on eoiagent-app-api + the BOM.
Experimental deps stay quarantined in core adapter modules (ADR-0010) — a pack never sees them.
```

ArchUnit rules (extend the Phase-0 set): (a) `eoiagent-app-api` imports only `eoiagent-core`
domain types; (b) no `com.eoiagent.app.*` reference appears in any core module; (c) a pack module
imports `com.eoiagent.app` + domain types only — not core adapters.

## 7. Onboarding a new product (the agent workflow)

1. Copy the **reference pack** (`eoiagent-app-reference`, see
   [`../specs/reference-app-pack.md`](../specs/reference-app-pack.md)) to `<product>-agent-pack`.
2. Fill the eight providers for the product: pick models (`ModelProfile`), point at the corpus
   (`KnowledgeSource[]`), bind the host's Java-API tools (`ToolProvider`), list pages
   (`NavigationCatalog`), write domain prompts/glossary (`PromptProfile`), map roles
   (`PolicyProfile`), set profile + gates (`PackConfig`).
3. Add the product's golden cases to the eval set.
4. `PlatformBuilder().pack(new <Product>Pack()).start()` — run the pack's eval suite offline.

No core code changes. This is the entire per-product surface.

## 8. Consequences

- **Reuse:** one core, N products; upgrades to core benefit every product.
- **Isolation:** a product's domain knowledge, tools, and prompts never leak into core or another
  product.
- **Agent-friendly onboarding:** a single, well-typed module to fill in, with a worked template.
- **Cost:** one more contract (the SPI) and an assembly module; mitigated because the SPI is small
  and the bootstrap is mechanical.
- **Multi-pack later:** with one-pack-per-deployment now, `AgentService`/audit already carry
  `AppId`, so a future `AppPackRegistry` (route by `AppId`, isolate per-pack stores) is additive —
  no breaking change. ([ADR-0011](../adr/0011-core-and-application-pack-split.md) §Alternatives)
