package com.eoiagent.runtime;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Citation;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import java.util.List;

/** The full result of an orchestrated run: answer, tasks, citations and step count. */
public record AgentRun(RunId id, AgentAnswer answer, TaskList tasks, List<Citation> citations, int steps) {
}
