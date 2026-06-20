package com.eoiagent.host;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Goal;
import com.eoiagent.runtime.AgentRun;
import com.eoiagent.runtime.Orchestrator;

/** In-test {@link Orchestrator}: returns a fixed {@link AgentRun} or throws, and captures its inputs. */
final class StubOrchestrator implements Orchestrator {

    private AgentRun fixed;
    private RuntimeException failure;
    AgentContext lastCtx;
    Goal lastGoal;

    StubOrchestrator returning(AgentRun run) {
        this.fixed = run;
        return this;
    }

    StubOrchestrator failing(RuntimeException e) {
        this.failure = e;
        return this;
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        this.lastGoal = goal;
        this.lastCtx = ctx;
        if (failure != null) {
            throw failure;
        }
        return fixed;
    }
}
