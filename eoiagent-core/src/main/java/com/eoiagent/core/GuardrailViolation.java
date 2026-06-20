package com.eoiagent.core;

/** Thrown when a guardrail fails closed on input or output. */
public class GuardrailViolation extends EoiAgentException {

    public GuardrailViolation(String message) {
        super(message);
    }

    public GuardrailViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
