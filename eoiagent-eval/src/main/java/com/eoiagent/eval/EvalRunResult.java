package com.eoiagent.eval;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import java.util.List;

/** The observed result of running one case: the answer, any tool calls, cited source ids and run id. */
public record EvalRunResult(AgentAnswer answer,
                            List<ToolCall> toolCalls,
                            List<String> citedSourceIds,
                            RunId run) {
}
