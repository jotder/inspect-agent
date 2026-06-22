package com.eoiagent.tool;

/**
 * Reference host service exposing platform configuration editing as a mutating {@code @Tool}
 * method. Wrap the method in a {@link JavaApiTool} with {@code mutating=true} and
 * {@code requiredRole=ADMIN}.
 */
public class ConfigApi {

    @dev.langchain4j.agent.tool.Tool("Edit a platform configuration key to a new value")
    public String editConfig(
            @dev.langchain4j.agent.tool.P("configuration key") String key,
            @dev.langchain4j.agent.tool.P("new value") String value) {
        return "config '" + key + "' updated to '" + value + "'";
    }
}
