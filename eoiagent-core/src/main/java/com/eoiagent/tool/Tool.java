package com.eoiagent.tool;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;

/** A single invokable tool exposing its spec and an invoke entry point. */
public interface Tool {

    ToolSpec spec();

    ToolResult invoke(ToolCall call);
}
