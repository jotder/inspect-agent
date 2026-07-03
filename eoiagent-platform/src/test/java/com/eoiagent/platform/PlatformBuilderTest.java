package com.eoiagent.platform;

import com.eoiagent.app.PackMetadata;
import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.AnswerKind;
import com.eoiagent.core.AppId;
import com.eoiagent.core.AuditEvent;
import com.eoiagent.core.Capability;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.PolicyViolation;
import com.eoiagent.core.Role;
import com.eoiagent.core.ToolCall;
import com.eoiagent.core.ToolResult;
import com.eoiagent.core.ToolSpec;
import com.eoiagent.core.SessionId;
import com.eoiagent.core.UserId;
import com.eoiagent.core.UserMessage;
import com.eoiagent.host.AgentService;
import com.eoiagent.host.AgentSession;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.memory.ChatMessageRecord;
import com.eoiagent.memory.ChatRole;
import com.eoiagent.memory.InMemoryMemoryStore;
import com.eoiagent.memory.MemoryStore;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.tool.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-010 PlatformBuilder: AC1 usable platform, AppId stamping, AC2/AC3 at start(), AC4 close(). */
class PlatformBuilderTest {

    private static UserMessage ask(String text) {
        return new UserMessage(text, null, Instant.now());
    }

    private static SessionRequest offlineSession() {
        return new SessionRequest(new UserId("u-1"), Role.ANALYST, DeploymentProfile.OFFLINE, null, Map.of());
    }

    @Test
    void startReturnsUsablePlatformThatAnswersOffline() { // AC1
        StubLlmGateway gateway = StubLlmGateway.builder()
                .defaultReplyText("The ingestion pipeline runs nightly.")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new StubApplicationPack())
                .llmGateway(gateway)
                .start()) {

            assertThat(platform.pack().appId()).isEqualTo(new AppId("stub-app"));

            AgentService service = platform.agentService();
            AgentSession session = service.open(offlineSession());
            AgentAnswer answer = session.ask(ask("How does ingestion work?"));

            assertThat(answer.kind()).isEqualTo(AnswerKind.TEXT);
            assertThat(answer.text()).isEqualTo("The ingestion pipeline runs nightly.");
            session.close();
        }
    }

    @Test
    void appIdFromPackIsStampedIntoEveryAuditEvent() { // application-pack AC2
        RecordingAuditSink sink = new RecordingAuditSink();
        StubLlmGateway gateway = StubLlmGateway.builder().defaultReplyText("ok").build();
        AppId expected = new AppId("billing-agent");

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new StubApplicationPack()
                        .withMetadata(new PackMetadata(expected, "Billing", "2.0.0")))
                .llmGateway(gateway)
                .auditSink(sink)
                .start()) {

            AgentSession session = platform.agentService().open(offlineSession());
            session.ask(ask("anything"));
            session.close();
        }

        assertThat(sink.events).isNotEmpty();
        assertThat(sink.events).allSatisfy(e -> assertThat(e.app()).isEqualTo(expected));
    }

    @Test
    void startFailsWithConfigExceptionWhenAProviderIsMissing() { // AC3 surfaces through start()
        assertThatThrownBy(() -> new PlatformBuilder()
                .pack(new StubApplicationPack().withPromptProfile(null))
                .llmGateway(StubLlmGateway.builder().defaultReplyText("x").build())
                .start())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("promptProfile");
    }

    @Test
    void startFailsWithPolicyViolationForOfflineHostedFallback() { // AC2 surfaces through start()
        assertThatThrownBy(() -> new PlatformBuilder()
                .pack(new StubApplicationPack()
                        .withModelProfile(StubApplicationPack.offlineModelProfile(true)))
                .llmGateway(StubLlmGateway.builder().defaultReplyText("x").build())
                .start())
                .isInstanceOf(PolicyViolation.class);
    }

    @Test
    void startWithoutAPackFails() {
        assertThatThrownBy(() -> new PlatformBuilder().start())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("pack");
    }

    @Test
    void closeClosesAutoCloseableAdaptersOwnedByThePlatform() { // AC4
        CloseableTool tool = new CloseableTool();
        AgentPlatform platform = new PlatformBuilder()
                .pack(new StubApplicationPack().withTools(List.of(tool)))
                .llmGateway(StubLlmGateway.builder().defaultReplyText("x").build())
                .start();

        assertThat(tool.closed).isFalse();
        platform.close();
        assertThat(tool.closed).as("platform.close() closes AutoCloseable tools").isTrue();

        platform.close(); // idempotent
        assertThat(tool.closed).isTrue();
    }

    @Test
    void unsupportedChatProviderWithoutAGatewayOverrideFailsAtStart() {
        // No llmGateway override → the model-from-profile factory rejects the "stub" provider.
        assertThatThrownBy(() -> new PlatformBuilder().pack(new StubApplicationPack()).start())
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void sessionTranscriptPersistsAcrossAsksThroughTheAssembledPlatform() { // T-351
        RecordingMemoryStore store = new RecordingMemoryStore();
        StubLlmGateway gateway = StubLlmGateway.builder()
                .replyText("orders_daily is the nightly revenue pipeline")
                .replyText("it last failed on 2026-06-20")
                .build();

        try (AgentPlatform platform = new PlatformBuilder()
                .pack(new StubApplicationPack())
                .llmGateway(gateway)
                .memoryStore(store)
                .start()) {

            AgentSession session = platform.agentService().open(offlineSession());
            session.ask(ask("what is orders_daily?"));
            session.ask(ask("when did IT last fail?"));
            session.close();

            assertThat(store.lastSession).isNotNull();
            List<ChatMessageRecord> transcript = store.get(store.lastSession);
            assertThat(transcript).extracting(ChatMessageRecord::role).containsExactly(
                    ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT);
            assertThat(transcript.get(2).text()).isEqualTo("when did IT last fail?");
            assertThat(transcript.get(3).text()).isEqualTo("it last failed on 2026-06-20");
        }
    }

    // --- test doubles -----------------------------------------------------------------------------

    /** Delegating store that remembers which session was written, so the test can read it back. */
    private static final class RecordingMemoryStore implements MemoryStore {
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

    private static final class RecordingAuditSink implements AuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }
    }

    /** A read-only tool that also holds a resource, so the platform must close it on shutdown. */
    private static final class CloseableTool implements Tool, AutoCloseable {
        private volatile boolean closed;

        @Override
        public ToolSpec spec() {
            return new ToolSpec("read_docs", "reads docs", "{}", false, Role.USER, Capability.READ_DOCS);
        }

        @Override
        public ToolResult invoke(ToolCall call) {
            return new ToolResult(true, "doc", null, Map.of());
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
