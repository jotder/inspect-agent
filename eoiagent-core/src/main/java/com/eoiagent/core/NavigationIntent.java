package com.eoiagent.core;

import java.util.Map;

/** A request to navigate the host UI to a target page with parameters. */
public record NavigationIntent(String targetPageId, Map<String, String> parameters, String rationale) {
}
