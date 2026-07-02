package com.eoiagent.tool;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Capability;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.RunId;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.UserId;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-304: investigation tools (events/alerts/incidents/cases) + root-cause playbook.
 * All five are read-only {@code SUPPORT}/{@code INVESTIGATE} Java-API tools: schema derived from
 * the {@code @Tool} method (AC8 pattern), dispatch through the Phase-1 read-only registry emits
 * exactly one {@code TOOL_CALL} and never touches an approval gate, and the canned corpus is
 * coherent (events ↔ alerts ↔ incident ↔ case tell one {@code orders_daily} failure story).
 */
class InvestigationJavaApiToolsTest {

    private static final AgentContext SUPPORT = new AgentContext(new AppId("app"), new SessionId("s"),
            new UserId("u"), Role.SUPPORT, DeploymentProfile.OFFLINE, null, Map.of());
    private static final AgentContext USER = new AgentContext(new AppId("app"), new SessionId("s"),
            new UserId("u"), Role.USER, DeploymentProfile.OFFLINE, null, Map.of());

    private static JavaApiTool investigationTool(String methodName) throws Exception {
        Method m = InvestigationApi.class.getDeclaredMethod(methodName, String.class);
        return new JavaApiTool(new InvestigationApi(), m, false, Role.SUPPORT, Capability.INVESTIGATE);
    }

    private static JavaApiTool playbookTool() throws Exception {
        Method m = PlaybookApi.class.getDeclaredMethod("getPlaybook", String.class);
        return new JavaApiTool(new PlaybookApi(), m, false, Role.SUPPORT, Capability.INVESTIGATE);
    }

    private static DefaultToolRegistry readOnlyRegistryWithAllFive(RecordingAuditSink sink) throws Exception {
        DefaultToolRegistry reg = new DefaultToolRegistry(new FakePolicyEngine(), sink);
        for (String name : List.of("listEvents", "listAlerts", "getIncident", "listCases")) {
            reg.register(investigationTool(name));
        }
        reg.register(playbookTool());
        return reg;
    }

    // ── Spec derivation + classification (AC8 pattern) ────────────────────────────────────────────

    @Test
    void everyInvestigationToolIsReadOnlySupportInvestigate() throws Exception {
        for (String name : List.of("listEvents", "listAlerts", "getIncident", "listCases")) {
            ToolSpec spec = investigationTool(name).spec();

            assertThat(spec.name()).isEqualTo(name);
            assertThat(spec.mutating()).isFalse();
            assertThat(spec.requiredRole()).isEqualTo(Role.SUPPORT);
            assertThat(spec.capability()).isEqualTo(Capability.INVESTIGATE);
        }
    }

    @Test
    void specsDeriveParameterSchemasFromToolMethods() throws Exception {
        assertThat(investigationTool("listEvents").spec().jsonSchema()).contains("componentId");
        assertThat(investigationTool("listAlerts").spec().jsonSchema()).contains("severity");
        assertThat(investigationTool("getIncident").spec().jsonSchema()).contains("incidentId");
        assertThat(investigationTool("listCases").spec().jsonSchema()).contains("status");
        assertThat(playbookTool().spec().jsonSchema()).contains("issueKind");
    }

    // ── Read-only dispatch: one TOOL_CALL, no approval path (registry AC3) ────────────────────────

    @Test
    void dispatchThroughReadOnlyRegistryEmitsExactlyOneToolCall() throws Exception {
        RecordingAuditSink sink = new RecordingAuditSink();
        DefaultToolRegistry reg = readOnlyRegistryWithAllFive(sink);

        ToolResult result = reg.dispatch(
                new ToolCall("listEvents", Map.of("componentId", "orders_daily"), new RunId("r1")), SUPPORT);

        assertThat(result.ok()).isTrue();
        assertThat(String.valueOf(result.value())).contains("schema drift");
        assertThat(sink.kinds()).containsExactly(AuditKind.TOOL_CALL);
    }

    // ── Corpus coherence: signals cross-reference one failure story ───────────────────────────────

    @Test
    void incidentLinksTheAlertsAndSuspectsTheFailingComponent() {
        String incident = new InvestigationApi().getIncident("INC-2001");

        assertThat(incident).contains("A-101").contains("A-102").contains("orders_daily").contains("OPEN");
    }

    @Test
    void alertsFilterBySeverityAndCoverTheFailingComponent() {
        InvestigationApi api = new InvestigationApi();

        assertThat(api.listAlerts("HIGH")).contains("A-101").doesNotContain("A-102");
        assertThat(api.listAlerts("ALL")).contains("A-101").contains("A-102");
        assertThat(api.listAlerts("LOW")).isEqualTo("[]");
    }

    @Test
    void openCaseLinksTheIncident() {
        String cases = new InvestigationApi().listCases("OPEN");

        assertThat(cases).contains("C-501").contains("INC-2001").doesNotContain("C-472");
    }

    @Test
    void unknownLookupsReturnDataNotFaults() {
        InvestigationApi api = new InvestigationApi();

        assertThat(api.listEvents("no_such_component")).isEqualTo("[]");
        assertThat(api.getIncident("INC-9999")).contains("not found");
    }

    // ── Playbooks ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void pipelineFailurePlaybookOrdersSignalGatheringBeforeGatedRemediation() {
        String playbook = new PlaybookApi().getPlaybook("pipeline-failure");

        assertThat(playbook).contains("listEvents").contains("listAlerts").contains("getIncident");
        assertThat(playbook).contains("hypothesis");
        // remediation is last and explicitly gated — never an ungated mutation
        assertThat(playbook.indexOf("listEvents")).isLessThan(playbook.indexOf("approval"));
        assertThat(playbook).contains("dry-run + approval");
    }

    @Test
    void unknownIssueKindNamesTheKnownPlaybooks() {
        String playbook = new PlaybookApi().getPlaybook("volcano");

        assertThat(playbook).contains("unknown issue kind")
                .contains("pipeline-failure").contains("data-quality").contains("job-stuck");
    }

    // ── Visibility: SUPPORT sees all five; USER (lower rank) sees none ────────────────────────────

    @Test
    void visibleToSupportButNotToUser() throws Exception {
        DefaultToolRegistry reg = readOnlyRegistryWithAllFive(new RecordingAuditSink());

        assertThat(reg.visibleTo(SUPPORT)).extracting(ToolSpec::name)
                .containsExactlyInAnyOrder("listEvents", "listAlerts", "getIncident", "listCases", "getPlaybook");
        assertThat(reg.visibleTo(USER)).isEmpty();
    }
}
