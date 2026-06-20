package com.eoiagent.host;

/** Top-level entry point the host uses to open agent sessions. */
public interface AgentService {

    AgentSession open(SessionRequest req);
}
