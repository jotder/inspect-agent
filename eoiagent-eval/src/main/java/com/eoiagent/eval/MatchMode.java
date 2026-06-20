package com.eoiagent.eval;

/** Closed set of strategies for matching an actual answer against an expected value. */
public enum MatchMode {
    EXACT,
    CONTAINS,
    REGEX,
    LLM_JUDGE
}
