---
name: local-postgres-dev
description: "Local PG for T-206: native portable PG 18.2 on :5432 (audit/memory) + pgvector Docker container on :5433. T-206 COMPLETE."
metadata: 
  node_type: memory
  type: project
  originSessionId: 7bcacfc1-1378-4a52-98cc-7ff959d79e84
---

Installed 2026-06-22 to unblock [[eoi-agent-project]] T-206 (pgvector + JDBC audit + Postgres memory).

**Portable, no admin, no Windows service** (machine has no Docker, no admin rights, no MSVC build tools).
- Binaries: `C:\Users\User\pgsql\bin` (PostgreSQL 18.2, EDB binaries zip)
- Data dir: `C:\Users\User\pgsql\data`; server log: `C:\Users\User\pgsql\server.log`
- Listens on `localhost:5432`, superuser `postgres`, **password `postgres`** (scram-sha-256 on localhost TCP), encoding UTF8
- Project database: `eoiagent`
- JDBC URL: `jdbc:postgresql://localhost:5432/eoiagent` (user `postgres`, password `postgres`)
- localhost-only (`listen_addresses=localhost`); for LAN access set `listen_addresses='*'` + add a pg_hba `host` rule + reload

**Manage** (not a service — does NOT auto-start on reboot):
- Start: `& C:\Users\User\pgsql\bin\pg_ctl.exe -D C:\Users\User\pgsql\data -l C:\Users\User\pgsql\server.log -o "-p 5432" start`
- Stop:  `& C:\Users\User\pgsql\bin\pg_ctl.exe -D C:\Users\User\pgsql\data stop`
- psql:  `& C:\Users\User\pgsql\bin\psql.exe -U postgres -p 5432 -d eoiagent`

**pgvector — via Docker** (Docker Desktop 4.78 installed 2026-06-22). pgvector has no Windows binary
and can't be built here (no MSVC), so `PgVectorStore` runs against a container:
- `docker run -d --name eoiagent-pgvector -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=eoiagent -p 5433:5432 pgvector/pgvector:pg16`
- JDBC URL `jdbc:postgresql://localhost:5433/eoiagent` (user/pass `postgres`). Container left running.
- `docker` is NOT on the default PATH; use `C:\Program Files\Docker\Docker\resources\bin\docker.exe`
  (prepend that bin dir to PATH so the `docker-credential-desktop` helper resolves for image pulls).
- **Testcontainers does NOT work** here: its bundled docker-java can't connect to this Docker Desktop
  (engine 29.5.3) — "Could not find a valid Docker environment". So all PG tests are env-gated against
  an externally-run PG, NOT Testcontainers.

**T-206 is COMPLETE** (all 3 parts): `JdbcAuditSink` (observability, commit fc72a56),
`PostgresMemoryStore` (memory, fc72a56), `PgVectorStore` (knowledge, commit 3e102d9). All verified
green. Note: `PgVectorStore` (langchain4j-pgvector 1.16.3-beta26) stores chunk ids as **UUIDs** —
`EmbeddedChunk.id()` must be a UUID string for pgvector (InMemoryVectorStore accepts any id).

**Running the PG integration tests** (otherwise skipped — env-gated, NOT Maven profiles; forked
surefire JVM inherits the env):
- audit + memory (native PG :5432): set `EOIAGENT_IT_PG_URL=jdbc:postgresql://localhost:5432/eoiagent`
  (+ `_USER`/`_PASSWORD`) then `mvn -pl eoiagent-observability,eoiagent-memory -am test`.
- pgvector (container :5433): set `EOIAGENT_IT_PGVECTOR_URL=jdbc:postgresql://localhost:5433/eoiagent`
  (+ `_USER`/`_PASSWORD`) then `mvn -pl eoiagent-knowledge test`.
PG driver `org.postgresql:postgresql` pinned in eoiagent-bom, test-scoped (main code uses only
java.sql/javax.sql; pgvector via langchain4j-pgvector).
