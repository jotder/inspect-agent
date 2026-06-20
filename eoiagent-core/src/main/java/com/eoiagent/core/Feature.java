package com.eoiagent.core;

/**
 * Capabilities that a {@link DeploymentProfile} may permit and config may turn on. A feature is
 * enabled only when the profile's capability matrix allows it <em>and</em> config enables it —
 * never by classpath presence. See {@code docs/architecture/03-deployment-profiles.md}.
 */
public enum Feature {
    HOSTED_MODELS,
    MUTATING_ACTIONS,
    MCP_TOOLS,
    PGVECTOR,
    LANGGRAPH_CHECKPOINTING,
    ADVANCED_RETRIEVAL,
    LONG_TERM_MEMORY
}
