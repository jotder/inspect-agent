package com.eoiagent.platform;

import com.eoiagent.app.PackMetadata;
import com.eoiagent.host.AgentService;

/**
 * A fully wired agent instance for one {@link com.eoiagent.app.ApplicationPack}: the host facade
 * ({@link #agentService()}) plus the identity of the pack that produced it. Built by
 * {@link PlatformBuilder#start()}.
 *
 * <p>{@link #close()} releases every {@link AutoCloseable} adapter the platform owns (model handles,
 * registered tools, scratchpad). Adapters the host injected via {@link PlatformBuilder} overrides are
 * the host's to close.
 */
public interface AgentPlatform extends AutoCloseable {

    /** The host's entry point for opening sessions, wired to this pack's core. */
    AgentService agentService();

    /** Which application pack this platform is running. */
    PackMetadata pack();

    @Override
    void close();
}
