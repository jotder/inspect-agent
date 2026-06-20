package com.eoiagent.host;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.InlineArtifact;

/** Host-side callback sink for streamed tokens, artifacts and terminal events. */
public interface AnswerSink {

    void onToken(String token);

    void onArtifact(InlineArtifact artifact);

    void onComplete(AgentAnswer finalAnswer);

    void onError(EoiAgentException error);
}
