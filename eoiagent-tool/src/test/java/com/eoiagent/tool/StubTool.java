package com.eoiagent.tool;

import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;

import java.util.Map;
import java.util.function.Function;

/** Deterministic in-test {@link Tool}: returns a configured result (or applies a function to the call). */
final class StubTool implements Tool {

    private final ToolSpec spec;
    private final Function<ToolCall, ToolResult> behavior;

    StubTool(ToolSpec spec, Function<ToolCall, ToolResult> behavior) {
        this.spec = spec;
        this.behavior = behavior;
    }

    static StubTool ok(ToolSpec spec, Object value) {
        return new StubTool(spec, call -> new ToolResult(true, value, null, Map.of()));
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResult invoke(ToolCall call) {
        return behavior.apply(call);
    }
}
