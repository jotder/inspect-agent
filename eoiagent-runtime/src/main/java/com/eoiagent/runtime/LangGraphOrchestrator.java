package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.Goal;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.ToolCall;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.persistence.Checkpoint;
import com.eoiagent.persistence.CheckpointStore;
import com.eoiagent.safety.ApprovalGate;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link Orchestrator} for long-running issue investigation (Flow E), backed by
 * {@code org.bsc.langgraph4j} — the EXPERIMENTAL, single-maintainer stateful-graph engine,
 * quarantined to this adapter module per ADR-0005 / ADR-0010 (no {@code org.bsc.langgraph4j} type
 * appears in core or any non-adapter package). It builds a <em>cyclical</em> graph
 * {@code gatherSignals → hypothesize → testHypothesis → (loop back | escalate | conclude)} and drives
 * it node-by-node, reaching the model only through the {@link LlmGateway} port. After each node it
 * appends a {@link Checkpoint} to the {@link CheckpointStore} so the run can later be resumed or replayed.
 *
 * <p>Phase 3:
 * <ul>
 *   <li><b>Checkpoint after each node</b> (T-301) — append-only via the {@link CheckpointStore}.</li>
 *   <li><b>Breakpoint + HITL</b> (T-303) — before the {@code escalate} node takes effect it routes
 *       through the {@link ApprovalGate} (Flow C semantics): only an {@code APPROVED} decision escalates;
 *       {@code DENIED}/{@code TIMED_OUT} concludes without escalating. The approval is audited.</li>
 *   <li><b>Resume after restart</b> (T-303) — {@link #resume(RunId, Goal, AgentContext)} rehydrates a
 *       run's {@link CheckpointStore#latest latest} checkpoint and re-enters the graph at the
 *       <em>next</em> node (a conditional {@code START} router), continuing the checkpoint sequence
 *       rather than restarting. Time-travel/replay reads {@link CheckpointStore#history history}
 *       (oldest→newest).</li>
 * </ul>
 *
 * <p>The breakpoint gates {@code escalate} because it is the only human-review node in the Phase-3
 * graph; the same {@link ApprovalGate} gates the mutating investigation tools that arrive with T-304.
 *
 * <p>Offline fail-closed (AC11): the adapter performs no network call itself; any reachability is the
 * {@code LlmGateway} port's concern under the active {@link com.eoiagent.core.DeploymentProfile}, and a
 * headless {@link ApprovalGate} denies escalation by construction.
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
    private static final String K_ESCALATION_DENIED = "escalationDenied";
    private static final String K_CONCLUSION = "conclusion";
    /** Entry node to enter on a resumed run; absent on a fresh run (defaults to {@link #GATHER}). */
    private static final String K_RESUME_FROM = "__resumeFrom";

    private final LlmGateway gateway;
    private final AuditSink audit;
    private final ConfigProvider config;
    private final CheckpointStore checkpoints;
    private final ApprovalGate approvalGate;

    public LangGraphOrchestrator(LlmGateway gateway, AuditSink audit, ConfigProvider config,
                                 CheckpointStore checkpoints, ApprovalGate approvalGate) {
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.config = Objects.requireNonNull(config, "config");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.approvalGate = Objects.requireNonNull(approvalGate, "approvalGate");
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            return drive(goal, ctx, run, Map.of(), 0); // fresh run: no seeded state, start at seq 0
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (Exception e) {
            return failed(ctx, run, "The investigation failed: " + e.getMessage());
        }
    }

    /**
     * Resumes a previously-checkpointed run from its {@link CheckpointStore#latest latest} checkpoint,
     * re-entering the graph at the node after the last completed one — so completed nodes (and their
     * model calls) are not re-executed. The checkpoint sequence continues from where it left off. A run
     * already past {@code conclude} returns its saved conclusion without re-running (AC9).
     */
    public AgentRun resume(RunId previous, Goal goal, AgentContext ctx) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        Optional<Checkpoint> latest = checkpoints.latest(previous);
        if (latest.isEmpty()) {
            throw new ConfigException("cannot resume run " + previous.value() + ": no checkpoint found");
        }
        Checkpoint cp = latest.get();
        try {
            Map<String, Object> state = deserialize(cp.state());
            if (CONCLUDE.equals(cp.nodeId())) {
                audit.record(event(ctx, previous, AuditKind.DECISION, "resume: run already concluded"));
                String conclusion = String.valueOf(state.getOrDefault(K_CONCLUSION, ""));
                return new AgentRun(previous,
                        new AgentAnswer(AnswerKind.TEXT, conclusion, null, null, List.of(), previous),
                        emptyTasks(), List.of(), 0);
            }
            String resumeFrom = nextNode(cp.nodeId(), state);
            audit.record(event(ctx, previous, AuditKind.DECISION,
                    "resume from " + cp.nodeId() + " -> " + resumeFrom));
            Map<String, Object> seeded = new LinkedHashMap<>(state);
            seeded.put(K_RESUME_FROM, resumeFrom);
            return drive(goal, ctx, previous, seeded, cp.seq() + 1);
        } catch (ConfigException e) {
            throw e;
        } catch (Exception e) {
            return failed(ctx, previous, "Resume failed: " + e.getMessage());
        }
    }

    /** Streams the graph from {@code initialState}, checkpointing each node from {@code startSeq}. */
    private AgentRun drive(Goal goal, AgentContext ctx, RunId run, Map<String, Object> initialState, int startSeq)
            throws GraphStateException {
        CompiledGraph<InvestigationState> graph = buildGraph(goal, ctx, run);
        boolean checkpointEachNode = config.get(RuntimeConfigKeys.CHECKPOINT_EVERY_NODE);

        int executed = 0;
        InvestigationState last = null;
        for (NodeOutput<InvestigationState> out : graph.stream(initialState)) {
            String node = out.node();
            if (StateGraph.START.equals(node) || StateGraph.END.equals(node)) {
                continue; // sentinels carry no run state to checkpoint
            }
            last = out.state();
            if (checkpointEachNode) {
                checkpoints.save(run, new Checkpoint(run, node,
                        Json.toJson(last.data()).getBytes(StandardCharsets.UTF_8), Instant.now(), startSeq + executed));
            }
            executed++;
        }

        String conclusion = last == null ? "" : last.<String>value(K_CONCLUSION).orElse("");
        AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, conclusion, null, null, List.of(), run);
        return new AgentRun(run, answer, emptyTasks(), List.of(), executed);
    }

    /**
     * Assembles the cyclical investigation graph. {@code START} routes via a conditional edge to the
     * entry node — {@link #GATHER} for a fresh run, or the {@code __resumeFrom} node for a resumed run —
     * so resume re-enters mid-graph without re-running completed nodes. The {@code testHypothesis →
     * hypothesize} cycle is bounded by an explicit round budget and, as a backstop, the recursion limit.
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

        // Conditional entry: fresh run -> gatherSignals; resumed run -> the saved next node.
        g.addConditionalEdges(StateGraph.START, AsyncEdgeAction.edge_async(InvestigationState::entryNode),
                Map.of(GATHER, GATHER, HYPOTHESIZE, HYPOTHESIZE, TEST, TEST, ESCALATE, ESCALATE, CONCLUDE, CONCLUDE));
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

    /**
     * Human-in-the-loop breakpoint: before escalation takes effect, route through the {@link ApprovalGate}
     * (Flow C). Only {@code APPROVED} escalates; otherwise the run concludes without escalating. The
     * decision is audited as an {@code APPROVAL} event.
     */
    private Map<String, Object> escalate(InvestigationState s, AgentContext ctx, RunId run) {
        ToolCall escalation = new ToolCall("escalateInvestigation", Map.of("hypothesis", s.hypothesis()), run);
        DryRunResult preview = approvalGate.dryRun(escalation);
        ApprovalDecision decision = approvalGate.request(
                new ApprovalRequest(run, escalation, "Escalate investigation for human review: " + s.hypothesis(), preview));
        audit.record(event(ctx, run, AuditKind.APPROVAL, "escalate approval: " + decision));
        if (decision == ApprovalDecision.APPROVED) {
            audit.record(event(ctx, run, AuditKind.DECISION, "escalate: human review approved"));
            return Map.of(K_ESCALATED, Boolean.TRUE);
        }
        audit.record(event(ctx, run, AuditKind.DECISION,
                "escalate: human review " + decision.name().toLowerCase(Locale.ROOT)));
        return Map.of(K_ESCALATION_DENIED, Boolean.TRUE);
    }

    private Map<String, Object> conclude(InvestigationState s, AgentContext ctx, RunId run) {
        boolean escalated = s.<Boolean>value(K_ESCALATED).orElse(false);
        boolean denied = s.<Boolean>value(K_ESCALATION_DENIED).orElse(false);
        String tail = escalated ? "; escalated for human review."
                : denied ? "; escalation was not approved." : ".";
        String text = "Root-cause hypothesis: " + s.hypothesis()
                + " — based on " + s.signals().size() + " signal(s)" + tail;
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

    /** The node to enter after {@code completed} finishes — used to resume mid-graph. */
    private static String nextNode(String completed, Map<String, Object> state) {
        return switch (completed) {
            case GATHER -> HYPOTHESIZE;
            case HYPOTHESIZE -> TEST;
            case TEST -> switch (String.valueOf(state.getOrDefault(K_VERDICT, DONE))) {
                case MORE -> HYPOTHESIZE;
                case NEEDS_ESCALATION -> ESCALATE;
                default -> CONCLUDE;
            };
            default -> CONCLUDE; // escalate (or anything else) -> conclude
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deserialize(byte[] state) {
        if (state == null || state.length == 0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> map = Json.fromJson(new String(state, StandardCharsets.UTF_8), Map.class);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private AgentRun failed(AgentContext ctx, RunId run, String message) {
        audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + message));
        return new AgentRun(run, new AgentAnswer(AnswerKind.ERROR, message, null, null, List.of(), run),
                emptyTasks(), List.of(), 0);
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

        /** Entry node for this run: the resumed node if seeded, otherwise {@link #GATHER}. */
        String entryNode() {
            return this.<String>value(K_RESUME_FROM).orElse(GATHER);
        }

        @SuppressWarnings("unchecked")
        List<String> signals() {
            return (List<String>) this.value(K_SIGNALS).orElse(List.of());
        }

        String hypothesis() {
            return this.<String>value(K_HYPOTHESIS).orElse("(undetermined)");
        }

        int rounds() {
            // After a JSON checkpoint round-trip a number may come back as Double; coerce defensively.
            Object v = this.value(K_ROUNDS).orElse(0);
            return v instanceof Number n ? n.intValue() : 0;
        }

        String verdict() {
            return this.<String>value(K_VERDICT).orElse(DONE);
        }
    }
}
