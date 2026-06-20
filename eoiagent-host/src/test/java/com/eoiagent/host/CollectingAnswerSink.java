package com.eoiagent.host;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.EoiAgentException;
import com.eoiagent.core.InlineArtifact;

import java.util.ArrayList;
import java.util.List;

/** In-test {@link AnswerSink} that records the streamed tokens, artifact, and terminal callback. */
final class CollectingAnswerSink implements AnswerSink {

    final List<String> tokens = new ArrayList<>();
    InlineArtifact artifact;
    AgentAnswer completed;
    EoiAgentException error;
    int completeCalls;
    int errorCalls;

    @Override
    public void onToken(String token) {
        tokens.add(token);
    }

    @Override
    public void onArtifact(InlineArtifact artifact) {
        this.artifact = artifact;
    }

    @Override
    public void onComplete(AgentAnswer finalAnswer) {
        this.completed = finalAnswer;
        this.completeCalls++;
    }

    @Override
    public void onError(EoiAgentException error) {
        this.error = error;
        this.errorCalls++;
    }
}
