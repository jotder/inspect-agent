package com.eoiagent.model;

/** Callback sink for streamed chat tokens and terminal events. */
public interface TokenSink {

    void onToken(String token);

    void onComplete(ChatResult result);

    void onError(Throwable error);
}
