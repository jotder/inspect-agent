package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.Goal;

import java.util.function.Consumer;

/** Port that drives a goal to completion, producing an AgentRun. */
public interface Orchestrator {

    AgentRun run(Goal goal, AgentContext ctx);

    /**
     * T-355: like {@link #run(Goal, AgentContext)} but forwarding answer tokens to {@code onToken}
     * as they arrive. Terminal events stay with the return value / thrown exception — the listener
     * receives only token text. The default emits the finished answer post-hoc (word-split), so
     * orchestrators without a live streaming path keep today's behavior; streaming-capable
     * implementations override to forward genuine model tokens.
     */
    default AgentRun run(Goal goal, AgentContext ctx, Consumer<String> onToken) {
        AgentRun run = run(goal, ctx);
        if (onToken != null && run.answer() != null && run.answer().text() != null) {
            for (String token : run.answer().text().split("\\s+")) {
                if (!token.isEmpty()) {
                    onToken.accept(token);
                }
            }
        }
        return run;
    }
}
