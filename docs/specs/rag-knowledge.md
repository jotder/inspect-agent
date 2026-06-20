# RAG / Knowledge — Spec

> Ingest and retrieve over the static corpus (product docs, pipeline/job config files,
> schema/data-model configs) with offline in-process embeddings.
> Component 2 in [01-component-model.md](../architecture/01-component-model.md).
> Port(s): `DocumentIngestor`, `Retriever`, `VectorStore` (+ reuse LangChain4j `EmbeddingModel`).

## Purpose

Provide the agent's grounding knowledge: turn the host's **static text corpus** into retrievable,
citable chunks, and answer top-k retrieval queries for [Flow A step 2](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1).

The **corpus** (per [glossary.md](../glossary.md#knowledge--rag-concepts)) is exactly:

- **Product docs** — help/manual text about the suite.
- **Pipeline/job config files** — ETL pipeline and job definitions (e.g. TOON config, NiFi).
- **Schema / data-model configs** — table/dataset schemas, data-model descriptors.

**Not in the corpus:** dynamic operational data (events, alerts, incidents, cases, live metrics).
That arrives through **tools** ([tool-registry.md](tool-registry.md)), never RAG. Ingesting
dynamic data is explicitly out of scope (see below).

Default deployment is **offline**: embeddings run in-JVM via ONNX `all-MiniLM` with no network,
and the vector store is in-memory with disk save/load.

## Port interface(s)

From [01-component-model.md](../architecture/01-component-model.md#component-2--knowledge--rag---ports-embeddingmodel-vectorstore-retriever-documentingestor), copied verbatim:

```java
package com.eoiagent.knowledge;

public interface DocumentIngestor {            // load → split → embed → store
    IngestReport ingest(IngestRequest request);
}
public interface Retriever {
    List<RetrievedChunk> retrieve(RetrievalQuery query);  // top-k + filters
}
public interface VectorStore {                 // thin wrap over LC4j EmbeddingStore
    void add(List<EmbeddedChunk> chunks);
    List<Match> search(float[] queryVector, int k, MetadataFilter filter);
}
// EmbeddingModel: reuse LangChain4j dev.langchain4j.model.embedding.EmbeddingModel
```

Contract notes:

- **`DocumentIngestor.ingest(IngestRequest)`** — Runs load → split → embed → store. `request`
  non-null; `request.sources()` non-empty. Idempotent per `sourceId` (re-ingesting a source
  replaces its chunks; do not duplicate). Returns a non-null `IngestReport` with counts +
  warnings (never throws on a single bad source — record a warning and continue). Blocking; safe
  to call from one thread per ingest job. Throws `ConfigException` only for misconfiguration
  (no store, no embedding model).
- **`Retriever.retrieve(RetrievalQuery)`** — `query` non-null; `query.text()` non-blank;
  `query.k() >= 1`. Returns up to `k` `RetrievedChunk`s ordered by descending `score`; empty list
  if nothing matches (never null). Each chunk carries a `Citation` (sourceId + locator). Embeds
  the query via the active `EmbeddingModel`, then `VectorStore.search`. Read-only and thread-safe.
- **`VectorStore.add(List<EmbeddedChunk>)`** — Appends embedded chunks; vectors must match the
  store's dimensionality (384 for `all-MiniLM`). Non-null, may be empty (no-op). Thread-safe for
  concurrent reads during add is **not** guaranteed for `InMemoryVectorStore`; the ingest path
  owns writes.
- **`VectorStore.search(float[], int, MetadataFilter)`** — `queryVector` length == store
  dimensionality; `k >= 1`; `filter` may be `MetadataFilter.none()`. Returns up to `k` `Match`es
  (chunk + score) ordered by descending similarity. Read-only, thread-safe.
- **Reused `EmbeddingModel`** — the LangChain4j `dev.langchain4j.model.embedding.EmbeddingModel`
  abstraction; do **not** define a new port for embeddings. `OnnxEmbeddingAdapter` *is* an
  `EmbeddingModel`.

Small supporting types owned by this module (`com.eoiagent.knowledge`): `EmbeddedChunk`,
`Match`, `MetadataFilter`, `DocumentSource`, `IngestOptions`. (`IngestRequest`, `IngestReport`,
`RetrievalQuery`, `RetrievedChunk`, `Citation` are core domain types.)

## Adapters to build

| Adapter | Library (Maven coord) | Phase | Notes |
|---------|-----------------------|-------|-------|
| `OnnxEmbeddingAdapter` (`AllMiniLmL6V2`) | `dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2` (BOM) | **Phase 1** | in-JVM, offline default; 384-dim; implements LC4j `EmbeddingModel` |
| `InMemoryVectorStore` | `dev.langchain4j:langchain4j` core (BOM) | **Phase 1** | wraps `InMemoryEmbeddingStore`; disk save/load |
| `PgVectorStore` | `dev.langchain4j:langchain4j-pgvector` (BOM, **≥1.16.3**) | **Phase 2** | prod store; gated by `featureEnabled(PGVECTOR)` |
| `ProductDocLoader` | LC4j loaders/splitters (`langchain4j` core, BOM) | **Phase 1** | product help/manual docs |
| `ConfigFileLoader` | LC4j loaders/splitters (BOM) | **Phase 1** | pipeline/job config files (TOON/NiFi text) |
| `SchemaConfigLoader` | LC4j loaders/splitters (BOM) | **Phase 1** | schema / data-model configs |
| `DefaultDocumentIngestor` | (ours, uses LC4j splitters) | **Phase 1** | orchestrates load → split → embed → store |
| `DefaultRetriever` | (ours) | **Phase 1** | embed query → `VectorStore.search` → `RetrievedChunk` |
| `AdvancedRetriever` (rewrite/route/re-rank) | LC4j RAG building blocks (BOM) | **Phase 2** | gated by `featureEnabled(ADVANCED_RETRIEVAL)` |

## Maven coordinates

- **This module:** `com.eoiagent:eoiagent-knowledge` (version `0.1.0-SNAPSHOT`).
- **Ports + domain types:** `com.eoiagent:eoiagent-core`.
- **Third-party (versions via `eoiagent-bom` → `langchain4j-bom:1.16.3`, never hardcoded):**
  `langchain4j` (core: loaders, splitters, `InMemoryEmbeddingStore`, RAG blocks),
  `langchain4j-embeddings-all-minilm-l6-v2`, `langchain4j-pgvector` (**must be ≥1.16.3**, CVE
  fix — Phase 2). `langchain4j-pgvector` appears **only** in the `PgVectorStore` adapter, behind
  the `PGVECTOR` feature flag.

## Inputs / Outputs

Consumed (from [02-domain-model.md](../architecture/02-domain-model.md#knowledge--rag)):
`IngestRequest(List<DocumentSource> sources, IngestOptions options)`,
`RetrievalQuery(String text, int k, MetadataFilter filter)`, plus `PageContext` (the Retriever
maps `page.filters()`/`page.entityIds()` into a `MetadataFilter` so retrieval is page-scoped per
Flow A step 2).

Produced:
`IngestReport(int documents, int chunks, List<String> warnings)`,
`RetrievedChunk(String text, double score, Citation citation)`,
`Citation(String sourceId, String title, String locator)`.

Metadata stored per chunk (drives `MetadataFilter` and citations): `sourceId`, `sourceType`
(`PRODUCT_DOC` / `PIPELINE_CONFIG` / `SCHEMA_CONFIG`), `title`, `locator` (page/section/line),
and any host-supplied tags (e.g. `pipelineId`, `datasetId`).

## Behavior / algorithm

Implements [Flow A step 2](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1).

**Ingest** (`DefaultDocumentIngestor.ingest`):

1. For each `DocumentSource`, pick the loader by type (`ProductDocLoader` / `ConfigFileLoader` /
   `SchemaConfigLoader`). A failed source → add a warning, continue.
2. **Split** into chunks with an LC4j splitter sized from `IngestOptions` (default 512 tokens,
   64 overlap). Attach metadata (sourceId, sourceType, title, locator).
3. **Embed** each chunk batch via the active `EmbeddingModel` (`OnnxEmbeddingAdapter`, in-JVM).
4. **Store** via `VectorStore.add(List<EmbeddedChunk>)`. For idempotency, delete prior chunks for
   the same `sourceId` first.
5. Return `IngestReport(documents, chunks, warnings)`.

**Retrieve** (`DefaultRetriever.retrieve`):

1. Build a `MetadataFilter` from `query.filter()` (+ page context when invoked from Flow A).
2. Embed `query.text()` with the `EmbeddingModel` (384-dim, in-JVM, no network).
3. `VectorStore.search(vector, query.k(), filter)` → `List<Match>`.
4. Map each `Match` to a `RetrievedChunk` with `Citation(sourceId, title, locator)`; order by
   descending score; return top-k.

**AdvancedRetriever** (Phase 2): query rewrite → route to corpus subset → retrieve → re-rank,
each step gated; only active when `featureEnabled(ADVANCED_RETRIEVAL)`.

Persistence: `InMemoryVectorStore` supports `save(Path)` / `load(Path)` so an offline install can
ship a pre-built index; `PgVectorStore` persists in PostgreSQL.

## Configuration keys

Read via `ConfigProvider` (defaults per [03-deployment-profiles.md](../architecture/03-deployment-profiles.md#defaults-per-profile)):

| Key | Type | Default (OFFLINE / ON_PREM_HOSTED / CLOUD) |
|-----|------|--------------------------------------------|
| `eoiagent.vectorstore.kind` | String | `in-memory` or `pgvector` / `pgvector` / `pgvector` |
| `eoiagent.vectorstore.indexPath` | String | (in-memory save/load path, optional) |
| `eoiagent.vectorstore.pgvector.url` | String | (unset offline) / JDBC URL / JDBC URL |
| `eoiagent.vectorstore.pgvector.table` | String | `eoiagent_embeddings` |
| `eoiagent.embedding.provider` | String | `onnx-all-minilm` (all profiles) |
| `eoiagent.embedding.dimension` | Integer | `384` |
| `eoiagent.ingest.chunkSize` | Integer | `512` |
| `eoiagent.ingest.chunkOverlap` | Integer | `64` |
| `eoiagent.retrieval.k` | Integer | `5` |
| `eoiagent.retrieval.advanced.enabled` | Boolean | `false` (Phase 2; only if `ADVANCED_RETRIEVAL`) |

`pgvector` selection requires `featureEnabled(PGVECTOR)`; otherwise the module falls back to
`InMemoryVectorStore` (and in `OFFLINE` never opens a non-local DB connection).

## Error handling

Typed exceptions from [conventions.md §5](../conventions.md#5-error-handling):

- `ConfigException` — no embedding model / no vector store configured; `pgvector` selected but
  `PGVECTOR` disabled by profile; pgvector version < 1.16.3 detected.
- `PolicyViolation` — a vector-store or retrieval path would require network egress in `OFFLINE`
  (fail-closed; e.g. a remote pgvector URL while offline).
- Ingest never throws on a single malformed source — it records a `warning` in `IngestReport`.
- Each retrieval emits a `RETRIEVAL` `AuditEvent` upstream (the runtime records it; the Retriever
  surfaces source ids + scores for that event). Citations must always be attached so answers are
  traceable.

Offline guarantee: with `provider=onnx-all-minilm` + `vectorstore.kind=in-memory`, no method
performs a network call (invariant 3 in [04-sequence-flows.md](../architecture/04-sequence-flows.md#cross-cutting-invariants-assert-in-tests)).

## Acceptance criteria

1. **AC1** Given `OFFLINE` profile, `embed` of a query and ingest of a doc produce **384-dim**
   vectors with **no network call** (verified by the network-deny harness).
2. **AC2** Ingesting a product doc, a pipeline config file, and a schema config yields an
   `IngestReport` whose `documents`/`chunks` counts match the inputs and whose chunks carry the
   correct `sourceType` metadata.
3. **AC3** `retrieve` returns at most `k` `RetrievedChunk`s, ordered by descending `score`, each
   with a non-null `Citation(sourceId, title, locator)`.
4. **AC4** A `RetrievalQuery` with a `MetadataFilter` on `sourceType=SCHEMA_CONFIG` returns only
   schema-config chunks.
5. **AC5** Re-ingesting the same `sourceId` does not duplicate chunks (idempotent replace).
6. **AC6** `InMemoryVectorStore.save(path)` followed by `load(path)` in a fresh instance returns
   identical search results for the same query vector.
7. **AC7** Given `OFFLINE` profile and `eoiagent.vectorstore.kind=pgvector` with a non-local URL,
   construction throws `PolicyViolation` (fail-closed).
8. **AC8** Selecting `pgvector` with `PGVECTOR` disabled throws `ConfigException` (or falls back
   to in-memory per config) — never silently connects.
9. **AC9** Attempting to ingest a dynamic-data source type (event/incident) is rejected with a
   `ConfigException` — the corpus excludes dynamic data.

## Test plan

All default tests run with **no network and no live LLM** (ONNX runs in-JVM; the embedding model
is real but local, or a fixed-vector stub for speed). JUnit 5 + AssertJ.

- **Unit** — `DefaultDocumentIngestorTest` (split + metadata + idempotency),
  `DefaultRetrieverTest` (ordering, k, filter→`MetadataFilter` mapping, page-context scoping),
  `InMemoryVectorStoreTest` (search ranking, save/load), `OnnxEmbeddingAdapterTest`
  (384-dim, deterministic vectors).
- **Contract** — `VectorStoreContractTest` (shared suite for `InMemoryVectorStore` and, Phase 2,
  `PgVectorStore`), `RetrieverContractTest`.
- **Profile/offline** — `OfflineNetworkDenyTest` asserts AC1/AC7.
- **Integration (profile-tagged, opt-in)** — `PgVectorStoreIT` against a Testcontainers
  PostgreSQL+pgvector image (Phase 2; excluded from default build).
- **Eval** — golden Q&A with expected citations (see [eval-harness.md](eval-harness.md)).

Command: `mvn -pl eoiagent-knowledge -am test` (default, no network). Phase 2 pgvector:
`mvn -pl eoiagent-knowledge -Ppgvector-it verify`.

## Dependencies on other modules

- `eoiagent-core` — domain records (`IngestRequest`, `IngestReport`, `RetrievalQuery`,
  `RetrievedChunk`, `Citation`, `PageContext`) and the typed exception hierarchy.
- `eoiagent-config` — `ConfigProvider` for keys + `featureEnabled(PGVECTOR / ADVANCED_RETRIEVAL)`.
- `eoiagent-model` — depends only on the LC4j `EmbeddingModel` abstraction; `OnnxEmbeddingAdapter`
  defined here is also consumed by the model gateway's embedding route.
- `eoiagent-observability` — `AuditSink` (runtime records `RETRIEVAL`).

## Out of scope / deferred

- Advanced retrieval (query rewriting / routing / re-ranking) — **Phase 2** (`ADVANCED_RETRIEVAL`).
- `PgVectorStore` production adapter — **Phase 2** (`PGVECTOR`).
- Ingesting **dynamic** operational data (events/alerts/incidents/cases/metrics) — never in RAG;
  that is the Tools component.
- Hosted/cloud embedding providers — possible in `CLOUD` later; ONNX is the default everywhere.
- Cross-encoder re-ranking models / hybrid (BM25+vector) search — Phase 2+.

## Related ADRs & flows

- [ADR-0007 — Vector store: in-memory then pgvector](../adr/0007-vector-store-inmemory-then-pgvector.md)
- [ADR-0003 — Foundation: LangChain4j BOM](../adr/0003-foundation-langchain4j-bom.md)
- [ADR-0010 — Isolate experimental deps](../adr/0010-isolate-experimental-deps.md)
- [ADR-0004 — Hexagonal ports & adapters](../adr/0004-hexagonal-ports-and-adapters.md)
- Flow: [A — page-context product help](../architecture/04-sequence-flows.md#flow-a--page-context-product-help-the-common-case-phase-1)
- [03 — Deployment profiles & capability matrix](../architecture/03-deployment-profiles.md#capability-matrix)
