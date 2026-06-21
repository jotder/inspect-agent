package com.eoiagent.safety;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;

/**
 * Host-supplied SPI: how a human approval decision is actually collected. The host renders the
 * {@link ApprovalRequest} (a UI prompt, an approval queue, an operator-token check, …) and returns
 * the {@link ApprovalDecision}. The {@link CallbackApprovalGate} owns the timeout and fail-closed
 * policy around this call; an implementation should simply block until it has a decision.
 */
@FunctionalInterface
public interface ApprovalHandler {

    ApprovalDecision decide(ApprovalRequest req);
}
