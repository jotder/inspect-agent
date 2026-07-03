package com.eoiagent.safety;

import com.eoiagent.core.PolicyViolation;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-JVM network-egress guard for the OFFLINE profile: while installed, any connection attempt to
 * a non-loopback destination through the JDK's default {@link ProxySelector} is <em>recorded</em>
 * and <em>denied</em> (a {@link PolicyViolation} aborts the connection before any packet leaves the
 * JVM). Loopback destinations ({@code localhost}, {@code 127.0.0.0/8}, {@code ::1}) stay allowed —
 * a local model server (Ollama / llama.cpp on localhost) is legitimate in OFFLINE.
 *
 * <p><strong>What this covers, and what it cannot.</strong> {@code SecurityManager} was removed in
 * JDK 25 (JEP 486), so there is no JVM-wide connect hook anymore. The default {@code ProxySelector}
 * is consulted by {@link java.net.http.HttpClient} (every model/tool adapter in this codebase) and
 * by {@code URLConnection}, which makes it the strongest remaining in-JVM seam. It is <em>not</em>
 * consulted by raw {@code SocketChannel}/{@code Socket} opens — a malicious or buggy dependency
 * could still bypass it. ({@code Socket.setSocketImplFactory} would not close that gap either: it
 * is once-per-JVM, deprecated for removal, and invisible to NIO.) The guard is therefore a strong
 * regression tripwire and audit surface, not a sandbox; the hard boundary for air-gapped installs
 * is OS-level denial — see {@code docs/security/} for the documented guidance.
 *
 * <p>Hostname handling never resolves DNS (a DNS lookup is itself egress): {@code localhost} and
 * literal IPs are classified locally; every other hostname is conservatively treated as remote.
 *
 * <p>Install/close manage the process-global default selector, so installs must not overlap;
 * {@link #install()} throws if a guard is already active. {@code close()} restores the previous
 * selector and is idempotent. Pure JDK — no third-party library, no network.
 */
public final class EgressGuard implements AutoCloseable {

    private static final Object LOCK = new Object();
    private static EgressGuard active; // guards the process-global ProxySelector default

    private final ProxySelector previous;
    private final List<URI> attempts = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private EgressGuard(ProxySelector previous) {
        this.previous = previous;
    }

    /** Installs the guard as the JVM's default {@link ProxySelector}. One at a time. */
    public static EgressGuard install() {
        synchronized (LOCK) {
            if (active != null) {
                throw new IllegalStateException("an EgressGuard is already installed");
            }
            EgressGuard guard = new EgressGuard(ProxySelector.getDefault());
            ProxySelector.setDefault(guard.new Denying());
            active = guard;
            return guard;
        }
    }

    /** Non-loopback connection attempts recorded (and denied) since install, in order. */
    public List<URI> attempts() {
        return List.copyOf(attempts);
    }

    /** Restores the previous default selector. Idempotent. */
    @Override
    public void close() {
        synchronized (LOCK) {
            if (closed) {
                return;
            }
            closed = true;
            if (active == this) {
                ProxySelector.setDefault(previous);
                active = null;
            }
        }
    }

    /** True for {@code localhost} and literal loopback IPs; never resolves DNS. */
    static boolean isLoopback(String host) {
        if (host == null || host.isEmpty()) {
            return false; // no host → cannot prove it is local → deny
        }
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        if (!isIpLiteral(host)) {
            return false; // a hostname would need DNS to classify — DNS is egress; deny
        }
        try {
            return InetAddress.getByName(stripBrackets(host)).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** Literal IPv4 (digits/dots) or IPv6 (hex/colons, optionally bracketed) — no DNS needed. */
    private static boolean isIpLiteral(String host) {
        String h = stripBrackets(host);
        if (h.isEmpty()) {
            return false;
        }
        boolean colon = h.indexOf(':') >= 0;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.'
                    || (colon && (c == ':' || c == '%'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
            if (!ok) {
                return false;
            }
        }
        return colon || h.chars().anyMatch(c -> c == '.');
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    /** The installed selector: loopback → direct; anything else → record + deny. */
    private final class Denying extends ProxySelector {
        @Override
        public List<Proxy> select(URI uri) {
            if (uri != null && isLoopback(uri.getHost())) {
                return List.of(Proxy.NO_PROXY);
            }
            attempts.add(uri);
            throw new PolicyViolation("network egress denied by EgressGuard: " + uri);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // nothing to do — the deny already happened in select()
        }
    }
}
