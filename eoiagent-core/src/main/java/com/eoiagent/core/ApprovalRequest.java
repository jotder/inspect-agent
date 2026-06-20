package com.eoiagent.core;

/** A request for human approval of a mutating tool call, with a dry-run preview. */
public record ApprovalRequest(RunId run, ToolCall call, String humanSummary, DryRunResult preview) {
}
