# ADR-0002: Target JDK 25 + Maven; standardize on the JDK HttpClient transport

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

Constraint **C7** fixes the runtime: **JDK 25, Maven**. The product team prefers JDK 25
over an LTS for its language and runtime improvements, and accepts owning the risk of a
newer JDK.

This creates two frictions with our chosen substrate:

1. **Library JDK targeting.** LangChain4j and LangGraph4j (see
   [ADR-0003](0003-foundation-langchain4j-bom.md),
   [ADR-0005](0005-orchestration-agentic-then-langgraph4j.md)) are tested primarily on
   **JDK 17/21**. JDK 25 is not their primary CI target, so we must validate it ourselves.
2. **HTTP transport.** There is a known **Netty `IO_Uring` event-loop slow-shutdown issue
   on JDK 25 (Netty issue #16174)**. Several LangChain4j providers can be backed by a
   Netty-based HTTP client, which would expose us to that defect on our chosen JDK.

We need a deterministic, well-supported HTTP path that does not depend on Netty behaving
correctly on JDK 25.

## Decision

- **`maven.compiler.release = 25`**, built with **Maven 3.9+**.
- **Standardize on the JDK `java.net.http.HttpClient`-based LangChain4j HTTP client** for
  every HTTP adapter, and **avoid Netty-based transports**. This is the transport referred
  to throughout the architecture docs ("All HTTP adapters use the JDK `HttpClient`
  transport", [`../architecture/01-component-model.md`](../architecture/01-component-model.md)
  §Component 1; [`../conventions.md`](../conventions.md) §6).
- A **shared `HttpClient` per gateway**; resource-holding adapters implement `AutoCloseable`
  and are closed by the owning runtime.
- Add a **CI job that builds and runs the full suite on JDK 25**, so library-on-JDK-25
  breakage surfaces in our pipeline rather than in a client deployment.

When configuring providers (`langchain4j-open-ai`, `langchain4j-anthropic`,
`langchain4j-google-ai-gemini`, `langchain4j-ollama`), pick the HttpClient-backed builder
options rather than any Netty-backed default.

## Consequences

**Positive**
- One HTTP stack across all model adapters; no Netty event-loop lifecycle to manage or to
  shut down slowly on JDK 25.
- The JDK `HttpClient` ships with the JDK we already require — fewer transitive deps, fewer
  CVE surfaces, virtual-thread-friendly.

**Negative / follow-ups**
- We must verify each provider adapter offers an HttpClient-backed configuration and select
  it explicitly; defaults can silently be Netty.
- We carry the cost of a dedicated JDK 25 CI job and of being an early adopter ahead of the
  upstream libraries' primary test matrix.

**Risks / mitigation**
- Risk: a provider only ships a Netty transport. Mitigation: this ADR is the revisit
  trigger — we either contribute/await an HttpClient option, route that provider through
  the OpenAI-compatible client ([ADR-0006](0006-local-llm-portability-openai-compatible.md)),
  or quarantine the Netty dependency in a single adapter module and document the JDK 25
  caveat.

## Alternatives considered

- **JDK 21 LTS** — the safer, better-tested-by-upstream target. Rejected per the product
  team's explicit preference for JDK 25; we accept and mitigate the early-adopter risk via
  the dedicated CI job.
- **Netty-based HTTP transport** — the common default for several providers, but exposes us
  to the JDK 25 `IO_Uring` slow-shutdown defect (Netty #16174). Rejected on JDK 25.
