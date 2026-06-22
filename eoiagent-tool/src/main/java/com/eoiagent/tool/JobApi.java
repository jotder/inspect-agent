package com.eoiagent.tool;

/**
 * Reference host service exposing job scheduling as a mutating {@code @Tool} method. Wrap the
 * method in a {@link JavaApiTool} with {@code mutating=true} and register into a
 * mutating-capable {@link DefaultToolRegistry}.
 */
public class JobApi {

    @dev.langchain4j.agent.tool.Tool("Trigger a scheduled job with the given arguments")
    public String triggerJob(
            @dev.langchain4j.agent.tool.P("job name") String jobName,
            @dev.langchain4j.agent.tool.P("JSON arguments") String arguments) {
        return "job '" + jobName + "' triggered";
    }
}
