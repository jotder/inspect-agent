package com.eoiagent.core;

/** A single step in a plan, flagged as mutating if it changes state. */
public record PlanStep(TaskId id, String description, boolean mutating) {
}
