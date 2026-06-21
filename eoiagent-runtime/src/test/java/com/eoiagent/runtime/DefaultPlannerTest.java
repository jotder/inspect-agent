package com.eoiagent.runtime;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.Plan;
import com.eoiagent.core.PlanStep;
import com.eoiagent.core.Role;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.TaskId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DefaultPlanner: QA pass-through, registry-derived mutating flags (T-201 AC6), and non-mutating revise. */
class DefaultPlannerTest {

    private static AgentContext ctx() {
        return new AgentContext(new AppId("app"), new SessionId("s"), new UserId("u"),
                Role.ADMIN, DeploymentProfile.ON_PREM_HOSTED, null, Map.of());
    }

    private static ToolSpec spec(String name, boolean mutating) {
        return new ToolSpec(name, "test", "{}", mutating, Role.USER, Capability.READ_DOCS);
    }

    /** Exposes a fixed catalogue to the planner (both kinds), unlike the Phase-1 registry which hides mutating. */
    private static ToolRegistry catalogue(ToolSpec... specs) {
        return new ToolRegistry() {
            @Override public void register(com.eoiagent.tool.Tool tool) { throw new UnsupportedOperationException(); }
            @Override public List<ToolSpec> visibleTo(AgentContext ctx) { return List.of(specs); }
            @Override public ToolResult dispatch(ToolCall call, AgentContext ctx) { throw new UnsupportedOperationException(); }
        };
    }

    @Test
    void qaGoalIsSingleNonMutatingPassThroughWithNoModelCall() {
        // A gateway that throws if touched proves QA planning never calls the model (OFFLINE-safe).
        ScriptedGateway gateway = new ScriptedGateway().failsWith(new IllegalStateException("QA must not call the model"));
        DefaultPlanner planner = new DefaultPlanner(gateway, catalogue(spec("read_docs", false)));

        Plan plan = planner.plan(new Goal("how do I read KPIs?", GoalKind.QA), ctx());

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).mutating()).isFalse();
        assertThat(gateway.chatCalls).isZero();
    }

    @Test
    void flagsStepMutatingIffToolSpecIsMutating() { // AC6
        ScriptedGateway gateway = new ScriptedGateway().toolCalls("read_docs", "run_pipeline");
        DefaultPlanner planner = new DefaultPlanner(gateway,
                catalogue(spec("read_docs", false), spec("run_pipeline", true)));

        Plan plan = planner.plan(
                new Goal("rerun the nightly load after checking the runbook", GoalKind.PIPELINE_AUTHOR), ctx());

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.steps().get(0).mutating()).isFalse(); // read_docs
        assertThat(plan.steps().get(1).mutating()).isTrue();  // run_pipeline
    }

    @Test
    void reviseDropsObservedStepWithoutMutatingInput() {
        Plan original = new Plan(List.of(
                new PlanStep(new TaskId("step-1"), "a", false),
                new PlanStep(new TaskId("step-2"), "b", true)), "two steps");
        DefaultPlanner planner = new DefaultPlanner(new ScriptedGateway(), catalogue());

        Plan revised = planner.revise(original, new Observation(new TaskId("step-1"), true, "done", null));

        assertThat(revised).isNotSameAs(original);
        assertThat(revised.steps()).extracting(s -> s.id().value()).containsExactly("step-2");
        assertThat(original.steps()).hasSize(2); // input untouched
    }

    @Test
    void reviseWithNoOpObservationReturnsEquivalentPlan() {
        Plan original = new Plan(List.of(new PlanStep(new TaskId("step-1"), "a", false)), "one step");
        DefaultPlanner planner = new DefaultPlanner(new ScriptedGateway(), catalogue());

        Plan revised = planner.revise(original, new Observation(null, true, "", null));

        assertThat(revised).isNotSameAs(original);
        assertThat(revised.steps()).isEqualTo(original.steps());
        assertThat(revised.summary()).isEqualTo("one step");
    }
}
