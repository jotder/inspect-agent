package com.eoiagent.runtime;

import com.eoiagent.core.TaskId;
import com.eoiagent.core.ToolResult;

/** The outcome of executing one plan step, used to revise the plan. */
public record Observation(TaskId step, boolean ok, String summary, ToolResult result) {
}
