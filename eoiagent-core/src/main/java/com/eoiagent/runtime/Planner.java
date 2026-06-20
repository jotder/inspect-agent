package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Goal;
import com.eoiagent.core.Plan;

/** Port that produces and revises plans for goals. */
public interface Planner {

    Plan plan(Goal goal, AgentContext ctx);

    Plan revise(Plan plan, Observation obs);
}
