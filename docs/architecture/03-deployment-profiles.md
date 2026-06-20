# 03 — Deployment Profiles & Capability Matrix

> The platform ships as **one library** that behaves differently per **`DeploymentProfile`**.
> A client picks a profile at install; the `ConfigProvider` enforces the capability matrix so a
> disabled feature is never reachable (not just hidden). Types: see
> [`02-domain-model.md`](02-domain-model.md). Spec: [`../specs/config-profiles.md`](../specs/config-profiles.md).
>
> The active profile, any `Feature` overrides, and `eoiagent.*` defaults are supplied per-product
> by the Application Pack's `PackConfig` (and may be overridden by host config) — see
> [`05-core-and-application-packs.md`](05-core-and-application-packs.md) and
> [`../specs/application-pack.md`](../specs/application-pack.md). The capability matrix below is the
> hard ceiling a pack cannot exceed.

## The three profiles

| Profile | Where it runs | Model placement | Network |
|---------|---------------|-----------------|---------|
| `OFFLINE` | Air-gapped / on-prem, no internet | In-JVM (ONNX embeddings) + local LLM server (Ollama / llama.cpp / vLLM on localhost) | none |
| `ON_PREM_HOSTED` | On-prem with a bigger model on the network | Local embeddings + on-prem model server (OpenAI-compatible endpoint on the LAN) | LAN only |
| `CLOUD` | Vendor cloud / client cloud | Hosted model (OpenAI / Anthropic / Gemini) or cloud-hosted local model | egress allowed |

Per the brainstorm: *"One client may put it on the cloud, one on-premise with hosted model,
another completely offline."* Online and offline are **equal-priority** — both are tested in CI.

## Inference placement (independent of profile)

The framework supports two inference placements; a profile may use either:
1. **In-JVM** — embeddings always run in-JVM (ONNX). A small chat model *may* also run in-JVM
   in future, but MVP assumes a **local server** for chat.
2. **Separate model server on the network** — Ollama / vLLM / llama.cpp / LM Studio reached via
   the OpenAI-compatible `baseUrl`. Same code path for "local server" and "on-prem hosted" — only
   the URL and model id differ.

## Capability matrix

`Feature` (from the domain model) × profile. `ConfigProvider.featureEnabled(...)` returns these
defaults; a client may further restrict, never loosen beyond what the profile allows.

| `Feature` | OFFLINE | ON_PREM_HOSTED | CLOUD | Notes |
|-----------|:-------:|:--------------:|:-----:|-------|
| `HOSTED_MODELS` | ✗ | ✗ (LAN model only) | ✓ | hosted = internet egress |
| `MUTATING_ACTIONS` | ✓ (gated) | ✓ (gated) | ✓ (gated) | always behind `ApprovalGate` |
| `MCP_TOOLS` | local stdio only | ✓ | ✓ | remote MCP needs network |
| `PGVECTOR` | ✓ (local PG) | ✓ | ✓ | else `InMemoryVectorStore` |
| `LANGGRAPH_CHECKPOINTING` | ✓ | ✓ | ✓ | Phase 3 |
| `ADVANCED_RETRIEVAL` | ✓ (local re-rank) | ✓ | ✓ | Phase 2 |
| `LONG_TERM_MEMORY` | ✓ | ✓ | ✓ | Phase 3 |

> Rule: **no feature is enabled by being present on the classpath.** It is enabled only when the
> profile's matrix allows it *and* config turns it on. This keeps offline installs provably
> network-free.

## Defaults per profile

| Setting | OFFLINE | ON_PREM_HOSTED | CLOUD |
|---------|---------|----------------|-------|
| `eoiagent.model.chat.provider` | `openai-compatible` (localhost) or `ollama` | `openai-compatible` (LAN) | `anthropic` / `openai-compatible` / `gemini` |
| `eoiagent.model.embedding.provider` | `onnx-all-minilm` | `onnx-all-minilm` | `onnx-all-minilm` (or hosted) |
| `eoiagent.vectorstore.kind` | `in-memory` or `pgvector` | `pgvector` | `pgvector` |
| `eoiagent.approval.required` | `true` | `true` | `true` |
| `eoiagent.audit.sink` | `file` / `jdbc` | `jdbc` | `jdbc` |

## Routing & fallback (`RoutingLlmGateway`)

- `CLOUD`: try hosted → fall back to local server if configured.
- `ON_PREM_HOSTED`: LAN model only; no internet fallback.
- `OFFLINE`: local only; **fail closed** (never attempt network). Any adapter that would touch
  the network must check `featureEnabled(HOSTED_MODELS)` and refuse otherwise.

## Testing requirement

CI runs the full eval suite under **OFFLINE** (local model via a containerized Ollama or a
stub `LlmGateway`) and at least one online profile. A feature marked ✗ for OFFLINE must have a
test proving it is unreachable in that profile. See [`../specs/eval-harness.md`](../specs/eval-harness.md).
