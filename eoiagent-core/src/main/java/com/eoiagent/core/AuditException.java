package com.eoiagent.core;

/** Thrown when an audit event cannot be recorded. */
public class AuditException extends EoiAgentException {

    public AuditException(String message) {
        super(message);
    }

    public AuditException(String message, Throwable cause) {
        super(message, cause);
    }
}
