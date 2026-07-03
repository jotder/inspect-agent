package com.eoiagent.runtime;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.Citation;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.Goal;
import com.eoiagent.core.GoalKind;
import com.eoiagent.core.NavigationIntent;
import com.eoiagent.core.RetrievalQuery;
import com.eoiagent.core.RetrievedChunk;
import com.eoiagent.core.RunId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolCallMeta;
import com.eoiagent.core.ToolResult;
import com.eoiagent.knowledge.Retriever;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.memory.MemoryStore;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.observability.Span;
import com.eoiagent.observability.SpanStatus;
import com.eoiagent.observability.TraceCollector;
import com.eoiagent.scratchpad.Scratchpad;
import com.eoiagent.tool.ToolRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The default/fallback {@link Orchestrator}: a bounded reason+act loop (Flow B). Each iteration asks
 * the {@link LlmGateway} for the next move given the conversation so far and the tools the context
 * may see; a tool call is dispatched through the audited {@link ToolRegistry} and its (possibly
 * large) result is offloaded to the {@link Scratchpad} before being summarised back into history; a
 * final answer ends the run. The loop is hard-bounded by {@code eoiagent.runtime.maxSteps} and never
 * loops unbounded.
 *
 * <p>Phase 1 is read-only (Flow B). Planner-driven plan→approve→act (Flow C), supervisor/sub-agents
 * (Flow D) and checkpointed investigation (Flow E) are later phases; this orchestrator reaches the
 * model, tools and scratchpad only through their ports, never a concrete library type.
 */
public final class ReActOrchestrator implements Orchestrator {

    private final LlmGateway gateway;
    private final ToolRegistry tools;
    private final Scratchpad scratchpad;
    private final AuditSink audit;
    private final ConfigProvider config;
    private final TraceCollector trace;
    private final MemoryStore memory;               // nullable: no session memory (pre-T-351 behavior)
    private final Retriever retriever;              // nullable: no retrieval in the loop (pre-T-352)
    private final Function<GoalKind, String> systemPrompts; // nullable: no system prompt

    public ReActOrchestrator(LlmGateway gateway, ToolRegistry tools, Scratchpad scratchpad,
                             AuditSink audit, ConfigProvider config) {
        this(builder().gateway(gateway).tools(tools).scratchpad(scratchpad).audit(audit).config(config));
    }

    // Package has no dependency on eoiagent-observability's concrete NoopTraceCollector (runtime
    // depends only on the TraceCollector PORT, in core) — so the no-tracing default lives here.
    private static final TraceCollector NOOP_TRACE = new TraceCollector() {
        @Override
        public Span start(String name, Map<String, Object> attrs) {
            return new Span(name, name, 0L);
        }

        @Override
        public void end(Span span, SpanStatus status) {
            // no-op
        }
    };

    /** T-401: optional {@link TraceCollector} for span-level tracing alongside the mandatory audit trail. */
    public ReActOrchestrator(LlmGateway gateway, ToolRegistry tools, Scratchpad scratchpad,
                             AuditSink audit, ConfigProvider config, TraceCollector trace) {
        this(builder().gateway(gateway).tools(tools).scratchpad(scratchpad).audit(audit).config(config)
                .traceCollector(trace));
    }

    /**
     * T-351: optional session {@link MemoryStore}. When present, the run is seeded with the most
     * recent stored turns (bounded by {@code eoiagent.runtime.memory.maxMessages}) and the USER
     * goal + final ASSISTANT answer are persisted after a successful run. Tool-call turns stay
     * run-scoped working state — they are never written to session memory.
     */
    public ReActOrchestrator(LlmGateway gateway, ToolRegistry tools, Scratchpad scratchpad,
                             AuditSink audit, ConfigProvider config, TraceCollector trace,
                             MemoryStore memory) {
        this(builder().gateway(gateway).tools(tools).scratchpad(scratchpad).audit(audit).config(config)
                .traceCollector(trace).memoryStore(memory));
    }

    private ReActOrchestrator(Builder b) {
        this.gateway = Objects.requireNonNull(b.gateway, "gateway");
        this.tools = Objects.requireNonNull(b.tools, "tools");
        this.scratchpad = Objects.requireNonNull(b.scratchpad, "scratchpad");
        this.audit = Objects.requireNonNull(b.audit, "audit");
        this.config = Objects.requireNonNull(b.config, "config");
        this.trace = b.trace == null ? NOOP_TRACE : b.trace;
        this.memory = b.memory;
        this.retriever = b.retriever;
        this.systemPrompts = b.systemPrompts;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Assembles the loop with its optional collaborators (the required five are gateway, tools,
     * scratchpad, audit, config). T-352 additions: {@link #retriever} makes the loop consult the
     * knowledge corpus per QA turn (RETRIEVAL audited, citations attached to the answer);
     * {@link #systemPrompts} injects the pack's domain system prompt by {@link GoalKind}.
     */
    public static final class Builder {
        private LlmGateway gateway;
        private ToolRegistry tools;
        private Scratchpad scratchpad;
        private AuditSink audit;
        private ConfigProvider config;
        private TraceCollector trace;
        private MemoryStore memory;
        private Retriever retriever;
        private Function<GoalKind, String> systemPrompts;

        public Builder gateway(LlmGateway gateway) {
            this.gateway = gateway;
            return this;
        }

        public Builder tools(ToolRegistry tools) {
            this.tools = tools;
            return this;
        }

        public Builder scratchpad(Scratchpad scratchpad) {
            this.scratchpad = scratchpad;
            return this;
        }

        public Builder audit(AuditSink audit) {
            this.audit = audit;
            return this;
        }

        public Builder config(ConfigProvider config) {
            this.config = config;
            return this;
        }

        public Builder traceCollector(TraceCollector trace) {
            this.trace = trace;
            return this;
        }

        public Builder memoryStore(MemoryStore memory) {
            this.memory = memory;
            return this;
        }

        public Builder retriever(Retriever retriever) {
            this.retriever = retriever;
            return this;
        }

        public Builder systemPrompts(Function<GoalKind, String> systemPrompts) {
            this.systemPrompts = systemPrompts;
            return this;
        }

        public ReActOrchestrator build() {
            return new ReActOrchestrator(this);
        }
    }

    @Override
    public AgentRun run(Goal goal, AgentContext ctx) {
        return run(goal, ctx, null);
    }

    /** T-355: forwards genuine model tokens live; falls back per turn if the backend can't stream. */
    @Override
    public AgentRun run(Goal goal, AgentContext ctx, java.util.function.Consumer<String> onToken) {
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(ctx, "ctx");
        RunId run = new RunId(UUID.randomUUID().toString());
        try {
            return loop(goal, ctx, run, onToken);
        } catch (ConfigException e) {
            throw e; // unrecoverable config faults propagate past the run boundary (spec)
        } catch (RuntimeException e) {
            audit.record(event(ctx, run, AuditKind.ERROR, "run failed: " + e.getMessage()));
            AgentAnswer answer = new AgentAnswer(AnswerKind.ERROR,
                    "The run failed: " + e.getMessage(), null, null, List.of(), run);
            return new AgentRun(run, answer, emptyTasks(), List.of(), 0);
        }
    }

    private AgentRun loop(Goal goal, AgentContext ctx, RunId run,
                          java.util.function.Consumer<String> onToken) {
        int maxSteps = config.get(RuntimeConfigKeys.MAX_STEPS);
        int offloadThreshold = config.get(RuntimeConfigKeys.OFFLOAD_THRESHOLD_BYTES);
        List<ChatMessageRecord> transcript = priorTranscript(ctx);
        ChatMessageRecord userTurn = message(ChatRole.USER, goal.text());

        String system = systemPrompts == null ? null : systemPrompts.apply(goal.kind());
        List<Citation> citations = List.of();
        if (retriever != null && goal.kind() == GoalKind.QA) {
            List<RetrievedChunk> chunks = retriever.retrieve(
                    new RetrievalQuery(goal.text(), config.get(RuntimeConfigKeys.RAG_TOP_K), null));
            audit.record(event(ctx, run, AuditKind.RETRIEVAL,
                    "retrieved " + chunks.size() + " chunks for goal",
                    Map.of("sourceIds", sourceIds(chunks))));
            if (!chunks.isEmpty()) {
                system = (system == null || system.isBlank() ? "" : system + "\n\n") + contextBlock(chunks);
                citations = distinctCitations(chunks);
            }
        }

        List<ChatMessageRecord> history = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            history.add(message(ChatRole.SYSTEM, system));
        }
        history.addAll(window(transcript));
        history.add(userTurn);
        List<ToolSpec> visible = tools.visibleTo(ctx);

        int steps = 0;
        while (steps < maxSteps) {
            steps++;
            Span modelSpan = trace.start("model_call", Map.of("run", run.value(), "step", steps));
            ChatResult result;
            try {
                result = chatOnce(new ChatRequest(history, visible, ChatOptions.defaults()), onToken);
            } catch (RuntimeException e) {
                trace.end(modelSpan, SpanStatus.ERROR);
                throw e;
            }
            trace.end(modelSpan, SpanStatus.OK);
            audit.record(event(ctx, run, AuditKind.MODEL_CALL, "model: " + modelName(result.model())));

            List<ToolCall> calls = result.toolCalls();
            if (calls == null || calls.isEmpty()) {
                audit.record(event(ctx, run, AuditKind.DECISION, "final answer"));
                AgentAnswer answer = new AgentAnswer(AnswerKind.TEXT, result.text(), null, null, citations, run);
                persistTurns(ctx, transcript, userTurn, result.text());
                return new AgentRun(run, answer, emptyTasks(), List.of(), steps);
            }

            // Replay the assistant's tool-call turn into history so the provider sees each TOOL
            // result paired with the request that caused it (T-350, ToolCallMeta convention).
            history.add(new ChatMessageRecord(ChatRole.ASSISTANT,
                    result.text() == null ? "" : result.text(), Instant.now(), ToolCallMeta.encode(calls)));

            for (ToolCall call : calls) {
                ToolCall scoped = new ToolCall(call.callId(), call.toolName(), call.arguments(), run);
                Span toolSpan = trace.start("tool_call", Map.of("run", run.value(), "tool", call.toolName()));
                ToolResult toolResult;
                try {
                    toolResult = tools.dispatch(scoped, ctx); // audited TOOL_CALL inside dispatch
                } catch (RuntimeException e) {
                    trace.end(toolSpan, SpanStatus.ERROR);
                    throw e;
                }
                trace.end(toolSpan, toolResult.ok() ? SpanStatus.OK : SpanStatus.ERROR);

                // T-353: a successful dispatch of the reserved navigation tool ends the run with a
                // typed NAVIGATION answer — the HOST performs the routing, never the agent. A failed
                // one (unknown page, missing param) flows back as a normal tool observation so the
                // model can correct itself.
                if (toolResult.ok() && NavigationIntent.TOOL_NAME.equals(scoped.toolName())) {
                    NavigationIntent intent = navigationIntent(toolResult);
                    audit.record(event(ctx, run, AuditKind.DECISION,
                            "navigation to " + intent.targetPageId()));
                    String text = intent.rationale() == null || intent.rationale().isBlank()
                            ? "Navigate to " + intent.targetPageId() : intent.rationale();
                    persistTurns(ctx, transcript, userTurn, text);
                    AgentAnswer answer = new AgentAnswer(AnswerKind.NAVIGATION, text, null, intent,
                            citations, run);
                    return new AgentRun(run, answer, emptyTasks(), List.of(), steps);
                }

                history.add(new ChatMessageRecord(ChatRole.TOOL,
                        observe(toolResult, run, offloadThreshold), Instant.now(), ToolCallMeta.forResult(scoped)));
            }
        }

        // maxSteps exhausted — conclude gracefully, never throw or loop unbounded (spec §2.3).
        audit.record(event(ctx, run, AuditKind.DECISION, "max steps (" + maxSteps + ") reached"));
        AgentAnswer answer = new AgentAnswer(AnswerKind.CLARIFICATION,
                "I couldn't complete this within " + maxSteps + " steps. Could you narrow the request?",
                null, null, List.of(), run);
        persistTurns(ctx, transcript, userTurn, answer.text());
        return new AgentRun(run, answer, emptyTasks(), List.of(), steps);
    }

    /** Stored transcript for this session, or empty when no memory is wired. */
    private List<ChatMessageRecord> priorTranscript(AgentContext ctx) {
        if (memory == null) {
            return List.of();
        }
        List<ChatMessageRecord> stored = memory.get(ctx.session());
        return stored == null ? List.of() : stored;
    }

    /** The most recent turns that fit the configured context window (the store keeps everything). */
    private List<ChatMessageRecord> window(List<ChatMessageRecord> transcript) {
        int max = config.get(RuntimeConfigKeys.MEMORY_MAX_MESSAGES);
        if (max <= 0 || transcript.size() <= max) {
            return transcript;
        }
        return transcript.subList(transcript.size() - max, transcript.size());
    }

    /**
     * Appends this run's USER goal and ASSISTANT answer to the session transcript. Only durable
     * conversation turns are stored — tool-call turns are working state and would bloat/poison
     * later context. ERROR runs never reach here (nothing worth replaying).
     */
    private void persistTurns(AgentContext ctx, List<ChatMessageRecord> transcript,
                              ChatMessageRecord userTurn, String answerText) {
        if (memory == null) {
            return;
        }
        List<ChatMessageRecord> updated = new ArrayList<>(transcript);
        updated.add(userTurn);
        updated.add(message(ChatRole.ASSISTANT, answerText));
        memory.put(ctx.session(), updated);
    }

    /** Inlines a small tool result, or offloads a large one to the scratchpad and returns a synopsis. */
    private String observe(ToolResult result, RunId run, int offloadThreshold) {
        String value = result.ok() ? String.valueOf(result.value()) : "tool error: " + result.error();
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (result.ok() && bytes > offloadThreshold) {
            String handle = scratchpad.write(run.value() + "/tool/" + UUID.randomUUID(), value);
            return "[tool result stored at " + handle + " — " + bytes + " bytes; read it to inspect]";
        }
        return value;
    }

    private static ChatMessageRecord message(ChatRole role, String text) {
        return new ChatMessageRecord(role, text == null ? "" : text, Instant.now(), Map.of());
    }

    private static String modelName(ModelInfo info) {
        return info == null ? "unknown" : info.provider() + "/" + info.modelId();
    }

    private static TaskList emptyTasks() {
        return new TaskList(List.<com.eoiagent.core.Task>of()); // ReAct (Flow B) has no planned task list
    }

    /**
     * One model turn. Without a listener this is the plain blocking call. With one, the turn runs
     * through {@code chatStream}, forwarding text tokens live (tool-call turns usually stream no
     * text); a backend that cannot stream ({@code ModelUnavailableException} before any token)
     * falls back to the blocking call and emits the turn's final text as one chunk — degraded
     * granularity, never a failed run.
     */
    private ChatResult chatOnce(ChatRequest request, java.util.function.Consumer<String> onToken) {
        if (onToken == null) {
            return gateway.chat(request);
        }
        java.util.concurrent.CompletableFuture<ChatResult> done = new java.util.concurrent.CompletableFuture<>();
        try {
            gateway.chatStream(request, new com.eoiagent.model.TokenSink() {
                @Override
                public void onToken(String token) {
                    onToken.accept(token);
                }

                @Override
                public void onComplete(ChatResult result) {
                    done.complete(result);
                }

                @Override
                public void onError(Throwable error) {
                    done.completeExceptionally(error);
                }
            });
        } catch (com.eoiagent.core.ModelUnavailableException e) {
            ChatResult result = gateway.chat(request); // backend can't stream — degrade gracefully
            if (result.text() != null && !result.text().isBlank()
                    && (result.toolCalls() == null || result.toolCalls().isEmpty())) {
                onToken.accept(result.text());
            }
            return result;
        }
        try {
            return done.join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    /** Builds the typed intent from the navigation tool's canonical result map (see NavigationIntent). */
    @SuppressWarnings("unchecked")
    private static NavigationIntent navigationIntent(ToolResult result) {
        if (!(result.value() instanceof Map<?, ?> value)) {
            throw new IllegalStateException("navigation tool returned no intent map: " + result.value());
        }
        Object params = value.get("parameters");
        return new NavigationIntent(
                String.valueOf(value.get("targetPageId")),
                params instanceof Map ? Map.copyOf((Map<String, String>) params) : Map.of(),
                value.get("rationale") == null ? null : String.valueOf(value.get("rationale")));
    }

    /** The retrieved context block appended to the system message — each chunk tagged with its source. */
    private static String contextBlock(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder(256)
                .append("Use the following retrieved context to answer. Cite only what it supports.");
        for (RetrievedChunk chunk : chunks) {
            Citation c = chunk.citation();
            sb.append("\n\n[source: ").append(c == null ? "unknown" : c.sourceId()).append("] ")
                    .append(chunk.text());
        }
        return sb.toString();
    }

    private static List<String> sourceIds(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::citation)
                .filter(Objects::nonNull)
                .map(Citation::sourceId)
                .distinct()
                .toList();
    }

    private static List<Citation> distinctCitations(List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(RetrievedChunk::citation)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary) {
        return event(ctx, run, kind, summary, Map.of());
    }

    private static AuditEvent event(AgentContext ctx, RunId run, AuditKind kind, String summary,
                                    Map<String, Object> details) {
        return new AuditEvent(Instant.now(), ctx.app(), run, ctx.session(), ctx.user(),
                kind, summary, details);
    }
}
