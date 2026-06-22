package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import com.eoiagent.safety.DryRunProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-204: mutating Java-API tools (pipeline author/run, config edit, job trigger).
 * AC8: schema derived from @Tool method; mutating/role/capability from host classification.
 * Also covers dry-run preview content and end-to-end approved dispatch.
 */
class MutatingJavaApiToolsTest {

    private static final AgentContext SUPPORT = new AgentContext(new AppId("app"), new SessionId("s"),
            new UserId("u"), Role.SUPPORT, DeploymentProfile.ON_PREM_HOSTED, null, Map.of());

    // ── AC8: spec schema + classification for each mutating tool ──────────────────────────────────

    @Test
    void authorPipelineSpecDerivesSchemaAndCarriesClassification() throws Exception { // AC8
        Method m = PipelineApi.class.getDeclaredMethod("authorPipeline", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new PipelineApi(), m, true, Role.SUPPORT, Capability.AUTHOR_PIPELINE);

        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("authorPipeline");
        assertThat(spec.description()).containsIgnoringCase("pipeline");
        assertThat(spec.jsonSchema()).contains("name").contains("definition");
        assertThat(spec.mutating()).isTrue();
        assertThat(spec.requiredRole()).isEqualTo(Role.SUPPORT);
        assertThat(spec.capability()).isEqualTo(Capability.AUTHOR_PIPELINE);
    }

    @Test
    void runPipelineSpecDerivesSchemaAndCarriesClassification() throws Exception { // AC8
        Method m = PipelineApi.class.getDeclaredMethod("runPipeline", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new PipelineApi(), m, true, Role.SUPPORT, Capability.RUN_PIPELINE);

        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("runPipeline");
        assertThat(spec.jsonSchema()).contains("pipelineId").contains("parameters");
        assertThat(spec.mutating()).isTrue();
        assertThat(spec.capability()).isEqualTo(Capability.RUN_PIPELINE);
    }

    @Test
    void editConfigSpecDerivesSchemaAndCarriesClassification() throws Exception { // AC8
        Method m = ConfigApi.class.getDeclaredMethod("editConfig", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new ConfigApi(), m, true, Role.ADMIN, Capability.EDIT_CONFIG);

        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("editConfig");
        assertThat(spec.jsonSchema()).contains("key").contains("value");
        assertThat(spec.mutating()).isTrue();
        assertThat(spec.requiredRole()).isEqualTo(Role.ADMIN);
        assertThat(spec.capability()).isEqualTo(Capability.EDIT_CONFIG);
    }

    @Test
    void triggerJobSpecDerivesSchemaAndCarriesClassification() throws Exception { // AC8
        Method m = JobApi.class.getDeclaredMethod("triggerJob", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new JobApi(), m, true, Role.SUPPORT, Capability.TRIGGER_JOB);

        ToolSpec spec = tool.spec();

        assertThat(spec.name()).isEqualTo("triggerJob");
        assertThat(spec.jsonSchema()).contains("jobName").contains("arguments");
        assertThat(spec.mutating()).isTrue();
        assertThat(spec.capability()).isEqualTo(Capability.TRIGGER_JOB);
    }

    // ── DryRunProvider previews ───────────────────────────────────────────────────────────────────

    @Test
    void authorPipelineDryRunProducesPreview() {
        DryRunProvider provider = new AuthorPipelineDryRun();
        ToolCall call = new ToolCall("authorPipeline",
                Map.of("name", "etl_daily", "definition", "steps: [extract, load]"), new RunId("r1"));

        DryRunResult result = provider.preview(call);

        assertThat(result.supported()).isTrue();
        assertThat(result.preview()).contains("etl_daily");
        assertThat(result.predictedEffects()).containsKey("pipeline");
    }

    @Test
    void runPipelineDryRunProducesPreview() {
        DryRunProvider provider = new RunPipelineDryRun();
        ToolCall call = new ToolCall("runPipeline",
                Map.of("pipelineId", "p-001", "parameters", "{\"env\":\"prod\"}"), new RunId("r1"));

        DryRunResult result = provider.preview(call);

        assertThat(result.supported()).isTrue();
        assertThat(result.preview()).contains("p-001");
        assertThat(result.predictedEffects()).containsEntry("action", "run");
    }

    @Test
    void editConfigDryRunProducesPreview() {
        DryRunProvider provider = new EditConfigDryRun();
        ToolCall call = new ToolCall("editConfig",
                Map.of("key", "max.batch.size", "value", "5000"), new RunId("r1"));

        DryRunResult result = provider.preview(call);

        assertThat(result.supported()).isTrue();
        assertThat(result.preview()).contains("max.batch.size").contains("5000");
        assertThat(result.predictedEffects()).containsEntry("action", "edit");
    }

    @Test
    void triggerJobDryRunProducesPreview() {
        DryRunProvider provider = new TriggerJobDryRun();
        ToolCall call = new ToolCall("triggerJob",
                Map.of("jobName", "nightly_report", "arguments", "{}"), new RunId("r1"));

        DryRunResult result = provider.preview(call);

        assertThat(result.supported()).isTrue();
        assertThat(result.preview()).contains("nightly_report");
        assertThat(result.predictedEffects()).containsEntry("job", "nightly_report");
    }

    // ── End-to-end: approved mutating JavaApiTool dispatched through registry ─────────────────────

    @Test
    void approvedRunPipelineInvokesAndEmitsApprovalThenMutation() throws Exception {
        RecordingAuditSink sink = new RecordingAuditSink();
        Method m = PipelineApi.class.getDeclaredMethod("runPipeline", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new PipelineApi(), m, true, Role.SUPPORT, Capability.RUN_PIPELINE);

        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(),
                new ScriptedApprovalGate(ApprovalDecision.APPROVED),
                new FakeConfigProvider(DeploymentProfile.ON_PREM_HOSTED, true),
                sink);
        reg.register(tool);

        ToolResult result = reg.dispatch(
                new ToolCall("runPipeline", Map.of("pipelineId", "pipe-1", "parameters", "{}"), new RunId("r1")),
                SUPPORT);

        assertThat(result.ok()).isTrue();
        assertThat(String.valueOf(result.value())).contains("pipe-1");
        assertThat(sink.kinds()).containsExactly(AuditKind.APPROVAL, AuditKind.MUTATION);
    }

    @Test
    void deniedEditConfigDoesNotInvoke() throws Exception {
        RecordingAuditSink sink = new RecordingAuditSink();
        Method m = ConfigApi.class.getDeclaredMethod("editConfig", String.class, String.class);
        JavaApiTool tool = new JavaApiTool(new ConfigApi(), m, true, Role.ADMIN, Capability.EDIT_CONFIG);

        AgentContext adminCtx = new AgentContext(new AppId("app"), new SessionId("s"),
                new UserId("u"), Role.ADMIN, DeploymentProfile.ON_PREM_HOSTED, null, Map.of());
        DefaultToolRegistry reg = new DefaultToolRegistry(
                new FakePolicyEngine(),
                new ScriptedApprovalGate(ApprovalDecision.DENIED),
                new FakeConfigProvider(DeploymentProfile.ON_PREM_HOSTED, true),
                sink);
        reg.register(tool);

        ToolResult result = reg.dispatch(
                new ToolCall("editConfig", Map.of("key", "timeout", "value", "30"), new RunId("r1")),
                adminCtx);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("DENIED");
        assertThat(sink.kinds()).containsExactly(AuditKind.APPROVAL);
        assertThat(sink.kinds()).doesNotContain(AuditKind.MUTATION);
    }
}
