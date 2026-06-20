package com.eoiagent.tool;

import dev.langchain4j.internal.Json;

import java.util.List;
import java.util.Map;

/**
 * Phase-1 argument validation: checks that every field named in the tool's JSON-Schema
 * {@code "required"} array is present (and non-null) in the call arguments. Full type/constraint
 * validation is deferred (Phase 2, {@code eoiagent.tools.argValidation}).
 *
 * <p>Parses the schema with LangChain4j's JSON facade — this is an adapter module that already
 * depends on LC4j, so the core ports and {@link DefaultToolRegistry}'s policy/audit logic stay
 * library-agnostic while the JSON coupling lives here.
 */
final class ArgumentValidator {

    private ArgumentValidator() {
    }

    /** @return {@code null} when valid, otherwise a human-readable reason for the failure. */
    @SuppressWarnings("unchecked")
    static String validate(String jsonSchema, Map<String, Object> arguments) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            return null;
        }
        Map<String, Object> schema;
        try {
            schema = Json.fromJson(jsonSchema, Map.class);
        } catch (RuntimeException e) {
            return null; // unparseable schema is a tool-authoring bug, not a caller error — don't reject
        }
        if (schema == null || !(schema.get("required") instanceof List<?> required)) {
            return null;
        }
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        for (Object field : required) {
            String name = String.valueOf(field);
            if (!args.containsKey(name) || args.get(name) == null) {
                return "missing required field '" + name + "'";
            }
        }
        return null;
    }
}
