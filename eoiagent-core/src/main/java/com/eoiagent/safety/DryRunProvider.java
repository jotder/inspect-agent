package com.eoiagent.safety;

import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;

/**
 * SPI a mutating tool/adapter implements so its effects can be previewed before approval. Resolved
 * by tool name from the {@link ApprovalGate} implementation. Implementations MUST be read-only — a
 * dry-run never commits the action it previews.
 */
public interface DryRunProvider {

    DryRunResult preview(ToolCall call);
}
