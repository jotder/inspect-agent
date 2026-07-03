---
name: delivery-style
description: Owner directive — move fast; every ticket ships tests + docs + a runnable demo, because users are new to agent tech and carry misconceptions
metadata:
  type: feedback
---

# Delivery style (owner directive, 2026-07-03)

**Fact:** rahul wants speed, but with three non-negotiables per ticket: (1) *sufficient tests*
(not just happy path — cover the misconception-prone edges), (2) *documentation* of what was
built and why, (3) *a runnable demo in `eoiagent-examples` wherever relevant*.

**Why:** the consumers of this platform are new to agent technologies and hold misconceptions
(e.g. "the model executes tools" vs. the registry does; "memory is automatic"; "RAG = the model
knows our docs"). Demos + docs are the teaching layer, not an afterthought. Existing pattern:
the Phase-2 capability demos (`MutatingApprovalDemo`, etc.) — narrated, offline-deterministic,
ASCII-only console output.

**How to apply:** for each Phase-3.5/4 ticket: tests in the owning module; a `*Demo` in
`eoiagent-examples` wired into `RunAllDemos` + `DemoSmokeTest`; HOWTO.md section and/or spec
update; then `node tools/okf/check.mjs` + `node tools/graphify/generate.mjs` if docs changed.
Commit per ticket, local only (see [[project-status]] for the push policy).
