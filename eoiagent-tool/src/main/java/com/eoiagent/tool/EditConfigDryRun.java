package com.eoiagent.tool;

import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.DryRunProvider;

import java.util.Map;

/** Dry-run preview for {@link ConfigApi#editConfig}: describes the configuration change that would be applied. */
public final class EditConfigDryRun implements DryRunProvider {

    @Override
    public DryRunResult preview(ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        String key = String.valueOf(args.getOrDefault("key", "<unknown>"));
        String value = String.valueOf(args.getOrDefault("value", "<unknown>"));
        String preview = "Would change config '" + key + "' to '" + value + "'";
        return new DryRunResult(true, preview, Map.of("key", key, "value", value, "action", "edit"));
    }
}
