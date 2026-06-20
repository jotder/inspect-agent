package com.eoiagent.safety;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;

/** Port that mediates human approval and dry-running of mutating calls. */
public interface ApprovalGate {

    ApprovalDecision request(ApprovalRequest req);

    DryRunResult dryRun(ToolCall call);
}
