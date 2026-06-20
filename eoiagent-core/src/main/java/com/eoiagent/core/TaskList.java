package com.eoiagent.core;

import java.util.List;

/** An ordered collection of tasks for a run. */
public record TaskList(List<Task> tasks) {
}
