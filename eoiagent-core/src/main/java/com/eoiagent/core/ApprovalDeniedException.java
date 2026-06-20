package com.eoiagent.core;

/** Thrown when a required human approval is denied or times out. */
public class ApprovalDeniedException extends EoiAgentException {

    public ApprovalDeniedException(String message) {
        super(message);
    }

    public ApprovalDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
