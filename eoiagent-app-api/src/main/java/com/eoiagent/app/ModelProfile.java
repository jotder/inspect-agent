package com.eoiagent.app;

/**
 * Which chat/embedding models a pack runs and how the platform routes between local and hosted
 * providers. {@code chat()}/{@code embedding()} are non-null; {@code routing().allowHostedFallback()}
 * must be {@code false} whenever the pack's profile is {@code OFFLINE} (validated at {@code start()}).
 */
public interface ModelProfile {

    /** Selection for the chat model. */
    ModelSelection chat();

    /** Selection for the embedding model. */
    ModelSelection embedding();

    /** Local/hosted preference order and fallback policy. */
    RoutingPolicy routing();
}
