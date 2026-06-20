package com.eoiagent.core;

/** Thrown when an action is denied by the policy engine. */
public class PolicyViolation extends EoiAgentException {

    public PolicyViolation(String message) {
        super(message);
    }

    public PolicyViolation(String message, Throwable cause) {
        super(message, cause);
    }
}
