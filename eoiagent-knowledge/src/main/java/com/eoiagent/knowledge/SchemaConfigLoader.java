package com.eoiagent.knowledge;

/** Loads schema / data-model configs ({@code SCHEMA_CONFIG}). */
public final class SchemaConfigLoader implements DocumentLoader {

    @Override
    public String sourceType() {
        return "SCHEMA_CONFIG";
    }
}
