package com.eoiagent.app;

import com.eoiagent.core.AppId;

/** Identity of an application pack; {@code appId} is stamped into every {@code AgentContext}/{@code AuditEvent}. */
public record PackMetadata(AppId appId, String name, String version) {
}
