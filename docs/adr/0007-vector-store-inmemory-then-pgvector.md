# ADR-0007: InMemoryEmbeddingStore for embedded/offline; pgvector for production

- **Status:** Accepted
- **Date:** 2026-06-19
- **Deciders:** Platform team

## Context

The Knowledge/RAG component needs a vector store behind the `VectorStore` port for
embeddings over product docs, pipeline/job config files, and schema/data-model configs.

Two forces pull in different directions:

- **Offline / embedded (constraint C2):** an OFFLINE install must run with **zero
  infrastructure** — no separate database server to provision in an air-gapped environment.
- **Production scale (C3):** ON_PREM_HOSTED and CLOUD installs typically already run
  PostgreSQL and want a durable, queryable, server-backed store.

We also carry a security constraint: `langchain4j-pgvector` had a **SQL-injection CVE fixed
in 1.16.3**, which is why the whole platform floors LangChain4j at that version
([ADR-0003](0003-foundation-langchain4j-bom.md)).

## Decision

Provide two adapters behind the `VectorStore` port, selected by config and the profile
capability matrix:

- **Default — `InMemoryVectorStore`** wrapping LangChain4j's **`InMemoryEmbeddingStore`**
  (`langchain4j` core): zero-infra, with **disk save/load** for persistence across restarts.
  This is the embedded/offline default.
- **Production — `PgVectorStore`** on **`dev.langchain4j:langchain4j-pgvector` (≥ 1.16.3)**,
  used where PostgreSQL exists.

Selection is by **`eoiagent.vectorstore.kind = in-memory | pgvector`** and the
**`PGVECTOR`** feature in the
[capability matrix](../architecture/03-deployment-profiles.md#capability-matrix): OFFLINE
defaults to `in-memory` (or local PG), ON_PREM_HOSTED and CLOUD default to `pgvector`.
Per-profile defaults are in
[`../architecture/03-deployment-profiles.md`](../architecture/03-deployment-profiles.md)
§Defaults.

## Consequences

**Positive**
- **Zero-infra offline story:** OFFLINE installs run RAG with no database to stand up;
  disk save/load gives persistence without a server.
- Same `VectorStore` contract for both; moving an install from in-memory to pgvector is a
  config change, not a code change.

**Negative / follow-ups**
- `InMemoryEmbeddingStore` holds vectors in heap — fine for an embedded corpus, not for very
  large ones; large corpora belong on pgvector.
- **pgvector needs a Postgres server and the CVE-fixed version** (`≥ 1.16.3`); the version
  floor is enforced via the BOM and the Definition of Done.

**Risks / mitigation**
- Risk: an install pins an older pgvector with the SQL-injection CVE. Mitigation: version
  comes only from `langchain4j-bom:1.16.3` — no out-of-BOM version is permitted.

## Alternatives considered

- **Qdrant / Chroma / Elasticsearch** — capable, but each adds external infrastructure to
  operate. Rejected as the default; allowed later behind the `VectorStore` port for clients
  that already run them.
- **SQLite / Lucene embeddable store** — attractive for a single-file embedded store, but we
  **could not confirm a current, maintained embeddable LangChain4j adapter**, so it is **not
  assumed**. `InMemoryEmbeddingStore` with disk save/load covers the embedded need today.
