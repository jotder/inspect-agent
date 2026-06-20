package com.eoiagent.core;

import java.time.Instant;
import java.util.Map;

/** An immutable audit record describing one notable event in a run. */
public record AuditEvent(Instant at,
                         AppId app,
                         RunId run,
                         SessionId session,
                         UserId user,
                         AuditKind kind,
                         String summary,
                         Map<String, Object> details) {
}
