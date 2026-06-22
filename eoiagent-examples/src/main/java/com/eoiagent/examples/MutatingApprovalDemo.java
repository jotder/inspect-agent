package com.eoiagent.examples;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Feature;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.safety.ApprovalHandler;
import com.eoiagent.safety.CallbackApprovalGate;
import com.eoiagent.safety.RoleBasedPolicyEngine;
import com.eoiagent.tool.DefaultToolRegistry;
import com.eoiagent.tool.JavaApiTool;
import com.eoiagent.tool.PipelineApi;
import com.eoiagent.tool.RunPipelineDryRun;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Map;

/**
 * Phase-2, Flow C — plan→approve→act for a MUTATING action (T-202 ApprovalGate, T-203 RBAC,
 * T-204 mutating Java-API tools). A host {@code @Tool} method ({@link PipelineApi#runPipeline}) is
 * wrapped as a mutating {@link JavaApiTool} and dispatched through a mutating-capable
 * {@link DefaultToolRegistry}, which routes every mutating call through the {@link CallbackApprovalGate}
 * (dry-run preview → human decision) and audits {@code APPROVAL} then {@code MUTATION}.
 *
 * <p>Three scenarios prove the safety invariant "no mutation without a preceding APPROVED":
 * <ol>
 *   <li><b>APPROVED</b> (ADMIN): dry-run preview, {@code APPROVAL}, the mutation runs, {@code MUTATION}.</li>
 *   <li><b>DENIED</b> (ADMIN): {@code APPROVAL} only — the tool is never invoked, no {@code MUTATION}.</li>
 *   <li><b>RBAC blocked</b> (SUPPORT lacks {@code RUN_PIPELINE}): rejected by the policy engine before
 *       any approval or invoke.</li>
 * </ol>
 * Mutating actions are gated on the {@code MUTATING_ACTIONS} feature and are not permitted OFFLINE, so
 * this demo runs under the {@code ON_PREM_HOSTED} profile.
 */
public final class MutatingApprovalDemo {

    private MutatingApprovalDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Flow C: plan -> approve -> act (mutating action with approval + RBAC)");

        JavaApiTool runPipeline = runPipelineTool();
        DemoSupport.kv("tool", runPipeline.spec().name() + " (mutating=" + runPipeline.spec().mutating()
                + ", requires " + runPipeline.spec().requiredRole() + "/" + runPipeline.spec().capability() + ")");

        ToolCall call = new ToolCall("runPipeline",
                Map.of("pipelineId", "orders-nightly", "parameters", "{\"window\":\"24h\"}"),
                new RunId("demo-approve"));

        scenario("1) ADMIN requests the run and the operator APPROVES", Role.ADMIN, ApprovalDecision.APPROVED, call);
        scenario("2) ADMIN requests the run and the operator DENIES", Role.ADMIN, ApprovalDecision.DENIED, call);
        scenario("3) SUPPORT requests the run (lacks RUN_PIPELINE capability)", Role.SUPPORT, ApprovalDecision.APPROVED, call);
    }

    private static void scenario(String title, Role role, ApprovalDecision decision, ToolCall call) {
        System.out.println();
        DemoSupport.bullet(title);

        ConsoleAuditSink audit = new ConsoleAuditSink();
        // The operator decision the host UI would collect; here it's scripted and logged to the console.
        ApprovalHandler operator = req -> {
            DemoSupport.kv("  dry-run", req.preview().supported() ? req.preview().preview() : "(no preview)");
            DemoSupport.kv("  operator", decision);
            return decision;
        };
        CallbackApprovalGate gate = CallbackApprovalGate.builder()
                .handler(operator)
                .timeout(Duration.ofSeconds(5))
                .dryRunProvider("runPipeline", new RunPipelineDryRun())
                .build();
        DefaultToolRegistry registry = new DefaultToolRegistry(
                new RoleBasedPolicyEngine(), gate,
                new DemoConfig(DeploymentProfile.ON_PREM_HOSTED, Feature.MUTATING_ACTIONS), audit);
        registry.register(runPipelineTool());

        AgentContext ctx = DemoSupport.context(role, DeploymentProfile.ON_PREM_HOSTED);
        try {
            ToolResult result = registry.dispatch(call, ctx);
            DemoSupport.kv("  result", result.ok() ? "OK -> " + result.value() : "BLOCKED -> " + result.error());
        } catch (PolicyViolation e) {
            DemoSupport.kv("  result", "POLICY VIOLATION -> " + e.getMessage());
        }
    }

    private static JavaApiTool runPipelineTool() {
        try {
            Method m = PipelineApi.class.getDeclaredMethod("runPipeline", String.class, String.class);
            return new JavaApiTool(new PipelineApi(), m, true, Role.SUPPORT, Capability.RUN_PIPELINE);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
