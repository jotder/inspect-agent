package com.eoiagent.host;

import com.eoiagent.core.AgentAnswer;
import com.eoiagent.core.UserMessage;

/** A live agent session supporting blocking and streaming asks. */
public interface AgentSession {

    AgentAnswer ask(UserMessage msg);

    void askStream(UserMessage msg, AnswerSink sink);

    void close();
}
