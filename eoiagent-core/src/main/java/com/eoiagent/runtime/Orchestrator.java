package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Goal;

/** Port that drives a goal to completion, producing an AgentRun. */
public interface Orchestrator {

    AgentRun run(Goal goal, AgentContext ctx);
}
