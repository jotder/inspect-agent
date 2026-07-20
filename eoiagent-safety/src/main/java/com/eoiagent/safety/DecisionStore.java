package com.eoiagent.safety;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;

import java.util.Optional;

/**
 * Host-supplied SPI: a durable side-channel for approval decisions, consulted by the
 * {@link CallbackApprovalGate} <em>before</em> prompting the {@link ApprovalHandler} and written
 * to after a decision is collected. The host owns keying, persistence, and consumption semantics
 * — {@link #find} may match on whatever the host considers "the same request" (e.g. tool name +
 * arguments rather than the framework's per-run {@code (RunId, ToolCall)} identity), and a
 * one-shot implementation that removes a decision on first match gives resume-after-restart:
 * an operator decision persisted while the original run's gate thread no longer exists is
 * consumed by the re-issued run's identical call, without re-prompting the human.
 *
 * <p>Fail-closed contract: a {@link #find} miss (or a store that throws) simply falls through to
 * the normal handler prompt; the store can therefore never widen access, only pre-supply a
 * decision the host already collected through its own approval surface.
 */
public interface DecisionStore {

    /** A previously collected decision for this request, per the host's matching rule; empty to prompt. */
    Optional<ApprovalDecision> find(ApprovalRequest req);

    /** Records the terminal decision the gate collected (including DENIED/TIMED_OUT), for host bookkeeping. */
    void record(ApprovalRequest req, ApprovalDecision decision);
}
