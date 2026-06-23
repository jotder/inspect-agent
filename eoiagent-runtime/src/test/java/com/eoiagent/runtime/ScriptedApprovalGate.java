package com.eoiagent.runtime;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.ApprovalGate;

import java.util.Map;

/** In-test {@link ApprovalGate}: returns a fixed decision and records how it was called. */
final class ScriptedApprovalGate implements ApprovalGate {

    private final ApprovalDecision decision;
    int requestCalls = 0;
    int dryRunCalls = 0;
    ApprovalRequest lastRequest;

    ScriptedApprovalGate(ApprovalDecision decision) {
        this.decision = decision;
    }

    @Override
    public ApprovalDecision request(ApprovalRequest req) {
        requestCalls++;
        lastRequest = req;
        return decision;
    }

    @Override
    public DryRunResult dryRun(ToolCall call) {
        dryRunCalls++;
        return new DryRunResult(true, "preview of " + call.toolName(), Map.of());
    }
}
