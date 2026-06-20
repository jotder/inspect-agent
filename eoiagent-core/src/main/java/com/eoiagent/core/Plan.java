package com.eoiagent.core;

import java.util.List;

/** An ordered list of plan steps with a human-readable summary. */
public record Plan(List<PlanStep> steps, String summary) {
}
