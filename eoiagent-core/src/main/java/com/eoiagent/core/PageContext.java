package com.eoiagent.core;

import java.util.Map;

/** The host page the user is on, with bound entity ids and active filters. */
public record PageContext(String pageId, Map<String, String> entityIds, Map<String, String> filters) {
}
