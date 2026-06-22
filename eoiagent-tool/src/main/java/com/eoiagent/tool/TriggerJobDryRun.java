package com.eoiagent.tool;

import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.ToolCall;
import com.eoiagent.safety.DryRunProvider;

import java.util.Map;

/** Dry-run preview for {@link JobApi#triggerJob}: describes the job that would be triggered. */
public final class TriggerJobDryRun implements DryRunProvider {

    @Override
    public DryRunResult preview(ToolCall call) {
        Map<String, Object> args = call.arguments() == null ? Map.of() : call.arguments();
        String jobName = String.valueOf(args.getOrDefault("jobName", "<unknown>"));
        String arguments = String.valueOf(args.getOrDefault("arguments", "{}"));
        String preview = "Would trigger job '" + jobName + "' with arguments: " + arguments;
        return new DryRunResult(true, preview, Map.of("job", jobName, "action", "trigger"));
    }
}
