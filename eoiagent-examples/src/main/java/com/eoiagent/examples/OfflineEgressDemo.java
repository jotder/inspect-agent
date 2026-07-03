package com.eoiagent.examples;

import com.eoiagent.core.PolicyViolation;
import com.eoiagent.safety.EgressGuard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * T-403: the offline zero-egress proof, live. Clears up a security misconception:
 * <strong>"offline mode just means we didn't configure a cloud API key." It does not.</strong>
 * The config layer already refuses hosted providers in OFFLINE at construction, and this demo
 * shows the second, runtime layer: an in-JVM {@link EgressGuard} that records and denies any
 * non-loopback connection attempt — so a misconfigured or buggy adapter fails loudly instead of
 * quietly phoning home. It also shows the honest boundary: an in-JVM guard is a tripwire, not a
 * sandbox; the hard wall for air-gapped installs is the OS firewall (see docs/security/).
 */
public final class OfflineEgressDemo {

    private OfflineEgressDemo() {
    }

    public static void main(String[] args) {
        DemoSupport.header("Offline zero egress: deny + record network attempts in the JVM (T-403)");

        System.out.println("  MISCONCEPTION: \"offline = we just don't configure a cloud key\"");
        System.out.println("  REALITY:       OFFLINE is enforced in layers - config refuses hosted");
        System.out.println("                 providers, and EgressGuard denies + records any attempt");
        System.out.println("                 that still tries to leave the machine");
        System.out.println();

        try (EgressGuard guard = EgressGuard.install()) {
            System.out.println("  EgressGuard installed (denies non-loopback, allows localhost).");
            System.out.println();

            System.out.println("  1) A 'misconfigured adapter' tries a hosted endpoint:");
            URI hosted = URI.create("https://api.example-llm-vendor.invalid/v1/chat");
            System.out.println("     -> GET " + hosted);
            try {
                HttpClient.newHttpClient().send(HttpRequest.newBuilder(hosted).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                System.out.println("     !! request went through - this should never print");
            } catch (PolicyViolation | java.io.IOException | InterruptedException e) {
                System.out.println("     DENIED before any packet left the JVM:");
                System.out.println("       " + rootCause(e).getMessage());
            }
            System.out.println();

            System.out.println("  2) The audit surface - every denied attempt is recorded:");
            guard.attempts().forEach(uri -> DemoSupport.bullet("egress attempt: " + uri));
            System.out.println();

            System.out.println("  3) Loopback stays allowed: a local model server (Ollama on");
            System.out.println("     localhost) is legitimate in OFFLINE - the guard classifies");
            System.out.println("     'localhost'/127.x/::1 locally and never resolves DNS (a DNS");
            System.out.println("     lookup would itself be egress).");
        }
        System.out.println();
        System.out.println("  Takeaways:");
        DemoSupport.bullet("OFFLINE is enforced at runtime, not just by leaving config blank");
        DemoSupport.bullet("denied attempts are recorded - a tripwire plus an audit surface");
        DemoSupport.bullet("in-JVM guard != sandbox: the hard wall is OS-level denial (docs/security/)");
        DemoSupport.bullet("the whole platform lifecycle runs under this guard in OfflineZeroEgressTest");
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur;
    }
}
