package com.eoiagent.core;

/** Closed set of audit event categories. */
public enum AuditKind {
    MODEL_CALL,
    TOOL_CALL,
    DECISION,
    APPROVAL,
    MUTATION,
    RETRIEVAL,
    ERROR
}
