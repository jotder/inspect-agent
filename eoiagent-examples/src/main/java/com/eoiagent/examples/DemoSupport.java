package com.eoiagent.examples;

import com.eoiagent.app.reference.ReferenceApplicationPack;
import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.core.Role;
import com.eoiagent.core.UserId;
import com.eoiagent.host.SessionRequest;
import com.eoiagent.model.LlmGateway;
import com.eoiagent.model.OpenAiCompatibleChatAdapter;
import com.eoiagent.model.StubLlmGateway;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.platform.AgentPlatform;
import com.eoiagent.platform.PlatformBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.Map;

/**
 * Shared helpers for the demos: choosing a gateway (offline stub vs. a reachable local Ollama),
 * booting the platform from the reference pack, opening sessions, and tidy console formatting.
 *
 * <p>By default the demos run offline against the deterministic {@link StubLlmGateway}. If a local
 * Ollama server is reachable at {@value #OLLAMA_HOST}:{@value #OLLAMA_PORT} it is used instead — unless
 * {@code -Deoiagent.demo.offline=true} forces offline (handy for CI/tests).
 */
final class DemoSupport {

    static final String OLLAMA_HOST = "localhost";
    static final int OLLAMA_PORT = 11434;
    static final String OLLAMA_BASE_URL = "http://" + OLLAMA_HOST + ":" + OLLAMA_PORT + "/v1";
    static final String OLLAMA_MODEL = "qwen2.5:14b-instruct";

    private DemoSupport() {
    }

    /** True if a local Ollama server answers a quick socket probe (and offline is not forced). */
    static boolean ollamaReachable() {
        if (Boolean.getBoolean("eoiagent.demo.offline")) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(OLLAMA_HOST, OLLAMA_PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** A live local Ollama gateway if one is reachable, otherwise the supplied offline stub. */
    static LlmGateway chooseGateway(StubLlmGateway offlineFallback) {
        if (ollamaReachable()) {
            System.out.println("[llm] local Ollama detected at " + OLLAMA_BASE_URL + " - using " + OLLAMA_MODEL);
            return new OpenAiCompatibleChatAdapter(OLLAMA_BASE_URL, OLLAMA_MODEL, null);
        }
        System.out.println("[llm] no Ollama at " + OLLAMA_HOST + ":" + OLLAMA_PORT
                + " - using the deterministic offline StubLlmGateway");
        return offlineFallback;
    }

    /** Boots an {@link AgentPlatform} from the reference pack with the given gateway and optional audit sink. */
    static AgentPlatform boot(LlmGateway gateway, AuditSink audit) {
        PlatformBuilder builder = new PlatformBuilder()
                .pack(new ReferenceApplicationPack())
                .llmGateway(gateway);
        if (audit != null) {
            builder.auditSink(audit);
        }
        return builder.start();
    }

    /** A session request for the OFFLINE reference pack under the given role. */
    static SessionRequest session(Role role) {
        return new SessionRequest(new UserId("demo-" + role.name().toLowerCase(Locale.ROOT)),
                role, DeploymentProfile.OFFLINE, null, Map.of());
    }

    static void header(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    static void kv(String key, Object value) {
        System.out.printf("  %-24s %s%n", key, value);
    }

    static void bullet(Object value) {
        System.out.println("  - " + value);
    }
}
