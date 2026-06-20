package com.eoiagent.core;

/** Closed set of guardrail outcomes. */
public enum GuardrailVerdict {
    PASS,
    FAIL,
    REDACTED,
    RETRY
}
