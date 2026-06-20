package com.eoiagent.runtime;

import com.eoiagent.core.Capability;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.tool.Tool;

import java.util.Map;

/** Read-only test {@link Tool} returning a fixed value (no schema args required). */
final class FixedTool implements Tool {

    private final ToolSpec spec;
    private final Object value;

    FixedTool(String name, Object value) {
        this.spec = new ToolSpec(name, "test tool", "{}", false, Role.USER, Capability.READ_DOCS);
        this.value = value;
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        return new ToolResult(true, value, null, Map.of());
    }
}
