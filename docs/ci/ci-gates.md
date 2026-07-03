---
type: ci
title: "CI Gates (T-405)"
description: "GitHub Actions: the offline PR gate (full reactor + golden suites + docs/packaging/license checks) and the nightly/manual live-model certification job."
timestamp: "2026-07-03T00:00:00+05:30"
tags: ["ci", "github-actions", "certification"]
---
# CI Gates (T-405)

> Delivers [backlog T-405](../roadmap/backlog.md#phase-4--hardening): the first CI for this repo.
> Two workflows with a deliberate split — the PR gate judges the **code** and must be
> deterministic/offline; the certification job judges a **model** and needs a live endpoint.

## PR gate — `.github/workflows/ci.yml`

Runs on every PR and push to `main` (ubuntu, Temurin JDK 25, Maven + Node):

| Step | Gate |
|---|---|
| `node tools/okf/check.mjs` | docs/ stays an OKF v0.1-conformant bundle |
| `mvn -B clean install` | full reactor: unit + JDK Class-File-API arch tests + **golden eval suites** (stub gateway, in-JVM ONNX — no live LLM; env-gated live/Postgres ITs self-skip) |
| `node tools/packaging/check-modules.mjs` | ADR-0014: every jar has `Automatic-Module-Name`, none ships `module-info.class` |
| `license:aggregate-add-third-party` (`-Plicense-report`) | ADR-0012: fails on any non-permissive runtime dependency |

## Live-model certification — `.github/workflows/live-certification.yml`

Nightly (02:30 UTC) + `workflow_dispatch`. Boots an Ollama container, pulls the candidate model,
and runs the T-356 certification gate (`LiveModelCertificationTest` → `ModelCertificationRunner`:
RAG grounding, tool-call fidelity, navigation) against the assembled platform via the
`EOIAGENT_IT_LLM_*` env contract.

**Honest constraint:** GitHub-hosted runners are CPU-only, so the scheduled run uses a small
default (`qwen2.5:3b-instruct`) as a *live-path smoke*, not a real certification. Actual
candidates (`qwen2.5:14b-instruct`, Ornith 1.0 9B) should be certified via `workflow_dispatch`
on a self-hosted runner with capable hardware, or locally:

```
EOIAGENT_IT_LLM_BASE_URL=http://localhost:11434 \
EOIAGENT_IT_LLM_MODEL=qwen2.5:14b-instruct \
mvn -pl eoiagent-examples test -Dtest=LiveModelCertificationTest
```

Either path prints the per-gate PASS/FAIL and the CERTIFIED/REJECTED verdict (ADR-0013 §3).

## Related

- [Eval Harness — Spec](../specs/eval-harness.md) (golden suites, assertions)
- [ADR-0013 — Pluggable models, certified by eval](../adr/0013-pluggable-models.md)
- [Packaging & Licensing](../packaging/packaging-and-licensing.md) (the two build-time gates)
