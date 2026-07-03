package com.eoiagent.core;

import java.util.Map;

/**
 * A request to navigate the host UI to a target page with parameters. The agent never performs UI
 * actions itself — it returns this typed intent and the HOST decides how to route (the signature
 * embeddable-product behavior).
 *
 * <p>T-353: navigation is requested through the reserved tool {@link #TOOL_NAME}, which the
 * platform derives from the pack's {@code NavigationCatalog}. A successful dispatch returns a
 * {@code Map} value keyed {@code targetPageId}/{@code parameters}/{@code rationale}, which the
 * orchestrator turns into this record as a terminal {@code NAVIGATION} answer.
 */
public record NavigationIntent(String targetPageId, Map<String, String> parameters, String rationale) {

    /** Reserved tool name through which the model proposes navigation. */
    public static final String TOOL_NAME = "navigate_to_page";
}
