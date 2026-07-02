---
type: spec
title: "Model Gateway — Spec"
description: "Unified chat + embedding model access with local/hosted routing and per-profile fallback."
timestamp: "2026-06-20T20:33:32+05:30"
tags: ["model-gateway"]
---
# Model Gateway — Spec

> Unified chat + embedding model access with local/hosted routing and per-profile fallback.
> Component 1 in [01-component-model.md](../architecture/01-component-model.md). Port(s): `LlmGateway`.

## Purpose

Give the rest of the platform a single, stable way to call **chat** and **embedding** models
without knowing where the model runs (in-JVM, localhost server, LAN server, or vendor cloud).
The gateway:

- Wraps each model backend (Ollama, OpenAI-compatible, Anthropic, Gemini) behind one port.
- Routes a request to local vs hosted models and **falls back** according to the active
  `DeploymentProfile` (see [03-deployment-profiles.md](../architecture/03-deployment-profiles.md) §Routing & fallback).
- Streams tokens for the host's `askStream` surface.
- Reports which model actually answered (for audit) and whether a `ModelRole` is reachable.

It does **not** decide *what* to send (that is the Orchestrator/prompt) or persist anything
(that is Memory/Audit). It is the substrate the ReAct loop in
[Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1) calls.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-1--model-access---port-llmgateway), copied verbatim:

```java
package com.eoiagent.model;

public interface LlmGateway {
    ChatResult chat(ChatRequest request);                 // blocking
    void chatStream(ChatRequest request, TokenSink sink); // streaming tokens
    EmbeddingResult embed(EmbeddingRequest request);
    ModelInfo activeChatModel();                           // which model answered (audit)
    boolean isAvailable(ModelRole role);                   // CHAT / EMBEDDING
}
```

Contract notes:

- **`chat(ChatRequest)`** — Blocking. `request` non-null; `request.messages()` non-empty.
  `request.tools()` may be empty (no tool calling). Returns a non-null `ChatResult`; `text()`
  may be empty when the model only emits tool calls (`toolCalls()` non-null, possibly empty).
  `model()` reflects the adapter that produced the result. Throws `ModelUnavailableException`
  when no eligible backend responds (after fallback). Thread-safe: callable concurrently across
  sessions (a shared `HttpClient` per gateway is reused).
- **`chatStream(ChatRequest, TokenSink)`** — Non-blocking-producer / blocking-until-complete:
  emits partial tokens to `sink` and returns when the stream terminates. `sink` non-null. On
  completion the sink receives a terminal signal carrying the final `ChatResult`; on error the
  sink receives the typed exception. Same fallback semantics as `chat`. One in-flight stream per
  call; `TokenSink` implementations need not be thread-safe (called from a single producer).
- **`embed(EmbeddingRequest)`** — Blocking. `request.inputs()` non-empty, no null elements.
  Returns vectors in input order; `vectors().size() == inputs().size()`. Each vector has fixed
  dimensionality per active embedding model (384 for `all-MiniLM`). Never returns null vectors.
  Throws `ModelUnavailableException` if the embedding backend is unreachable.
- **`activeChatModel()`** — Returns the `ModelInfo` of the backend that served the most recent
  successful `chat`/`chatStream`, or the configured primary if none has run yet. Never null.
- **`isAvailable(ModelRole)`** — Cheap reachability probe (no full inference). For `CHAT`,
  checks the primary chat backend; for `EMBEDDING`, the embedding backend. Must **not** perform a
  network call in `OFFLINE` when the only reachable backend would require egress; returns `false`
  fail-closed instead. Never throws.

`TokenSink` (this module's small SPI, lives in `com.eoiagent.model`):

```java
public interface TokenSink {
    void onToken(String token);
    void onComplete(ChatResult result);
    void onError(Throwable error);
}
```

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `OllamaChatAdapter` | `dev.langchain4j:langchain4j-ollama` (BOM) | **Phase 1** | local chat; JDK HttpClient transport |
| `OllamaEmbeddingAdapter` | `dev.langchain4j:langchain4j-ollama` (BOM) | Phase 1 (optional) | alt embedding path; ONNX is the default (see rag-knowledge) |
| `OpenAiCompatibleChatAdapter` | `dev.langchain4j:langchain4j-open-ai` (BOM) | **Phase 1** | `baseUrl` for llama.cpp / vLLM / LM Studio / Ollama `/v1` |
| `AnthropicChatAdapter` | `dev.langchain4j:langchain4j-anthropic` (BOM) | Phase 1 (CLOUD only) | hosted; gated by `HOSTED_MODELS` |
| `GeminiChatAdapter` | `dev.langchain4j:langchain4j-google-ai-gemini` (BOM) | Phase 1 (CLOUD only) | hosted; gated by `HOSTED_MODELS` |
| `RoutingLlmGateway` | (ours, no third-party) | **Phase 1** | picks primary + fallback per `DeploymentProfile`; the public `LlmGateway` |
| `StubLlmGateway` | (ours, test-fixtures) | Phase 1 | deterministic, no network — default for all LLM-dependent tests |

`RoutingLlmGateway` is the composition root: it holds an ordered chain of delegate adapters
(primary then fallbacks) per `ModelRole` and is the instance injected into the runtime. The
single shared JDK `HttpClient` lives here and is passed to each HTTP adapter; the gateway is
`AutoCloseable` and closes its delegates and client.

## Maven coordinates

- **This module:** `com.eoiagent:eoiagent-model` (version `0.1.0-SNAPSHOT`, inherits parent).
- **Ports + domain types:** `com.eoiagent:eoiagent-core` (`LlmGateway`, `TokenSink` live here or
  in `com.eoiagent.model`; the `*Request`/`*Result` records are core domain types).
- **Third-party (versions via `eoiagent-bom` → `langchain4j-bom:1.16.3`, never hardcoded):**
  `langchain4j` (core), `langchain4j-ollama`, `langchain4j-open-ai`, `langchain4j-anthropic`,
  `langchain4j-google-ai-gemini`.
- HTTP transport is the **JDK `HttpClient`** ([ADR-0002](../adr/0002-jdk25-maven-httpclient.md));
  do not add Netty-based transport artifacts. Configure each LC4j model builder to use the
  JDK-HttpClient `HttpClientBuilder` provided by `langchain4j-http-client-jdk` (BOM-aligned).

## Inputs / Outputs

Consumed (from [02-domain-model.md](../architecture/02-domain-model.md#models)):
`ChatRequest(List<ChatMessageRecord> messages, List<ToolSpec> tools, ChatOptions options)`,
`EmbeddingRequest(List<String> inputs)`, `ModelRole{CHAT, EMBEDDING}`, and `AgentContext` (for
the profile check).

Produced:
`ChatResult(String text, List<ToolCall> toolCalls, ModelInfo model, Usage usage)`,
`EmbeddingResult(List<float[]> vectors, ModelInfo model)`,
`ModelInfo(String provider, String modelId, boolean local)`.

Tool-calling: `ChatRequest.tools()` carries `ToolSpec.jsonSchema()`; adapters translate these to
LC4j tool specifications and map model tool invocations back into `ToolCall` records. The gateway
never executes tools — it returns the requested `ToolCall`s for `ToolRegistry.dispatch` (Flow B).

## Behavior / algorithm

Implements the model-access steps of
[Flow A step 3](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1)
and [Flow B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1).

`RoutingLlmGateway.chat(request)`:

1. Resolve the **candidate chain** for `ModelRole.CHAT` from config: `[primary, fallback...]`.
   The chain is built once at construction from `eoiagent.model.chat.*` keys + profile.
2. **Profile gate:** if a candidate is a hosted adapter (Anthropic/Gemini/remote OpenAI), assert
   `configProvider.featureEnabled(HOSTED_MODELS)`. If not enabled, skip it; if it is the *only*
   candidate, throw `PolicyViolation` (offline fail-closed — never silently hit the network).
3. Try candidates in order. On `ModelUnavailableException` / connect/timeout from a candidate,
   record it and advance to the next.
   - `OFFLINE`: chain contains local candidates only; no network attempt ever.
   - `ON_PREM_HOSTED`: LAN OpenAI-compatible endpoint only; no internet fallback.
   - `CLOUD`: hosted primary → local-server fallback if configured.
4. On the first success, set `activeChatModel = result.model()` and return.
5. If all candidates fail, throw `ModelUnavailableException` listing the attempted backends.

`chatStream` mirrors steps 1–4 but streams via the adapter's streaming client into `TokenSink`;
fallback only applies before the first token is emitted (mid-stream failure → `onError`).

`embed(request)`: resolve the `ModelRole.EMBEDDING` chain (default `onnx-all-minilm`, which is
in-JVM and provided by the Knowledge module's `OnnxEmbeddingAdapter`; the gateway may also use
`OllamaEmbeddingAdapter`). Same profile gate; no network for the ONNX path.

`isAvailable(role)`: probe the primary candidate's health (Ollama `/api/tags`, OpenAI-compatible
`/v1/models`, or ONNX model-loaded check) with a short timeout; return false (never throw) on
failure or when a probe would require egress in `OFFLINE`.

## Configuration keys

Read via `ConfigProvider` (defaults differ per profile — see
[03-deployment-profiles.md](../architecture/03-deployment-profiles.md#defaults-per-profile)):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) |
|-----|------|--------------------------------------------|
| `eoiagent.model.chat.provider` | String | `openai-compatible` or `ollama` / `openai-compatible` / `anthropic`\|`openai-compatible`\|`gemini` |
| `eoiagent.model.chat.baseUrl` | String | `http://localhost:11434/v1` / LAN URL / (n/a for hosted) |
| `eoiagent.model.chat.modelId` | String | `qwen2.5:14b-instruct` (example) |
| `eoiagent.model.chat.apiKey` | String | (unset) / (unset) / required for hosted |
| `eoiagent.model.chat.fallback.provider` | String | (unset) / (unset) / `openai-compatible` |
| `eoiagent.model.chat.fallback.baseUrl` | String | (unset) |
| `eoiagent.model.chat.timeoutMs` | Integer | `60000` |
| `eoiagent.model.embedding.provider` | String | `onnx-all-minilm` (all profiles) |

Hosted keys are only honored when `featureEnabled(HOSTED_MODELS)` is true.

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- `ModelUnavailableException` — a backend is unreachable / errors / times out, and no eligible
  fallback succeeds. Carries the list of attempted `ModelInfo`s.
- `PolicyViolation` — a hosted backend is required but `HOSTED_MODELS` is disabled for the
  profile (offline fail-closed). Thrown *before* any socket is opened.
- `ConfigException` — provider/baseUrl/apiKey missing or inconsistent at construction.
- Adapters never swallow exceptions: each model call emits a `MODEL_CALL` (or `ERROR`)
  `AuditEvent` upstream (the runtime records audit; the gateway exposes `activeChatModel()` and
  `usage` for that event). The gateway converts library exceptions into the typed hierarchy and
  rethrows.

Offline guarantee: in `OFFLINE`, construction rejects any hosted provider; `isAvailable` and the
routing chain never open a network socket. Asserted by the network-deny test harness (invariant 3
in [04-sequence-flows.md](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)).

## Acceptance criteria

1. **AC1** Given a `ChatRequest` and a reachable local OpenAI-compatible endpoint,
   `chat` returns a `ChatResult` whose `model().local() == true` and `activeChatModel()` matches.
2. **AC2** Given `OFFLINE` profile and `eoiagent.model.chat.provider=anthropic`, gateway
   construction throws `ConfigException` (hosted provider not permitted offline).
3. **AC3** Given `OFFLINE` profile, no call to `chat`, `embed`, or `isAvailable` opens a network
   socket to a non-localhost host (verified by the network-deny harness).
4. **AC4** Given `CLOUD` profile with a hosted primary that fails and a configured
   local-server fallback that succeeds, `chat` returns the fallback's result and
   `activeChatModel().local() == true`.
5. **AC5** Given all candidates unreachable, `chat` throws `ModelUnavailableException` whose
   message lists every attempted backend.
6. **AC6** `chatStream` delivers tokens to `TokenSink.onToken` in order and ends with exactly one
   `onComplete(ChatResult)`; a pre-first-token failure triggers fallback, a mid-stream failure
   triggers `onError`.
7. **AC7** `embed` with the ONNX embedding provider returns 384-dim vectors, one per input, in
   order, with no network call (any profile).
8. **AC8** A `ChatRequest` carrying `ToolSpec`s yields `ChatResult.toolCalls()` mapped 1:1 from
   the model's tool invocations; the gateway does not execute any tool.
9. **AC9** `isAvailable(CHAT)` returns `false` (never throws) when the primary endpoint is down.

## Test plan

All default tests run with **no network and no live LLM**, using `StubLlmGateway` / stubbed
adapters (a fake `HttpClient`/transport). JUnit 5 + AssertJ; Mockito only for the transport seam.

- **Unit** — `RoutingLlmGatewayTest` (fallback order, profile gate, `activeChatModel` tracking),
  `OpenAiCompatibleChatAdapterTest` and `OllamaChatAdapterTest` (request/response mapping against
  a stubbed transport), `TokenSinkStreamingTest`.
- **Contract** — `LlmGatewayContractTest` (shared abstract suite every adapter passes:
  non-null results, vector dimensionality, tool-call passthrough, exception typing).
- **Profile/offline** — `OfflineNetworkDenyTest` asserts AC2/AC3 under `OFFLINE`.
- **Integration (profile-tagged, opt-in)** — `OllamaLiveIT` hits a containerized Ollama in CI
  only; excluded from the default build.
- **Eval** — golden chat prompts feed `StubLlmGateway` recorded responses (see
  [eval-harness.md](eval-harness.md)).

Command: `mvn -pl eoiagent-model -am test` (default profile, no network). Live integration:
`mvn -pl eoiagent-model -Plive-ollama verify`.

## Dependencies on other modules

- `eoiagent-core` — domain records (`ChatRequest`, `ChatResult`, `EmbeddingRequest`,
  `EmbeddingResult`, `ModelInfo`, `ModelRole`, `ToolSpec`, `ToolCall`, `Usage`,
  `ChatMessageRecord`, `ChatOptions`) and the typed exception hierarchy.
- `eoiagent-config` — `ConfigProvider` for keys + `featureEnabled(HOSTED_MODELS)`.
- `eoiagent-knowledge` — provides `OnnxEmbeddingAdapter` (LC4j `EmbeddingModel`) used by the
  embedding route; the gateway depends only on the LC4j `EmbeddingModel` abstraction, not the
  adapter class.
- `eoiagent-observability` — `AuditSink` (the runtime, not the gateway, records `MODEL_CALL`).

## Out of scope / deferred

- In-JVM chat model (small local LLM inside the JVM) — future; MVP assumes a local server.
- Token-budget / cost-aware routing and caching of responses — Phase 2+.
- Multi-region hosted failover and provider-specific rate-limit backoff — Phase 4 hardening.
- Output guardrails (schema/PII) — Component 7, applied by the runtime around the gateway.

## Related ADRs & flows

- [ADR-0002 — JDK 25, Maven, JDK HttpClient](../adr/0002-jdk25-maven-httpclient.md)
- [ADR-0003 — Foundation: LangChain4j BOM](../adr/0003-foundation-langchain4j-bom.md)
- [ADR-0006 — Local LLM portability via OpenAI-compatible baseUrl](../adr/0006-local-llm-portability-openai-compatible.md)
- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- Flows: [A](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1),
  [B](../architecture/04-sequence-flows.md#flow-b--react-loop-with-read-only-tools-phase-1)
- [03 — Deployment profiles & routing/fallback](../architecture/03-deployment-profiles.md#routing--fallback-routingllmgateway)
