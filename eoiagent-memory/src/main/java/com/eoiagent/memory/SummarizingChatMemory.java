package com.eoiagent.memory;

import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.AuditKind;
import com.eoiagent.core.ModelUnavailableException;
import com.eoiagent.core.RunId;
import com.eoiagent.model.ChatOptions;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.observability.AuditSink;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Short-term memory that <strong>condenses</strong> rather than drops old turns (memory spec §3,
 * AC7). It keeps the most recent {@code maxMessages} raw turns; when a turn is evicted past that
 * window it is folded into a single running-summary {@link SystemMessage} via the {@link LlmGateway}
 * — so long-run context survives in summarized form. Persistence goes through a {@link MemoryStore}
 * (the running summary is stored as a marked leading system message and rehydrated on construction).
 *
 * <p>Reaches the model only through the {@code LlmGateway} port (no direct model artifact). The
 * summary call is audited {@code MODEL_CALL}. If the model is unavailable the adapter must not throw
 * or corrupt memory: it falls back to plain window eviction (the turn is dropped, the summary is left
 * unchanged) and records an {@code ERROR} audit event.
 */
public final class SummarizingChatMemory implements ChatMemory {

    /** Marker so the persisted running summary can be told apart from an ordinary system message. */
    static final String SUMMARY_PREFIX = "[running summary] ";

    /** Summarization is memory maintenance, not scoped to a single run — a sentinel run id. */
    private static final RunId MEMORY_RUN = new RunId("memory");

    private final AgentContext ctx;
    private final int maxMessages;
    private final LlmGateway gateway;
    private final MemoryStore store;
    private final AuditSink audit;

    private String summary; // running summary, or null until the first eviction
    private final Deque<ChatMessage> recent = new ArrayDeque<>();

    public SummarizingChatMemory(AgentContext ctx, int maxMessages, LlmGateway gateway,
                                 MemoryStore store, AuditSink audit) {
        this.ctx = Objects.requireNonNull(ctx, "ctx");
        this.maxMessages = maxMessages;
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        load();
    }

    @Override
    public Object id() {
        return ctx.session().value();
    }

    @Override
    public void add(ChatMessage message) {
        Objects.requireNonNull(message, "message");
        recent.addLast(message);
        while (recent.size() > maxMessages) {
            fold(recent.removeFirst());
        }
        persist();
    }

    @Override
    public List<ChatMessage> messages() {
        List<ChatMessage> out = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            out.add(SystemMessage.from(SUMMARY_PREFIX + summary));
        }
        out.addAll(recent);
        return out;
    }

    @Override
    public void clear() {
        summary = null;
        recent.clear();
        persist();
    }

    /** Fold one evicted turn into the running summary; on model failure, drop it (window eviction). */
    private void fold(ChatMessage evicted) {
        try {
            this.summary = summarize(summary, evicted);
            audit.record(event(AuditKind.MODEL_CALL, "summarized an evicted turn into the running summary"));
        } catch (ModelUnavailableException e) {
            // Fail-safe (spec): the turn is already removed from the window; leave the summary intact.
            audit.record(event(AuditKind.ERROR, "summary model unavailable; fell back to window eviction"));
        }
    }

    private String summarize(String currentSummary, ChatMessage evicted) {
        ChatMessageRecord rec = ChatMessageMapper.toRecord(evicted);
        String prompt = "You maintain a concise running summary of a conversation.\n"
                + "Current summary: " + (currentSummary == null ? "(none)" : currentSummary) + "\n"
                + "Fold in this " + rec.role() + " message and reply with the updated summary only:\n"
                + rec.text();
        ChatMessageRecord ask = new ChatMessageRecord(ChatRole.USER, prompt, Instant.now(), Map.of());
        ChatResult result = gateway.chat(new ChatRequest(List.of(ask), List.of(), ChatOptions.defaults()));
        String text = result.text();
        if (text == null || text.isBlank()) {
            // Model answered emptily — keep prior context rather than blanking the summary.
            return currentSummary == null ? rec.text() : currentSummary;
        }
        return text.strip();
    }

    private void load() {
        List<ChatMessageRecord> stored = store.get(ctx.session());
        if (stored == null || stored.isEmpty()) {
            return;
        }
        int start = 0;
        ChatMessageRecord first = stored.get(0);
        if (first.role() == ChatRole.SYSTEM && first.text() != null && first.text().startsWith(SUMMARY_PREFIX)) {
            this.summary = first.text().substring(SUMMARY_PREFIX.length());
            start = 1;
        }
        for (int i = start; i < stored.size(); i++) {
            recent.addLast(ChatMessageMapper.toLc4j(stored.get(i)));
        }
    }

    private void persist() {
        store.put(ctx.session(), ChatMessageMapper.toRecords(messages()));
    }

    private AuditEvent event(AuditKind kind, String summaryText) {
        return new AuditEvent(Instant.now(), ctx.app(), MEMORY_RUN, ctx.session(), ctx.user(),
                kind, summaryText, Map.<String, Object>of());
    }
}
