package com.eoiagent.core;

/** A tracked unit of work with current status and an optional note. */
public record Task(TaskId id, String description, TaskStatus status, String note) {
}
