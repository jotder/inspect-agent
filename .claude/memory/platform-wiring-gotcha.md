---
name: platform-wiring-gotcha
description: PlatformBuilder wires only the read-only Flow B stack — Phase-2/3 features are adapter-only, not reachable through a platform session
metadata:
  type: project
---

# Platform wiring gotcha (cost a full investigation once)

**Fact:** `PlatformBuilder`/`DefaultAgentPlatform` (eoiagent-platform) wire ONLY
`ReActOrchestrator` (Flow B, read-only) + the 2-arg read-only `DefaultToolRegistry`.
No ApprovalGate, SupervisorOrchestrator, LangGraphOrchestrator, or mutating tools are
assembled, and `PlatformBuilder` exposes setters only for pack/configProvider/auditSink/llmGateway.

**Why:** demos and evals of Phase-2/3 capabilities (mutating approval, supervisor, Flow E
checkpoint/resume) must wire adapters directly — they cannot be shown through
`platform.agentService()` yet. The reference pack (`AcmeReadTools`) is read-only.

**How to apply:** before demoing/testing a Phase-2+ feature end-to-end, check whether it needs
platform wiring first (an open design item — reconcile ProfilePolicyEngine vs
RoleBasedPolicyEngine, add an ApprovalHandler seam). See [[project-status]].
