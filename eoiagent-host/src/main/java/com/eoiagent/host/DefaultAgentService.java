package com.eoiagent.host;

import com.eoiagent.config.ConfigProvider;
import com.eoiagent.core.AgentContext;
import com.eoiagent.core.AppId;
import com.eoiagent.core.ConfigException;
import com.eoiagent.core.SessionId;
import com.eoiagent.observability.AuditSink;
import com.eoiagent.runtime.Orchestrator;
import com.eoiagent.safety.Guardrail;

import java.util.Objects;
import java.util.UUID;

/**
 * The host's single entry point. Wires the composed ports — {@link ConfigProvider}, the input
 * {@link Guardrail}, the {@link Orchestrator} and the {@link AuditSink} — into per-user
 * {@link AgentSession}s. It depends only on ports (all in {@code eoiagent-core}); concrete adapters
 * are chosen by config and injected here by the platform, so no framework type crosses this boundary.
 *
 * <p>Thread-safe factory: many sessions may be opened concurrently.
 */
public final class DefaultAgentService implements AgentService {

    private final AppId appId;
    private final ConfigProvider config;
    private final Orchestrator orchestrator;
    private final Guardrail inputGuardrail;
    private final AuditSink audit;

    public DefaultAgentService(AppId appId, ConfigProvider config, Orchestrator orchestrator,
                               Guardrail inputGuardrail, AuditSink audit) {
        this.appId = Objects.requireNonNull(appId, "appId");
        this.config = Objects.requireNonNull(config, "config");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.inputGuardrail = Objects.requireNonNull(inputGuardrail, "inputGuardrail");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public AgentSession open(SessionRequest req) {
        Objects.requireNonNull(req, "req");
        if (req.user() == null || req.role() == null || req.profile() == null) {
            throw new ConfigException("SessionRequest requires non-null user, role and profile");
        }
        if (req.profile() != config.profile()) {
            throw new ConfigException("session profile " + req.profile()
                    + " does not match the configured profile " + config.profile());
        }
        AgentContext ctx = new AgentContext(appId, new SessionId(UUID.randomUUID().toString()),
                req.user(), req.role(), req.profile(), req.initialPage(), req.attributes());
        return new DefaultAgentSession(ctx, orchestrator, inputGuardrail, audit);
    }
}
