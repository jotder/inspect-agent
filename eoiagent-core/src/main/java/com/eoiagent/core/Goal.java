package com.eoiagent.core;

/** A user goal to be planned and executed, classified by kind. */
public record Goal(String text, GoalKind kind) {
}
