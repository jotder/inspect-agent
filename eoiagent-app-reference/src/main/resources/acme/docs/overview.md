# Acme Lakehouse Suite — Overview

Acme Lakehouse Suite is a unified storage and warehouse layer (a *lakehouse*) that holds your
datasets across three *zones*: **raw**, **curated**, and **mart**. ETL *pipelines* materialize
curated and mart datasets from raw sources on a schedule.

- **Datasets** live in a zone and have a schema (columns + types).
- **Pipelines** are ETL jobs; each run records a status (SUCCEEDED / FAILED) and a row count.
- **KPI dashboards** summarize revenue and usage metrics by period.

Ingestion runs nightly by default; the `nightly-load` pipeline refreshes curated datasets from the
raw zone every day at 02:00 UTC.
