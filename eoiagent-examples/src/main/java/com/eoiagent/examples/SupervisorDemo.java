package com.eoiagent.examples;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.runtime.AgentRun;
import com.eoiagent.runtime.SupervisorOrchestrator;
import com.eoiagent.safety.RoleBasedPolicyEngine;
import com.eoiagent.scratchpad.InMemoryScratchpad;
import com.eoiagent.tool.DefaultToolRegistry;

import java.util.Map;

/**
 * Phase-2, Flow D — supervisor + sub-agents (T-205). A {@link SupervisorOrchestrator} runs an LLM
 * supervisor that delegates a sub-goal to one of a fixed worker catalogue (analysis / sql / pipeline);
 * each worker is an isolated nested ReAct loop with its own narrowed tool subset and its own
 * scratchpad scope, and the supervisor aggregates the worker's finding into one answer. Delegation is
 * hard-capped by {@code eoiagent.runtime.supervisor.maxWorkers}.
 *
 * <p>Offline-deterministic: a scripted {@link StubLlmGateway} plays the supervisor's delegation
 * decision, the worker's conclusion, then the supervisor's aggregation. The {@code DECISION} audit
 * events ({@code delegate to analysis}, {@code aggregate (1 worker)}) show the control flow.
 */
public final class SupervisorDemo {

    private SupervisorDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Flow D: supervisor delegates to an isolated sub-agent");

        StubLlmGateway gateway = StubLlmGateway.builder()
                .replyToolCalls(new ToolCall("analysis", Map.of(), null))      // supervisor: delegate to analysis
                .replyText("schema is consistent; row counts are nominal")     // analysis worker concludes
                .replyText("Investigation complete: the warehouse looks healthy.") // supervisor aggregates
                .defaultReplyText("done")
                .build();

        ConsoleAuditSink audit = new ConsoleAuditSink();
        DefaultToolRegistry registry = new DefaultToolRegistry(new RoleBasedPolicyEngine(), audit);
        SupervisorOrchestrator supervisor = new SupervisorOrchestrator(
                gateway, registry, new InMemoryScratchpad(), audit,
                new DemoConfig(DeploymentProfile.OFFLINE));

        AgentContext ctx = DemoSupport.context(Role.ANALYST, DeploymentProfile.OFFLINE);
        Goal goal = new Goal("is the warehouse healthy?", GoalKind.ANALYSIS);

        AgentRun run = supervisor.run(goal, ctx);

        System.out.println();
        DemoSupport.kv("answer", "[" + run.answer().kind() + "] " + run.answer().text());
        DemoSupport.kv("delegations", run.steps() + " (worker isolation: each runs in its own "
                + "tool subset + scratchpad scope)");
    }
}
