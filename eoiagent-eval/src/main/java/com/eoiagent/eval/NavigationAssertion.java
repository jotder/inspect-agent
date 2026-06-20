package com.eoiagent.eval;

import java.util.Map;

/** An assertion over a navigation intent's target page, required params and rationale. */
public record NavigationAssertion(String targetPageId,
                                  Map<String, String> requiredParams,
                                  MatchMode rationaleMode,
                                  String rationale) {
}
