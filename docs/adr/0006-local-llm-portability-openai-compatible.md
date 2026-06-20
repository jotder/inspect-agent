# ADR-0006: Standardize local/on-prem model access on the OpenAI-compatible baseUrl client

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The platform must run **fully offline** (constraint **C2**) and **on-prem hosted**
(constraint **C3**), with model placement chosen per `DeploymentProfile`
([`../architecture/03-deployment-profiles.md`](../architecture/03-deployment-profiles.md)).
Clients run a variety of local/on-prem inference engines: **Ollama, llama.cpp, vLLM, LM
Studio**, and on-prem model servers on the LAN.

Most of these engines expose an **OpenAI-compatible HTTP endpoint**. The framework already
treats "local server" and "on-prem hosted" as the same code path differing only in URL and
model id ([`03-deployment-profiles.md`](../architecture/03-deployment-profiles.md)
§Inference placement). Writing a bespoke client per engine would mean churn every time a
client swaps engines, with little benefit.

## Decision

Standardize local/on-prem **chat** model access on the **OpenAI-compatible `baseUrl`
client**:

- Reach Ollama (`/v1`), llama.cpp, vLLM, LM Studio, and on-prem model servers through one
  **`OpenAiCompatibleChatAdapter`** built on **`dev.langchain4j:langchain4j-open-ai`** with
  a configured `baseUrl`, using the JDK `HttpClient` transport
  ([ADR-0002](0002-jdk25-maven-httpclient.md)).
- Keep a **native Ollama adapter** (`OllamaChatAdapter` / `OllamaEmbeddingAdapter` on
  `langchain4j-ollama`) for Ollama-specific behavior where it helps.
- **Swapping engines is a config change, not a code change** — set
  `eoiagent.model.chat.baseUrl` and `eoiagent.model.chat.modelId` (e.g.
  `http://localhost:11434/v1`, `qwen2.5:14b-instruct`); `eoiagent.model.chat.provider =
  openai-compatible | ollama`.
- **In-process embeddings stay ONNX** — `OnnxEmbeddingAdapter`
  (`langchain4j-embeddings-all-minilm-l6-v2`, `AllMiniLmL6V2EmbeddingModel`), in-JVM, zero
  network.
- **Offline must fail closed:** an adapter that would touch the network checks
  `featureEnabled(HOSTED_MODELS)` and throws `PolicyViolation` rather than silently falling
  back ([`../conventions.md`](../conventions.md) §5;
  [`03-deployment-profiles.md`](../architecture/03-deployment-profiles.md) §Routing).

`RoutingLlmGateway` picks/falls back per profile behind the `LlmGateway` port.

## Consequences

**Positive**
- **One code path** for "local server" and "on-prem hosted"; engine choice becomes a
  URL + model-id config edit.
- Directly satisfies C2/C3 — the same library serves OFFLINE, ON_PREM_HOSTED, and CLOUD with
  no per-engine client sprawl.
- Embeddings are always available offline (in-JVM ONNX), independent of the chat engine.

**Negative / follow-ups**
- Engine-specific capabilities not surfaced through the OpenAI-compatible API are not
  reachable via this adapter; if one is needed, add a native adapter behind `LlmGateway`
  (the native Ollama adapter is the precedent).
- We must verify each engine's OpenAI-compatible endpoint quirks (tool-calling, streaming)
  in the per-profile integration tests.

**Risks / mitigation**
- Risk: an offline adapter accidentally reaches the network. Mitigation: fail-closed
  `featureEnabled(HOSTED_MODELS)` checks plus a profile test proving the network path is
  unreachable under OFFLINE.

## Alternatives considered

- **Provider-specific clients per engine** (separate bespoke client for llama.cpp, vLLM, LM
  Studio, …) — maximal fidelity but constant churn as clients change engines. Rejected for
  the default path; a native adapter behind the port is allowed where it earns its keep.
- **In-JVM chat model only** — appealing for true zero-network, but modest client CPU forces
  a small model with weak quality. **Deferred** (embeddings are already in-JVM; MVP assumes
  a local chat *server*).
