package com.eoiagent.tool;

import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.DryRunProvider;

import java.util.Map;

/** Dry-run preview for {@link PipelineApi#runPipeline}: describes the run that would be triggered. */
public final class RunPipelineDryRun implements DryRunProvider {

    @Override
    public DryRunResult preview(ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        String pipelineId = String.valueOf(args.getOrDefault("pipelineId", "<unknown>"));
        String params = String.valueOf(args.getOrDefault("parameters", "{}"));
        String preview = "Would execute pipeline '" + pipelineId + "' with parameters: " + params;
        return new DryRunResult(true, preview, Map.of("pipeline", pipelineId, "action", "run"));
    }
}
