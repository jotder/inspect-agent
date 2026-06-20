package com.eoiagent.eval;

import com.eoiagent.core.DeploymentProfile;
import com.eoiagent.host.AgentService;

/** Runs an eval suite against an agent service under a deployment profile and reports outcomes. */
public interface EvalHarness {

    EvalReport run(EvalSuite suite, AgentService agent, DeploymentProfile profile);
}
