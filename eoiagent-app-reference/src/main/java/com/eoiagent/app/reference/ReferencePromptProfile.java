package com.eoiagent.app.reference;

import com.eoiagent.app.PromptProfile;
import com.eoiagent.core.GoalKind;

import java.util.Map;

/**
 * Persona + per-{@link GoalKind} system prompts + a small domain glossary. {@code systemPrompt} is
 * total: it returns a sensible default for every {@code GoalKind} rather than null. The Flow-A
 * navigation heuristic (prefer routing the user to an existing page over re-deriving data inline) is
 * baked into the base prompt.
 */
final class ReferencePromptProfile implements PromptProfile {

    @Override
    public String persona() {
        return "You are the Acme Lakehouse assistant embedded in the product.";
    }

    @Override
    public String systemPrompt(GoalKind kind) {
        String base = persona() + " Prefer routing the user to an existing KPI/report page "
                + "(emit a NavigationIntent) over re-deriving data inline. Cite sources.";
        return switch (kind) {
            case SQL_GEN -> base + " Generate read-only SQL against Acme schemas only.";
            case INVESTIGATION -> base + " Investigate data-quality incidents using read-only tools.";
            default -> base; // QA, ANALYSIS, PIPELINE_AUTHOR, OPERATIONAL_ACTION — sensible default
        };
    }

    @Override
    public Map<String, String> domainGlossary() {
        return Map.of(
                "lakehouse", "Unified storage+warehouse layer holding Acme datasets",
                "zone", "raw | curated | mart storage tier",
                "pipeline", "An ETL job that materializes datasets on a schedule");
    }
}
