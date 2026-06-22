package com.eoiagent.tool;

/**
 * Reference host service exposing pipeline authoring and execution as mutating {@code @Tool}
 * methods. Wrap each method in a {@link JavaApiTool} with {@code mutating=true} and register into
 * a mutating-capable {@link DefaultToolRegistry}.
 */
public class PipelineApi {

    @dev.langchain4j.agent.tool.Tool("Author (create or update) a named data pipeline with the given definition")
    public String authorPipeline(
            @dev.langchain4j.agent.tool.P("pipeline name") String name,
            @dev.langchain4j.agent.tool.P("pipeline definition in YAML or JSON") String definition) {
        return "pipeline '" + name + "' authored";
    }

    @dev.langchain4j.agent.tool.Tool("Execute a named pipeline with the given parameter overrides")
    public String runPipeline(
            @dev.langchain4j.agent.tool.P("pipeline identifier") String pipelineId,
            @dev.langchain4j.agent.tool.P("JSON parameter overrides") String parameters) {
        return "pipeline '" + pipelineId + "' started";
    }
}
