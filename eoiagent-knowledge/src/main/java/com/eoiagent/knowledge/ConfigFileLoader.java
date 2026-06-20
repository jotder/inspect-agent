package com.eoiagent.knowledge;

/** Loads pipeline/job config files ({@code PIPELINE_CONFIG}; e.g. TOON/NiFi text). */
public final class ConfigFileLoader implements DocumentLoader {

    @Override
    public String sourceType() {
        return "PIPELINE_CONFIG";
    }
}
