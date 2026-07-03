# HOWTO — build, run, and embed the EOI Agent

Task-oriented guide for working with the platform. For *what it is* and *why*, see
[`README.md`](README.md); for the contracts, [`docs/`](docs/).

- [Prerequisites](#prerequisites)
- [Build and test](#build-and-test)
- [Run the demos](#run-the-demos)
- [Embed the platform in a host app](#embed-the-platform-in-a-host-app)
- [Write an Application Pack](#write-an-application-pack)
- [Phase-2 capabilities (approval, delegation, retrieval, MCP, guardrails)](#phase-2-capabilities)
- [Optional infrastructure (Ollama, Postgres, MCP)](#optional-infrastructure)
- [Run the eval harness](#run-the-eval-harness)
- [Troubleshooting](#troubleshooting)

---

## Prerequisites

| Need | For |
|------|-----|
| **JDK 25+** (JDK 26 works; the build compiles `--release 25`) | everything |
| **Maven 3.9+** | build/test/run |
| *(optional)* local **Ollama** at `localhost:11434` | run demos against a real local LLM |
| *(optional)* **PostgreSQL** (+ `pgvector`) | JDBC audit, Postgres chat memory, pgvector store |
| *(optional)* **Node/npx** | a live MCP server for the MCP integration test |

Everything builds and every demo/unit test runs **fully offline** — the optional pieces only enable
the env-gated integration tests and the "use a real model" path.

## Build and test

```bash
mvn clean install            # build all 18 modules + run the test suite (offline, green)
mvn -q -DskipTests install   # faster: build only, skip tests
mvn -pl eoiagent-runtime test    # test a single module (install the reactor once first)
```

The default `mvn test` is **offline and green** with no network, no model, and no database — the
integration tests that need external infra are env-gated (see [below](#optional-infrastructure)) and
skip when their env var is unset.

## Run the demos

The [`eoiagent-examples`](eoiagent-examples) module has runnable, narrated demos. They run offline
against a deterministic stub model, and auto-detect a local Ollama at `localhost:11434` if present.

```bash
mvn -q -DskipTests install                       # build once
mvn -q -pl eoiagent-examples exec:java           # run ALL demos
mvn -q -pl eoiagent-examples exec:java -Deoiagent.demo.offline=true   # force offline (ignore Ollama)

# run one demo:
mvn -q -pl eoiagent-examples exec:java -Dexec.mainClass=com.eoiagent.examples.MutatingApprovalDemo
```

| Demo | Shows |
|------|-------|
| `PlatformBootstrapDemo` | Assembling a usable `AgentService` from a pack in one call (Flow 0) |
| `RagAndToolsDemo` | The bundled knowledge corpus + invoking the read-only tools |
| `NavigationDemo` | The navigation catalog and a validated `NavigationIntent` |
| `PolicyAndProfilesDemo` | Host-role → `Role` mapping, capability grants, OFFLINE config |
| `QaSessionDemo` | An end-to-end Q&A session with the recorded audit trail |
| `MutatingApprovalDemo` | **Flow C** — dry-run → approve/deny a mutating action + RBAC enforcement |
| `SupervisorDemo` | **Flow D** — a supervisor delegating to an isolated sub-agent |
| `SummarizingMemoryDemo` | Condensing evicted turns into a running summary |
| `AdvancedRetrievalDemo` | Query rewrite + routing + re-rank vs naive vector search |
| `McpGatingDemo` | Gating MCP-backed tools on the `MCP_TOOLS` feature |
| `OutputGuardrailDemo` | Schema output validation with a bounded reprompt (PASS/RETRY/FAIL) |
| `Phase2EvalDemo` | Scoring an agent against a golden suite with the eval harness |

## Embed the platform in a host app

The host depends on `eoiagent-platform` (+ your Application Pack), assembles an `AgentPlatform`, then
opens sessions and asks. The pack supplies the models, knowledge, tools, navigation, prompts, roles,
and config — see [next section](#write-an-application-pack).

```java
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.core.*;
import java.time.Instant;
import java.util.Map;

// PlatformBuilder validates the pack and wires the engine (Flow 0). The pack's ModelProfile
// builds the LLM gateway; .auditSink(...) and .llmGateway(...) are optional overrides.
try (AgentPlatform platform = new PlatformBuilder()
        .pack(new ReferenceApplicationPack())   // <- your ApplicationPack
        .start()) {

    AgentSession session = platform.agentService().open(new SessionRequest(
            new UserId("user-1"),
            Role.ANALYST,
            DeploymentProfile.OFFLINE,            // MUST match the pack's configured profile
            null,                                  // optional PageContext (page-aware help)
            Map.of()));

    AgentAnswer answer = session.ask(new UserMessage("Which datasets can I query?", null, Instant.now()));
    System.out.println(answer.kind() + ": " + answer.text());   // TEXT / NAVIGATION / CLARIFICATION / ...
    session.close();
}
```

The `SessionRequest` profile must equal the pack's configured `DeploymentProfile` (the service
validates and fails closed otherwise). [`QaSessionDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/QaSessionDemo.java)
is a complete working version of the above.

## Write an Application Pack

A pack is a typed implementation of the `ApplicationPack` SPI in `eoiagent-app-api` — one per product
/ deployment. It compiles against **only** `eoiagent-app-api` (which transitively brings the
`eoiagent-core` domain types); it never depends on an adapter module or an agent framework (enforced
by an architecture test).

Implement the eight providers:

| Provider | Supplies |
|----------|----------|
| `PackMetadata` | id (`AppId`), name, version |
| `PackConfig` | `DeploymentProfile`, config defaults, feature overrides |
| `ModelProfile` | chat + embedding model selection (provider, model id, routing) |
| `KnowledgeSource[]` | the RAG corpus (docs / schema / pipeline config) |
| `ToolProvider` | the host `Tool`s and any MCP server refs |
| `NavigationCatalog` | the pages the agent may route the user to |
| `PromptProfile` | system-prompt fragments |
| `PolicyProfile` | the role → capability grants |

Copy [`eoiagent-app-reference`](eoiagent-app-reference) (the worked **Acme Lakehouse** pack) as your
starting template; read [`docs/specs/application-pack.md`](docs/specs/application-pack.md) and
[`docs/architecture/05-core-and-application-packs.md`](docs/architecture/05-core-and-application-packs.md).
A new product is a new pack — never a fork of CORE.

## Phase-2 capabilities

The Phase-2 features are implemented as adapters behind the core ports. The current
`PlatformBuilder` assembles the **read-only ReAct** path (Flow B); the agentic flows are wired
directly from their adapters today, and each has a runnable demo that is the copy-to-start recipe:

| Capability | Adapter(s) | Recipe |
|------------|-----------|--------|
| Plan → approve → act (mutating) | `CallbackApprovalGate`, `JavaApiTool`, 4-arg `DefaultToolRegistry`, `RoleBasedPolicyEngine` | [`MutatingApprovalDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/MutatingApprovalDemo.java) |
| Supervisor + sub-agents | `SupervisorOrchestrator` | [`SupervisorDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/SupervisorDemo.java) |
| Summarizing chat memory | `SummarizingChatMemory` | [`SummarizingMemoryDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/SummarizingMemoryDemo.java) |
| Advanced retrieval | `AdvancedRetriever` | [`AdvancedRetrievalDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/AdvancedRetrievalDemo.java) |
| MCP tools | `McpToolAdapter` (+ `MCP_TOOLS` gate) | [`McpGatingDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/McpGatingDemo.java) |
| Output guardrail | `SchemaOutputGuardrail` | [`OutputGuardrailDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/OutputGuardrailDemo.java) |
| Stateful investigation (Phase 3, started) | `LangGraphOrchestrator` (LangGraph4j) | — (T-301; checkpoint store + HITL land in T-302/T-303) |

Mutating actions are gated on the `MUTATING_ACTIONS` feature and are **not permitted under the
`OFFLINE` profile** — they run under `ON_PREM_HOSTED` / `CLOUD`. No mutation ever executes without a
preceding recorded `APPROVED` decision.

## Phase-3.5 integration: the live path, closed

Phase 3.5 wires the tested components into the *assembled* platform, so they work through
`platform.agentService()` — not just in unit tests. Two common misconceptions these features
correct:

**"The model executes tools."** It does not — the model only *asks* for a tool call; the
platform's audited `ToolRegistry` executes it and feeds the result back. Since T-350 this works
with **real models** (Ollama / OpenAI-compatible): tool schemas are sent with each request, the
model's tool calls come back typed, and results are replayed paired with the provider's call id.
Any model served via Ollama or an OpenAI-compatible endpoint can drive tools — no per-model code
(see [ADR-0013](docs/adr/0013-pluggable-models.md)).

**"The model remembers my last question."** Models are stateless. Since T-351 the platform stores
each USER/ASSISTANT turn in a `MemoryStore` (in-memory by default; inject
`PlatformBuilder.memoryStore(new PostgresMemoryStore(ds))` to survive restarts) and replays the
recent transcript — bounded by `eoiagent.runtime.memory.maxMessages` (default 20) — into every
model call. Tool chatter never enters the transcript. Watch it happen:
[`MultiTurnMemoryDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/MultiTurnMemoryDemo.java).

**"RAG means the model knows our documents."** It doesn't — nothing is taught to the model. Since
T-352 the platform ingests the pack's corpus at bootstrap and, for every QA turn, embeds the
question, vector-searches, and injects only the top-k chunks (`eoiagent.runtime.rag.topK`) into
that single call. The `RETRIEVAL` is audited and the answer carries `Citation`s tracing it to
sources. See
[`RagCitationsDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/RagCitationsDemo.java).

| Capability | Where it lives | Recipe |
|------------|----------------|--------|
| Real-model tool calling (T-350) | `Lc4jChatGateway` + `ToolMapping`/`ToolCallMeta` | tool round-trip test: `Lc4jChatGatewayToolCallTest` |
| Session memory in the loop (T-351) | `ReActOrchestrator` + `PlatformBuilder.memoryStore(...)` | [`MultiTurnMemoryDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/MultiTurnMemoryDemo.java) |
| RAG + citations in the loop (T-352) | `PlatformBuilder` knowledge stack + `ReActOrchestrator.builder().retriever(...)` | [`RagCitationsDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/RagCitationsDemo.java) |
| Typed NavigationIntent (T-353) | reserved `navigate_to_page` tool from the pack's `NavigationCatalog` | [`LiveNavigationDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/LiveNavigationDemo.java) |
| Config-first model swap (T-354) | `eoiagent.model.chat.*` keys override the pack `ModelProfile` ([ADR-0013](docs/adr/0013-pluggable-models.md)) | [`ConfigSwapModelDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/ConfigSwapModelDemo.java) |
| Mutating approval via platform (T-354) | `PlatformBuilder.approvalHandler(...)` + `MUTATING_ACTIONS`; headless = fail-closed DENIED | `PlatformWiringV2Test` (C4 asserted end-to-end) |
| Policy ceiling (T-354) | pack `PolicyProfile` = restriction overlay on `RoleBasedPolicyEngine` — packs narrow, never widen | `PlatformWiringV2Test` |
| Real token streaming (T-355) | `askStream` → `Orchestrator.run(goal, ctx, onToken)` → `LlmGateway.chatStream` | [`StreamingAnswerDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/StreamingAnswerDemo.java) |

**"The agent operates the UI."** It never does — navigation answers are *typed intents*
(`AgentAnswer(NAVIGATION, NavigationIntent)`) the model proposes via the reserved
`navigate_to_page` tool, validated against the pack's `NavigationCatalog` (bad proposals bounce
back so the model self-corrects). Your product decides how — and whether — to route.

## Optional infrastructure

### A real local LLM (Ollama)
Install [Ollama](https://ollama.com), `ollama pull qwen2.5:14b-instruct`, and the demos use it
automatically. Or point a pack's `ModelProfile` at any OpenAI-compatible endpoint (llama.cpp / vLLM /
LM Studio).

### PostgreSQL — JDBC audit, Postgres memory, pgvector
The Postgres-backed adapters have integration tests that are **skipped unless** their env var is set
(so the default build stays offline). Provide a JDBC URL to run them:

```bash
EOIAGENT_IT_PG_URL="jdbc:postgresql://localhost:5432/eoiagent?user=postgres&password=postgres" \
  mvn -pl eoiagent-observability,eoiagent-memory test     # JdbcAuditSink + PostgresMemoryStore

EOIAGENT_IT_PGVECTOR_URL="jdbc:postgresql://localhost:5433/eoiagent?user=postgres&password=postgres" \
  mvn -pl eoiagent-knowledge test                          # PgVectorStore (needs the pgvector extension)
```

(On Windows PowerShell: `$env:EOIAGENT_IT_PG_URL="…"; mvn …`.)

### A live MCP server
```bash
EOIAGENT_IT_MCP=1 mvn -pl eoiagent-tool test   # connects to npx @modelcontextprotocol/server-everything
```

## Run the eval harness

The eval harness scores an `AgentService` against a YAML golden suite (answer kind + text match + tool
calls/citations reconstructed from the audit trail). It runs offline against a scripted agent.

- Golden suites: `eoiagent-eval/src/test/resources/eval/**` (`phase1-golden`, `phase2-golden`).
- See it run: `mvn -pl eoiagent-eval test`, or the self-contained
  [`Phase2EvalDemo`](eoiagent-examples/src/main/java/com/eoiagent/examples/Phase2EvalDemo.java).
- Spec: [`docs/specs/eval-harness.md`](docs/specs/eval-harness.md).

## Troubleshooting

- **`mvn -pl X test` fails to find tests after editing pom/compiler config** — stale `.class` files;
  run `clean` (`mvn -pl X clean test`).
- **Demo console shows `�`** — the Windows console renders em-dashes as mojibake; demo output is
  ASCII. Cosmetic only.
- **A Postgres/pgvector/MCP test is "skipped"** — expected: its env var (above) is unset.
- **`graphify` command not found** — the knowledge-graph CLI referenced in `CLAUDE.md` hooks is not
  installed here; it is optional and not needed to build, test, or run anything.
