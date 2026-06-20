package com.eoiagent.host;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;

/** Host-side handler invoked when the agent needs a human approval decision. */
public interface ApprovalHandler {

    ApprovalDecision onApprovalRequested(ApprovalRequest request);
}
