package com.eoiagent.safety;

import com.eoiagent.core.ApprovalDecision;
import com.eoiagent.core.ApprovalRequest;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.DryRunResult;
import com.eoiagent.core.RunId;
import com.eoiagent.core.ToolCall;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The default {@link ApprovalGate}: collects a human decision through a host-supplied
 * {@link ApprovalHandler}, bounded by a timeout, and previews mutating calls through registered
 * {@link DryRunProvider}s. Pure-Java — no third-party library, no network.
 *
 * <p>It only <em>collects</em> the decision; it never performs the mutation. The caller (the
 * {@code ToolRegistry}) records the {@code APPROVAL}/{@code MUTATION} audit events around it and is
 * responsible for the "no mutation without a preceding {@code APPROVED}" ordering invariant.
 *
 * <p>Fail-closed (conventions §5): with no handler (headless/OFFLINE) {@link #request} returns
 * {@link ApprovalDecision#DENIED}; on timeout it returns the configured {@code onTimeout}
 * disposition; a handler that throws is treated as {@code DENIED}. Decisions are cached per
 * {@code (run, call)} so retrying a request never re-prompts the human.
 */
public final class CallbackApprovalGate implements ApprovalGate {

    private final ApprovalHandler handler;                 // null → headless: deny
    private final Duration timeout;
    private final ApprovalDecision onTimeout;              // DENIED or TIMED_OUT (never APPROVED)
    private final Map<String, DryRunProvider> dryRunProviders;
    private final DecisionStore decisionStore;             // null → no durable side-channel

    private final Map<Key, ApprovalDecision> decided = new ConcurrentHashMap<>();

    private CallbackApprovalGate(Builder b) {
        this.handler = b.handler;
        this.timeout = b.timeout;
        this.onTimeout = b.onTimeout;
        this.dryRunProviders = Map.copyOf(b.dryRunProviders);
        this.decisionStore = b.decisionStore;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ApprovalDecision request(ApprovalRequest req) {
        Objects.requireNonNull(req, "req");
        Key key = new Key(req.run(), req.call());
        ApprovalDecision cached = decided.get(key);
        if (cached != null) {
            return cached; // idempotent on retry — never re-prompt the human (AC6)
        }
        ApprovalDecision stored = fromStore(req);
        if (stored != null) {
            decided.put(key, stored); // host already collected this decision — never re-prompt
            return stored;
        }
        ApprovalDecision decision = decide(req);
        decided.put(key, decision);
        recordToStore(req, decision);
        return decision;
    }

    /** A store miss or a throwing store falls through to the handler prompt — never fail open. */
    private ApprovalDecision fromStore(ApprovalRequest req) {
        if (decisionStore == null) {
            return null;
        }
        try {
            return decisionStore.find(req).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void recordToStore(ApprovalRequest req, ApprovalDecision decision) {
        if (decisionStore == null) {
            return;
        }
        try {
            decisionStore.record(req, decision);
        } catch (RuntimeException e) {
            // bookkeeping only — the decision stands regardless of the store
        }
    }

    private ApprovalDecision decide(ApprovalRequest req) {
        if (handler == null) {
            return ApprovalDecision.DENIED; // headless / no handler → fail closed (AC8)
        }
        if (timeout.isZero() || timeout.isNegative()) {
            return onTimeout; // no human wait configured → disposition (OFFLINE PT0S → DENIED)
        }
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "approval-gate");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<ApprovalDecision> f = ex.submit(() -> handler.decide(req));
            try {
                ApprovalDecision d = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return d == null ? ApprovalDecision.DENIED : d; // null decision → fail closed
            } catch (TimeoutException te) {
                f.cancel(true);
                return onTimeout; // AC5
            } catch (ExecutionException ee) {
                return ApprovalDecision.DENIED; // handler threw → fail closed
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return ApprovalDecision.DENIED;
            }
        } finally {
            ex.shutdownNow();
        }
    }

    @Override
    public DryRunResult dryRun(ToolCall call) {
        Objects.requireNonNull(call, "call");
        DryRunProvider provider = dryRunProviders.get(call.toolName());
        if (provider == null) {
            return new DryRunResult(false, "no dry-run available for " + call.toolName(), Map.of());
        }
        try {
            DryRunResult result = provider.preview(call);
            return result != null ? result
                    : new DryRunResult(false, "dry-run returned no preview for " + call.toolName(), Map.of());
        } catch (RuntimeException e) {
            // A failed preview must not block approval — the human still decides without one.
            return new DryRunResult(false, "dry-run failed for " + call.toolName() + ": " + e.getMessage(), Map.of());
        }
    }

    private record Key(RunId run, ToolCall call) {
    }

    public static final class Builder {

        private ApprovalHandler handler;
        private Duration timeout = Duration.ofMinutes(5);
        private ApprovalDecision onTimeout = ApprovalDecision.TIMED_OUT;
        private final Map<String, DryRunProvider> dryRunProviders = new HashMap<>();
        private DecisionStore decisionStore;

        public Builder handler(ApprovalHandler handler) {
            this.handler = handler;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        public Builder onTimeout(ApprovalDecision onTimeout) {
            Objects.requireNonNull(onTimeout, "onTimeout");
            if (onTimeout == ApprovalDecision.APPROVED) {
                throw new ConfigException("approval.onTimeout must be DENIED or TIMED_OUT, never APPROVED");
            }
            this.onTimeout = onTimeout;
            return this;
        }

        public Builder decisionStore(DecisionStore decisionStore) {
            this.decisionStore = decisionStore;
            return this;
        }

        public Builder dryRunProvider(String toolName, DryRunProvider provider) {
            this.dryRunProviders.put(Objects.requireNonNull(toolName, "toolName"),
                    Objects.requireNonNull(provider, "provider"));
            return this;
        }

        public CallbackApprovalGate build() {
            return new CallbackApprovalGate(this);
        }
    }
}
