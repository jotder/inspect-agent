package com.eoiagent.safety;

import com.eoiagent.core.PolicyViolation;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-JVM network-deny harness itself (T-403). No test here touches the network: remote
 * attempts are aborted in ProxySelector.select() before any packet leaves the JVM, and the
 * loopback case talks to an in-process JDK HttpServer on 127.0.0.1.
 */
class EgressGuardTest {

    private static HttpClient client() {
        return HttpClient.newHttpClient(); // uses ProxySelector.getDefault()
    }

    @Test
    void remoteHttpAttemptIsDeniedAndRecorded() {
        try (EgressGuard guard = EgressGuard.install()) {
            URI remote = URI.create("http://203.0.113.7/model"); // TEST-NET-3, never reachable
            assertThatThrownBy(() -> client().send(
                    HttpRequest.newBuilder(remote).GET().build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(PolicyViolation.class)
                    .hasMessageContaining("egress denied");
            assertThat(guard.attempts()).containsExactly(remote);
        }
    }

    @Test
    void hostnameIsDeniedWithoutDnsResolution() {
        try (EgressGuard guard = EgressGuard.install()) {
            URI remote = URI.create("https://api.example.invalid/v1/chat");
            assertThatThrownBy(() -> client().send(
                    HttpRequest.newBuilder(remote).GET().build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOf(PolicyViolation.class);
            assertThat(guard.attempts()).containsExactly(remote);
        }
    }

    @Test
    void loopbackStaysAllowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try (EgressGuard guard = EgressGuard.install()) {
            URI local = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/ping");
            HttpResponse<String> response = client().send(
                    HttpRequest.newBuilder(local).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("pong");
            assertThat(guard.attempts()).isEmpty(); // loopback is not an egress attempt
        } finally {
            server.stop(0);
        }
    }

    @Test
    void closeRestoresPreviousSelectorAndIsIdempotent() {
        ProxySelector before = ProxySelector.getDefault();
        EgressGuard guard = EgressGuard.install();
        assertThat(ProxySelector.getDefault()).isNotSameAs(before);
        guard.close();
        guard.close();
        assertThat(ProxySelector.getDefault()).isSameAs(before);
    }

    @Test
    void overlappingInstallIsRejected() {
        try (EgressGuard ignored = EgressGuard.install()) {
            assertThatThrownBy(EgressGuard::install)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void loopbackClassificationNeverResolvesDns() {
        assertThat(EgressGuard.isLoopback("localhost")).isTrue();
        assertThat(EgressGuard.isLoopback("127.0.0.1")).isTrue();
        assertThat(EgressGuard.isLoopback("127.42.0.1")).isTrue();
        assertThat(EgressGuard.isLoopback("::1")).isTrue();
        assertThat(EgressGuard.isLoopback("[::1]")).isTrue();
        assertThat(EgressGuard.isLoopback("10.0.0.1")).isFalse();
        assertThat(EgressGuard.isLoopback("203.0.113.7")).isFalse();
        assertThat(EgressGuard.isLoopback("example.com")).isFalse();   // hostname → no DNS → deny
        assertThat(EgressGuard.isLoopback("local-model.lan")).isFalse();
        assertThat(EgressGuard.isLoopback(null)).isFalse();
        assertThat(EgressGuard.isLoopback("")).isFalse();
    }
}
