package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Goal;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.persistence.Checkpoint;
import com.eoiagent.persistence.CheckpointStore;

import dev.langchain4j.internal.Json;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link Orchestrator} for long-running issue investigation (Flow E), backed by
 * {@code org.bsc.langgraph4j} — the EXPERIMENTAL, single-maintainer stateful-graph engine,
 * quarantined to this adapter module per ADR-0005 / ADR-0010 (no {@code org.bsc.langgraph4j} type
 * appears in core or any non-adapter package). It builds a <em>cyclical</em> graph
 * {@code gatherSignals → hypothesize → testHypothesis → (loop back | escalate | conclude)} and drives
 * it node-by-node, reaching the model only through the {@link LlmGateway} port (node actions are
 * plain functions over graph state, so — unlike the agentic engine — nothing here touches a concrete
 * model type). After each node it appends a {@link Checkpoint} to the {@link CheckpointStore} so the
 * run can later be resumed or replayed.
 *
 * <p>Scope (T-301): the cyclical graph, audited node transitions, bounded termination, and
 * checkpoint-after-each-node. Resume-after-restart and the concrete {@code InMemory}/{@code Postgres}
 * checkpoint stores arrive with T-302; breakpoints / HITL / time-travel (pausing before a mutating or
 * {@code escalate} node for {@link com.eoiagent.core.Role}-gated approval, replaying from history)
 * arrive with T-303 — the graph already exposes the {@code escalate} node and a checkpoint per node
 * those tickets build on.
 *
 * <p>Offline fail-closed (AC11): the adapter performs no network call itself; any reachability is the
 * {@code LlmGateway} port's concern under the active {@link com.eoiagent.core.DeploymentProfile}.
 */
public final class LangGraphOrchestrator implements Orchestrator {

    // Graph node ids.
    private static final String GATHER = "gatherSignals";
    private static final String HYPOTHESIZE = "hypothesize";
    private static final String TEST = "testHypothesis";
    private static final String ESCALATE = "escalate";
    private static final String CONCLUDE = "conclude";

    // Conditional-edge routing keys out of TEST.
    private static final String MORE = "more";
    private static final String NEEDS_ESCALATION = "escalate";
    private static final String DONE = "done";

    // State keys (kept JSON-serializable so the whole state map is the checkpoint payload).
    private static final String K_SIGNALS = "signals";
    private static final String K_HYPOTHESIS = "hypothesis";
    private static final String K_ROUNDS = "rounds";
    private static final String K_VERDICT = "verdict";
    private static final String K_ESCALATED = "escalated";
    private static final String K_CONCLUSION = "conclusion";

    private final LlmGateway gateway;
    private final AuditSink audit;
    private final ConfigProvider config;
    private final CheckpointStore checkpoints;

    public LangGraphOrchestrator(LlmGateway gateway, AuditSink audit, ConfigProvider config,
                                 CheckpointStore checkpoints) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.config = Objects.requireNonNull(config, "config");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            return investigate(goal, ctx, run);
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (Exception e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The investigation failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, emptyTasks(), List.of(), 0);
        }
    }

    private AgentRun investigate(Goal goal, AgentContext ctx, RunId run) throws GraphStateException {
        CompiledGraph<InvestigationState> graph = buildGraph(goal, ctx, run);
        boolean checkpointEachNode = config.get(RuntimeConfigKeys.CHECKPOINT_EVERY_NODE);

        int nodes = 0;
        InvestigationState last = null;
        for (NodeOutput<InvestigationState> out : graph.stream(Map.of())) {
            String node = out.node();
            if (StateGraph.START.equals(node) || StateGraph.END.equals(node)) {
                continue; // sentinels carry no run state to checkpoint
            }
            last = out.state();
            if (checkpointEachNode) {
                checkpoints.save(run, new Checkpoint(run, node,
                        Json.toJson(last.data()).getBytes(StandardCharsets.UTF_8), Instant.now(), nodes));
            }
            nodes++;
        }

        String conclusion = last == null ? "" : last.<String>value(K_CONCLUSION).orElse("");
        AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, conclusion, null, null, List.of(), run);
        return new AgentRun(run, answer, emptyTasks(), List.of(), nodes);
    }

    /**
     * Assembles the cyclical investigation graph. The cycle is {@code testHypothesis → hypothesize};
     * it is bounded both by an explicit round budget (an inconclusive test concludes once
     * {@code maxSteps} rounds are spent) and, as a backstop against a logic bug, by the compiled
     * graph's recursion limit.
     */
    private CompiledGraph<InvestigationState> buildGraph(Goal goal, AgentContext ctx, RunId run)
            throws GraphStateException {
        int maxRounds = Math.max(1, config.get(RuntimeConfigKeys.MAX_STEPS));
        StateGraph<InvestigationState> g = new StateGraph<>(InvestigationState::new);

        g.addNode(GATHER, AsyncNodeAction.node_async(s -> gather(s, goal, ctx, run)));
        g.addNode(HYPOTHESIZE, AsyncNodeAction.node_async(s -> hypothesize(s, goal, ctx, run)));
        g.addNode(TEST, AsyncNodeAction.node_async(s -> test(s, ctx, run, maxRounds)));
        g.addNode(ESCALATE, AsyncNodeAction.node_async(s -> escalate(s, ctx, run)));
        g.addNode(CONCLUDE, AsyncNodeAction.node_async(s -> conclude(s, ctx, run)));

        g.addEdge(StateGraph.START, GATHER);
        g.addEdge(GATHER, HYPOTHESIZE);
        g.addEdge(HYPOTHESIZE, TEST);
        g.addConditionalEdges(TEST, AsyncEdgeAction.edge_async(InvestigationState::verdict),
                Map.of(MORE, HYPOTHESIZE, NEEDS_ESCALATION, ESCALATE, DONE, CONCLUDE));
        g.addEdge(ESCALATE, CONCLUDE);
        g.addEdge(CONCLUDE, StateGraph.END);

        return g.compile(CompileConfig.builder().recursionLimit(4 * maxRounds + 8).build());
    }

    // --- nodes (each reaches the model only through the LlmGateway port) -----------------------

    private Map<String, Object> gather(InvestigationState s, Goal goal, AgentContext ctx, RunId run) {
        ChatResult r = ask("List the operational signals to examine for: " + goal.text());
        audit.record(event(ctx, run, AuditKind.MODEL_CALL, "gatherSignals"));
        return Map.of(K_SIGNALS, List.of(blankToDefault(r.text(), "(no signals)")));
    }

    private Map<String, Object> hypothesize(InvestigationState s, Goal goal, AgentContext ctx, RunId run) {
        ChatResult r = ask("Given signals " + s.signals() + ", propose a root-cause hypothesis for: "
                + goal.text());
        audit.record(event(ctx, run, AuditKind.MODEL_CALL, "hypothesize"));
        return Map.of(K_HYPOTHESIS, blankToDefault(r.text(), "(undetermined)"));
    }

    private Map<String, Object> test(InvestigationState s, AgentContext ctx, RunId run, int maxRounds) {
        int round = s.rounds() + 1;
        ChatResult r = ask("Test this hypothesis: " + s.hypothesis()
                + ". Reply CONFIRMED, INCONCLUSIVE, or ESCALATE.");
        String verdict = verdictFor(r.text(), round, maxRounds);
        audit.record(event(ctx, run, AuditKind.DECISION, "testHypothesis round " + round + ": " + verdict));
        return Map.of(K_ROUNDS, round, K_VERDICT, verdict);
    }

    private Map<String, Object> escalate(InvestigationState s, AgentContext ctx, RunId run) {
        // T-303 routes this breakpoint through the ApprovalGate (HITL) before proceeding.
        audit.record(event(ctx, run, AuditKind.DECISION, "escalate: human review required"));
        return Map.of(K_ESCALATED, Boolean.TRUE);
    }

    private Map<String, Object> conclude(InvestigationState s, AgentContext ctx, RunId run) {
        boolean escalated = s.<Boolean>value(K_ESCALATED).orElse(false);
        String text = "Root-cause hypothesis: " + s.hypothesis()
                + " — based on " + s.signals().size() + " signal(s)"
                + (escalated ? "; escalated for human review." : ".");
        audit.record(event(ctx, run, AuditKind.DECISION, "conclude"));
        return Map.of(K_CONCLUSION, text);
    }

    private ChatResult ask(String prompt) {
        List<ChatMessageRecord> history =
                List.of(new ChatMessageRecord(ChatRole.USER, prompt, Instant.now(), Map.of()));
        return gateway.chat(new ChatRequest(history, List.of(), ChatOptions.defaults()));
    }

    /** Inconclusive tests loop until the round budget is spent, then conclude with what was learned. */
    private static String verdictFor(String reply, int round, int maxRounds) {
        String r = reply == null ? "" : reply.toUpperCase(Locale.ROOT);
        if (r.contains("ESCALATE")) {
            return NEEDS_ESCALATION;
        }
        if (r.contains("CONFIRMED")) {
            return DONE;
        }
        return round >= maxRounds ? DONE : MORE;
    }

    private static String blankToDefault(String text, String fallback) {
        return text == null || text.isBlank() ? fallback : text;
    }

    private static TaskList emptyTasks() {
        return new TaskList(List.<com.eoiagent.core.Task>of()); // Flow E summarizes; it has no planned task list
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, Map.<String, Object>of());
    }

    /** Graph state for an investigation; values are JSON-serializable so the map is the checkpoint. */
    public static final class InvestigationState extends AgentState {

        public InvestigationState(Map<String, Object> data) {
            super(data);
        }

        @SuppressWarnings("unchecked")
        List<String> signals() {
            return (List<String>) this.value(K_SIGNALS).orElse(List.of());
        }

        String hypothesis() {
            return this.<String>value(K_HYPOTHESIS).orElse("(undetermined)");
        }

        int rounds() {
            return this.<Integer>value(K_ROUNDS).orElse(0);
        }

        String verdict() {
            return this.<String>value(K_VERDICT).orElse(DONE);
        }
    }
}
