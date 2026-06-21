package com.eoiagent.platform;

import com.eoiagent.app.PackMetadata;
import com.eoiagent.host.AgentService;

import java.util.List;
import java.util.Objects;

/**
 * The assembled {@link AgentPlatform}. Holds the wired {@link AgentService} and the pack's
 * {@link PackMetadata}, and owns the lifecycle of the adapters the platform built.
 *
 * <p>{@link #close()} closes each owned resource that is {@link AutoCloseable}, in reverse order of
 * construction, best-effort (a failure to close one does not stop the others). It is idempotent.
 */
final class DefaultAgentPlatform implements AgentPlatform {

    private final AgentService agentService;
    private final PackMetadata pack;
    private final List<AutoCloseable> ownedResources;
    private volatile boolean closed;

    DefaultAgentPlatform(AgentService agentService, PackMetadata pack, List<AutoCloseable> ownedResources) {
        this.agentService = Objects.requireNonNull(agentService, "agentService");
        this.pack = Objects.requireNonNull(pack, "pack");
        this.ownedResources = List.copyOf(Objects.requireNonNull(ownedResources, "ownedResources"));
    }

    @Override
    public AgentService agentService() {
        return agentService;
    }

    @Override
    public PackMetadata pack() {
        return pack;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Reverse order: tear down dependents before their dependencies.
        for (int i = ownedResources.size() - 1; i >= 0; i--) {
            try {
                ownedResources.get(i).close();
            } catch (Exception ignored) {
                // best-effort: one adapter failing to close must not block the rest
            }
        }
    }
}
