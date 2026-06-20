package com.eoiagent.safety;

/**
 * How an input guardrail treats detected PII (config key {@code eoiagent.guardrail.input.pii}):
 * leave it ({@link #OFF}), mask it and continue ({@link #REDACT}), or block the turn ({@link #BLOCK}).
 */
public enum PiiMode {
    OFF,
    REDACT,
    BLOCK
}
