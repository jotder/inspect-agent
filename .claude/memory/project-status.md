---
name: project-status
description: Actual build progress (from git log) — further along than ONBOARDING.md claims
metadata:
  type: project
---

# Project status (as of 2026-07-03, evening)

**Done (committed, tagged `T-NNN:` in git log):** Phase 0 (T-001…T-010), Phase 1 (T-101…T-116),
**all of Phase 2** (T-201…T-211), and **ALL of Phase 3** (T-301…T-306: LangGraphOrchestrator,
CheckpointStore InMemory+Postgres, breakpoints/HITL/time-travel, investigation tools + playbooks,
VectorLongTermMemory, investigation eval + restart→resume asserted). Full reactor green.

**Phase 4 started: T-401 done (OTel tracing).** `NoopTraceCollector` + quarantined
`OpenTelemetryTraceCollector` (optional `opentelemetry-api` 1.44.1 via BOM) in
eoiagent-observability; spans (model_call/tool_call) wired into `ReActOrchestrator` via a new
6-arg ctor (5-arg ctor unchanged, defaults to a private no-op — runtime depends only on the
TraceCollector PORT in core, never the observability adapter); `PlatformBuilder.traceCollector(...)`
override, Noop default. OTel tests run against `OpenTelemetry.noop()` — offline, no SDK.

**HANDOVER AUDIT (2026-07-03, new sole owner):** phases 0–3 are component-complete but the LIVE
path was never closed. Verified gaps: **F1** `Lc4jChatGateway.toolCalls()` always empty — no real
model can drive a tool (whole agentic stack only ever ran on StubLlmGateway); **F2** sessions
stateless (no ChatMemory consumed in host/platform); **F3** no Retriever in the loop (no live
RETRIEVAL/citations); **F4** NavigationIntent never emitted by any orchestrator; **F5** Phase 4
as written collided with reality (JPMS vs split packages; SecurityManager removed in JDK 25; no
CI exists). Owner directives: model choice must be pluggable as models progress (→ ADR-0013);
otherwise architect's discretion. New ADRs: **0013** (pluggable models, config-first + eval
certification), **0014** (classpath jars + Automatic-Module-Name, NO JPMS).

**T-350 DONE (2026-07-03):** tool calls now map across the LC4j seam both ways. `ToolCall` gained
a nullable `callId` as FIRST component (back-compat 3-arg ctor delegates); `ToolCallMeta` (core,
pure-JDK encoder) carries assistant-tool-call turns + paired TOOL results in ChatMessageRecord.meta;
`ToolMapping`/`MessageMapping` (model) decode via LC4j internal Json + `JsonSchemaElementJsonUtils
.fromMap` (exact inverse of JavaApiTool's toMap); ReAct loop now replays the assistant tool-call
turn and preserves callId when scoping. Malformed model-emitted arg JSON degrades to
`{"_raw": <string>}` (never crashes; dispatch validation reports). KEY: OpenAI-protocol servers
REQUIRE the tool result to echo the request's call id — legacy meta-less TOOL records still map to
UserMessage (fallback).

**T-351 DONE (2026-07-03):** session memory in the live loop. `ReActOrchestrator` 7-arg ctor takes
nullable `MemoryStore` (port); seeds history from stored transcript (window = new
`eoiagent.runtime.memory.maxMessages` key, default 20; store keeps everything), persists USER+final
ASSISTANT turn on success (also on max-steps CLARIFICATION), nothing on ERROR; tool turns NEVER
persisted. `PlatformBuilder.memoryStore(...)` override, `InMemoryMemoryStore` default (platform pom
gained eoiagent-memory dep). Demo `MultiTurnMemoryDemo` (prints the exact context the model sees —
anti-misconception teaching per [[delivery-style]]); HOWTO "Phase-3.5 integration" section; memory
spec §1b records as-built vs aspirational §1.

**T-352 DONE (2026-07-03):** RAG in the live loop. `ReActOrchestrator.builder()` added (ctor
telescoping ended; old ctors delegate) with `retriever`/`systemPrompts(Function<GoalKind,String>)`
seams (runtime uses only core ports — PromptProfile stays app-api-side as a lambda). QA turns:
retrieve topK (`eoiagent.runtime.rag.topK`=4) → RETRIEVAL audit `details.sourceIds` (the EXACT
shape DefaultEvalHarness reconstructs) → SYSTEM msg = persona + "[source: id] chunk" blocks →
distinct Citations on the answer. Platform builds the knowledge stack at bootstrap
(ModelProfile.embedding provider switch onnx-all-minilm|ollama, InMemoryVectorStore, enrich docs
with sourceId=ks.id()/sourceType from SourceKind [CONFIG_FILE→"PIPELINE_CONFIG"]);
`PlatformBuilder.retriever(...)` override (required for CUSTOM sources). KEY FIX: `DocumentLoader`
default now falls back to classpath resources — the reference pack's bundled corpus
(/acme/docs/*.md) was NEVER ingestable before. Golden cases qa-ingestion-cadence/qa-lakehouse-zones
now assert mustCite acme-docs via real ONNX retrieval, green. NOTE: reference-pack boots now load
ONNX + ingest (~3s each) — perf pass T-402 should share/cache the embedding model.

**T-353 DONE (2026-07-03):** NavigationIntent live — the signature behavior (audit finding F4
closed). Design: navigation is the reserved TOOL `navigate_to_page` (`NavigationIntent.TOOL_NAME`
constant in core). `PlatformBuilder` registers a package-private `NavigationTool` derived from the
pack's NavigationCatalog (description enumerates pages/params; validates pageId + required
ParamSpecs; returns canonical intent map targetPageId/parameters/rationale). `ReActOrchestrator`:
successful dispatch of that name → terminal `AgentAnswer(NAVIGATION, intent)` + DECISION audit
"navigation to <page>"; FAILED proposal flows back as a tool observation so the model
self-corrects (tested). Golden `nav-revenue-dashboard` green E2E through the platform. No host
changes (goals stay QA; the model chooses the tool). Capability=READ_DOCS (no NAVIGATE cap
exists). Demo `LiveNavigationDemo` shows reject→correct.

**T-354 DONE (2026-07-03):** platform wiring v2 — the [[platform-wiring-gotcha]] is CLOSED for the
main flows. (1) Config-first models (ADR-0013 §1): `ModelConfigKeys` in eoiagent-model
(eoiagent.model.chat/embedding .provider/.modelId/.baseUrl, null defaults = "use pack");
`PlatformBuilder.buildGateway/buildRetrieval` overlay non-blank config over the pack ModelProfile.
NOTE: eoiagent-config `ConfigKeys` still carries duplicate MODEL_* constants with the SAME key
names (one has default "onnx-all-minilm" — harmless since get() uses the PASSED key's default, but
migrate/remove them per conventions §11 eventually). (2) Mutating stack: `MUTATING_ACTIONS` is
matrix-permitted in ALL profiles and enabling-key default TRUE → every platform now assembles the
4-arg mutating registry + `CallbackApprovalGate`; `PlatformBuilder.approvalHandler(...)` is the
host seam; NO handler = headless gate = every approval DENIED (fail-closed; C4 asserted through
the assembled platform in `PlatformWiringV2Test`). (3) Policy: `CeilingPolicyEngine` (platform) =
RoleBasedPolicyEngine ceiling ∧ pack ProfilePolicyEngine — packs narrow, never widen. Deferred
from T-354: orchestrator selection by GoalKind (host sessions hardcode QA — needs a goal-kind
signal from the host; revisit with T-356/Phase 4). Demo `ConfigSwapModelDemo` (DemoConfig gained a
values-map ctor).

**T-355 DONE (2026-07-03):** real token streaming. `Orchestrator` PORT gained
`run(goal, ctx, Consumer<String> onToken)` as a DEFAULT method (post-hoc word emission — other
orchestrators unchanged); `ReActOrchestrator` overrides via `chatOnce` helper: with a listener,
each model turn goes through `gateway.chatStream` (internal TokenSink → CompletableFuture.join);
synchronous `ModelUnavailableException` (backend can't stream) → blocking chat + final text as ONE
chunk (degraded, never failed); mid-stream onError → future exceptional → run's ERROR path.
`DefaultAgentSession.askStream` now passes `sink::onToken` through `coreRun` (post-hoc splitting
REMOVED from host). ScriptedGateway test double got token-split chatStream. Demo
`StreamingAnswerDemo` (time-to-first-token teaching).

**T-356 DONE (2026-07-03) — PHASE 3.5 COMPLETE.** Model certification gate (ADR-0013 §3):
`ModelCertificationRunner` (eoiagent-examples main) — suite = 3 capability gates (cert-rag-grounding
CONTAINS "02:00" + mustCite acme-docs; cert-tool-call-fidelity getPipelineStatus{pipelineId:
nightly-load} + CONTAINS SUCCEEDED; cert-navigation → NAVIGATION kpi-dashboard{metric:revenue});
`certify(provider,baseUrl,modelId)` boots the reference pack with config-first overrides (exercises
ADR-0013 §1 live) + audit-aware harness; main() prints per-gate PASS/FAIL + CERTIFIED/REJECTED
(graceful return when endpoint unreachable; sysprops outrank env so tests can target a dead port).
`LiveModelCertificationTest` env-gated on `EOIAGENT_IT_LLM_BASE_URL` (+_PROVIDER/_MODEL) — skipped
in offline CI, the T-405 nightly job should run it. NOT in RunAllDemos (needs live endpoint).
**A live certification has NOT yet been executed on this machine (no Ollama installed)** — first
real run pending; candidates: qwen2.5:14b-instruct (default), ornith-1.0-9b.

**T-403 DONE (2026-07-03) — Phase 4 security review.** `EgressGuard` (eoiagent-safety main, pure
JDK): in-JVM network-deny harness — recording default `ProxySelector` that denies non-loopback
with `PolicyViolation` before any packet leaves (SecurityManager gone in JDK 25/JEP 486, so the
default ProxySelector is the strongest remaining seam; JDK HttpClient consults it; raw
SocketChannel bypasses it → tripwire not sandbox, OS-level denial documented as the hard wall).
Never resolves DNS to classify hosts. Proofs: `OfflineZeroEgressTest` (examples — FULL platform
lifecycle incl. ONNX ingest + RAG ask under guard, zero attempts), `GuardrailOfflineTest`
(guardrails AC4 closed), `AuditCompletenessTest` (RETRIEVAL-before-MODEL_CALL ordering + full
attribution). Red-team: `Lc4jInputGuardrail` hardened from exact substrings to normalized
(zero-width strip + whitespace collapse) variant-tolerant regexes (4 labeled rules);
`InjectionRedTeamTest` = must-block corpus + benign-lookalike false-positive guard + known
evasions asserted PASS as CANARIES (paraphrase/base64/non-English/persona-split — flip = update
doc). New doc `docs/security/security-review-2026-07.md` (type=security, indexed) with OS-level
denial table (nftables/Windows FW/--network=none/systemd IPAddressDeny/DNS stub). Demo
`OfflineEgressDemo` (Phase-4 section in RunAllDemos). Examples pom gained explicit
eoiagent-safety dep.

**Remaining (restructured backlog):**
- **Phase 3.5 Integration COMPLETE (T-350…T-356 ✓):**
  T-352 RAG-in-loop, T-353 NavigationIntent emission, T-354 platform wiring v2 + config-first
  models (subsumes [[platform-wiring-gotcha]]), T-355 real streaming, T-356 live-model E2E +
  model certification (candidates: qwen2.5, Ornith 1.0 9B — agentic-coding model, GGUF/Ollama).
- Phase 4: T-403 ✓ (security review). Left: T-402 perf (live-latency AC blocked — no Ollama on
  this box; offline slice = share/cache ONNX embedding model across boots), T-404 packaging,
  T-405 CI gates.

**Fixed 2026-07-03** (all committed): ONBOARDING.md refreshed to real status; all ArchUnit doc
references corrected to JDK Class-File API arch tests (conventions §2 has the note); ADR-0012
(permissive licensing, Proposed) recorded + roadmap open questions closed; docs graph excludes
target/**; CLAUDE.md graphify rules retargeted to GRAPH_REPORT.md/docs/index.md.
Still open: `.claude/settings.json` hooks still mandate the broken pip graphify CLI — editing
hooks needs the user (permission classifier blocks agent self-modification); see
[[graphify-tooling-broken]].

**How to apply:** pick the lowest-numbered remaining ticket whose Depends-on are done
(docs/roadmap/backlog.md). Verify baseline with `mvn clean install` (JDK 25+, Maven 3.9+); use
`-am` for per-module test runs. T-304's canned corpus (orders_daily failure: E-1..E-3, A-101/
A-102, INC-2001, C-501, playbook `pipeline-failure`) is what the T-306 investigation eval should
assert against. See [[improvement-backlog]] and [[platform-wiring-gotcha]].

Repo: https://github.com/jotder/inspect-agent (personal `jotder`; commits authored
rahul@gammanalytics.com — org transfer still an open item).
