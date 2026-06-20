package com.eoiagent.core;

/** Declarative description of a tool, including its schema and required role/capability. */
public record ToolSpec(String name,
                       String description,
                       String jsonSchema,
                       boolean mutating,
                       Role requiredRole,
                       Capability capability) {
}
