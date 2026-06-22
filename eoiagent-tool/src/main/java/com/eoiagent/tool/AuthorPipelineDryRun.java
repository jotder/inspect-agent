package com.eoiagent.tool;

import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.DryRunProvider;

import java.util.Map;

/** Dry-run preview for {@link PipelineApi#authorPipeline}: describes the pipeline that would be created/updated. */
public final class AuthorPipelineDryRun implements DryRunProvider {

    @Override
    public DryRunResult preview(ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        String name = String.valueOf(args.getOrDefault("name", "<unknown>"));
        String definition = String.valueOf(args.getOrDefault("definition", ""));
        String preview = "Would create or update pipeline '" + name + "' with definition: "
                + definition.substring(0, Math.min(definition.length(), 200));
        return new DryRunResult(true, preview, Map.of("pipeline", name, "action", "author"));
    }
}
