package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentSession;
import com.eoiagent.core.SessionId;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.InMemoryMemoryStore;
import com.eoiagent.memory.MemoryStore;
import com.eoiagent.model.ChatRequest;
import com.eoiagent.model.ChatResult;
import com.eoiagent.model.EmbeddingRequest;
import com.eoiagent.model.EmbeddingResult;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.ModelInfo;
import com.eoiagent.model.ModelRole;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.model.TokenSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.time.Instant;
import java.util.List;

/**
 * T-351: multi-turn session memory. Clears up the most common misconception about LLMs:
 * <strong>the model does not remember your last question — models are stateless.</strong> The
 * platform makes conversations work by storing each USER/ASSISTANT turn in a {@code MemoryStore}
 * and replaying the recent transcript into every model call. This demo prints exactly what the
 * model sees on each turn so the mechanism is visible, and shows that tool chatter never enters
 * the durable transcript.
 */
public final class MultiTurnMemoryDemo {

    private MultiTurnMemoryDemo() {
    }

    /** Delegating store that remembers the session id, so the demo can print the transcript. */
    private static final class ObservableMemoryStore implements MemoryStore {
        private final InMemoryMemoryStore delegate = new InMemoryMemoryStore();
        volatile SessionId lastSession;

        @Override
        public void put(SessionId id, List<ChatMessageRecord> messages) {
            lastSession = id;
            delegate.put(id, messages);
        }

        @Override
        public List<ChatMessageRecord> get(SessionId id) {
            return delegate.get(id);
        }

        @Override
        public void delete(SessionId id) {
            delegate.delete(id);
        }
    }

    /** Wraps the real gateway and prints every message the platform sends to the model. */
    private static final class PrintingGateway implements LlmGateway {
        private final LlmGateway delegate;

        PrintingGateway(LlmGateway delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            System.out.println("  --- context the model sees for this call ---");
            for (ChatMessageRecord m : request.messages()) {
                System.out.println("    " + m.role() + ": " + m.text());
            }
            return delegate.chat(request);
        }

        @Override
        public void chatStream(ChatRequest request, TokenSink sink) {
            delegate.chatStream(request, sink);
        }

        @Override
        public EmbeddingResult embed(EmbeddingRequest request) {
            return delegate.embed(request);
        }

        @Override
        public ModelInfo activeChatModel() {
            return delegate.activeChatModel();
        }

        @Override
        public boolean isAvailable(ModelRole role) {
            return delegate.isAvailable(role);
        }
    }

    public static void main(String[] args) {
        DemoSupport.header("Multi-turn memory: the model is stateless, the platform is not");

        System.out.println("  MISCONCEPTION: \"the model remembers my last question\"");
        System.out.println("  REALITY:       every model call starts blank; the platform replays the");
        System.out.println("                 stored transcript (bounded by eoiagent.runtime.memory.maxMessages)");
        System.out.println();

        StubLlmGateway scripted = StubLlmGateway.builder()
                .replyText("orders_daily is the nightly revenue ingestion pipeline.")
                .replyText("It last failed on 2026-06-20 during the 02:00 UTC load.")
                .defaultReplyText("See the Acme docs.")
                .build();
        ObservableMemoryStore memory = new ObservableMemoryStore();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(new PrintingGateway(scripted))
                .memoryStore(memory)
                .start()) {

            AgentSession session = platform.agentService().open(DemoSupport.session(Role.ANALYST));

            System.out.println("  Q1: What is orders_daily?");
            AgentAnswer a1 = session.ask(new UserMessage("What is orders_daily?", null, Instant.now()));
            System.out.println("  A1: " + a1.text());
            System.out.println();

            // "it" only resolves because the platform replays turn 1 — watch the printed context.
            System.out.println("  Q2: When did it last fail?   (\"it\" needs the previous turn!)");
            AgentAnswer a2 = session.ask(new UserMessage("When did it last fail?", null, Instant.now()));
            System.out.println("  A2: " + a2.text());
            System.out.println();

            System.out.println("  Durable transcript in the MemoryStore (tool chatter is never stored):");
            for (ChatMessageRecord m : memory.get(memory.lastSession)) {
                System.out.println("    " + m.role() + ": " + m.text());
            }
            session.close();
        }

        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("memory is explicit platform behavior, not a model capability");
        DemoSupport.bullet("the store keeps the whole transcript; only a bounded window is replayed");
        DemoSupport.bullet("swap InMemoryMemoryStore for PostgresMemoryStore to survive restarts");
    }
}
