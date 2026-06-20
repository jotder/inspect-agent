package com.eoiagent.core;

/** Closed set of lifecycle states for a task. */
public enum TaskStatus {
    PENDING,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    FAILED,
    NEEDS_APPROVAL
}
