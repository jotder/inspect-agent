package com.eoiagent.tool;

/**
 * Reference host service exposing root-cause playbooks as a read-only {@code @Tool} method.
 * A playbook is the ordered investigation procedure the agent follows for a kind of issue —
 * each step names the investigation tool to call ({@link InvestigationApi}) or the action to
 * take, ending in a gated remediation or an escalation. Wrap in a {@link JavaApiTool} with
 * {@code mutating=false}, {@code requiredRole=SUPPORT}, {@code capability=INVESTIGATE}.
 */
public class PlaybookApi {

    @dev.langchain4j.agent.tool.Tool("Get the root-cause playbook (ordered steps) for an issue kind")
    public String getPlaybook(
            @dev.langchain4j.agent.tool.P("issue kind: pipeline-failure, data-quality or job-stuck") String issueKind) {
        return switch (issueKind == null ? "" : issueKind.toLowerCase()) {
            case "pipeline-failure" -> """
                    {"issueKind":"pipeline-failure","steps":[
                     "1. listEvents(<componentId>) — gather recent events for the failing pipeline",
                     "2. listAlerts(HIGH) — check active high-severity alerts for the same component",
                     "3. getIncident(<incidentId>) — correlate the incident timeline and linked alerts",
                     "4. inspect the events for schema/config drift and retrieve the relevant schema or config",
                     "5. state a root-cause hypothesis and test it against the failing run's evidence",
                     "6. propose remediation as a gated mutating action (dry-run + approval) or escalate to an operator"]}""";
            case "data-quality" -> """
                    {"issueKind":"data-quality","steps":[
                     "1. listAlerts(ALL) — find freshness/volume/quality alerts on the affected dataset",
                     "2. listEvents(<componentId>) — check the producing pipeline's recent runs",
                     "3. compare the dataset's schema against its schema config for drift",
                     "4. state a hypothesis (late upstream, drift, bad deploy) and verify against run history",
                     "5. propose remediation as a gated mutating action or escalate"]}""";
            case "job-stuck" -> """
                    {"issueKind":"job-stuck","steps":[
                     "1. listEvents(<jobId>) — confirm the job's last heartbeat and state transitions",
                     "2. listAlerts(ALL) — check for resource or dependency alerts",
                     "3. check upstream dependencies for incomplete inputs (getIncident on any linked incident)",
                     "4. propose a gated retrigger (triggerJob — dry-run + approval) or escalate"]}""";
            default -> "{\"error\":\"unknown issue kind '" + issueKind
                    + "'; known kinds: pipeline-failure, data-quality, job-stuck\"}";
        };
    }
}
