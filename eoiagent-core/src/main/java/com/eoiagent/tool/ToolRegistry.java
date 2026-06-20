package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import java.util.List;

/** Registry that gates tool visibility and dispatches calls by context. */
public interface ToolRegistry {

    void register(Tool tool);

    List<ToolSpec> visibleTo(AgentContext ctx);

    ToolResult dispatch(ToolCall call, AgentContext ctx);
}
