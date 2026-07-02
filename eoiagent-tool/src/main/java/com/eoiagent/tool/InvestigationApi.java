package com.eoiagent.tool;

/**
 * Reference host service exposing the operational investigation surface — events, alerts,
 * incidents, and support cases — as read-only {@code @Tool} methods (Flow E signal gathering).
 * Wrap each method in a {@link JavaApiTool} with {@code mutating=false},
 * {@code requiredRole=SUPPORT}, {@code capability=INVESTIGATE}.
 *
 * <p>Returns canned but coherent sample data — a failing {@code orders_daily} pipeline traced by
 * events E-1..E-3, alerts A-101/A-102, incident INC-2001 and case C-501 — so investigation flows
 * and evals run without a live operational system. A real product replaces this class with calls
 * into its own Java API. Dynamic operational data comes through tools, never RAG (glossary
 * §Corpus). A "not found" lookup is data, not a fault: it returns an {@code {"error": ...}}
 * payload rather than throwing.
 */
public class InvestigationApi {

    @dev.langchain4j.agent.tool.Tool("List recent operational events for a component, oldest first")
    public String listEvents(
            @dev.langchain4j.agent.tool.P("component identifier, e.g. a pipeline or job id") String componentId) {
        if ("orders_daily".equals(componentId)) {
            return """
                    [{"id":"E-1","ts":"2026-07-01T02:00:00Z","component":"orders_daily","message":"run 412 started"},
                     {"id":"E-2","ts":"2026-07-01T02:03:11Z","component":"orders_daily","message":"schema drift detected: source column 'discount' changed STRING -> DECIMAL(10,2)"},
                     {"id":"E-3","ts":"2026-07-01T02:03:12Z","component":"orders_daily","message":"run 412 failed: write rejected by schema validation on table curated.orders"}]""";
        }
        return "[]";
    }

    @dev.langchain4j.agent.tool.Tool("List active alerts, optionally filtered by severity (HIGH, MEDIUM, LOW or ALL)")
    public String listAlerts(
            @dev.langchain4j.agent.tool.P("severity filter: HIGH, MEDIUM, LOW or ALL") String severity) {
        String high = "{\"id\":\"A-101\",\"severity\":\"HIGH\",\"component\":\"orders_daily\","
                + "\"message\":\"orders_daily failure rate 100% over last 3 runs\"}";
        String medium = "{\"id\":\"A-102\",\"severity\":\"MEDIUM\",\"component\":\"curated.orders\","
                + "\"message\":\"freshness lag 26h exceeds 24h SLO\"}";
        return switch (severity == null ? "ALL" : severity.toUpperCase()) {
            case "HIGH" -> "[" + high + "]";
            case "MEDIUM" -> "[" + medium + "]";
            case "LOW" -> "[]";
            default -> "[" + high + "," + medium + "]";
        };
    }

    @dev.langchain4j.agent.tool.Tool("Get an incident by id: status, suspected component, linked alerts and timeline")
    public String getIncident(
            @dev.langchain4j.agent.tool.P("incident identifier, e.g. INC-2001") String incidentId) {
        if ("INC-2001".equals(incidentId)) {
            return """
                    {"id":"INC-2001","status":"OPEN","title":"orders_daily pipeline failing since 2026-07-01",
                     "suspectedComponent":"orders_daily","linkedAlerts":["A-101","A-102"],
                     "timeline":[{"ts":"2026-07-01T02:05:00Z","entry":"opened from alert A-101"},
                                 {"ts":"2026-07-01T06:40:00Z","entry":"freshness alert A-102 linked"}]}""";
        }
        return "{\"error\":\"incident '" + incidentId + "' not found\"}";
    }

    @dev.langchain4j.agent.tool.Tool("List support cases, optionally filtered by status (OPEN, RESOLVED or ALL)")
    public String listCases(
            @dev.langchain4j.agent.tool.P("status filter: OPEN, RESOLVED or ALL") String status) {
        String open = "{\"id\":\"C-501\",\"status\":\"OPEN\",\"linkedIncident\":\"INC-2001\","
                + "\"summary\":\"customer reports stale revenue dashboard\"}";
        String resolved = "{\"id\":\"C-472\",\"status\":\"RESOLVED\",\"linkedIncident\":null,"
                + "\"summary\":\"one-off export re-run requested\"}";
        return switch (status == null ? "ALL" : status.toUpperCase()) {
            case "OPEN" -> "[" + open + "]";
            case "RESOLVED" -> "[" + resolved + "]";
            default -> "[" + open + "," + resolved + "]";
        };
    }
}
