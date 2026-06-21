/**
 * Runnable sample apps that showcase the EOI Agent platform end-to-end by booting the reference
 * Application Pack ("Acme Lakehouse Suite") through {@code PlatformBuilder}. Each {@code *Demo} class
 * has a {@code main} focused on one capability area; {@link com.eoiagent.examples.RunAllDemos} runs
 * them in sequence.
 *
 * <p>They run fully offline against a deterministic {@code StubLlmGateway} by default, and use a local
 * Ollama server automatically when one is reachable (see {@link com.eoiagent.examples.DemoSupport}).
 * This module is a learning surface and a host-integration example, not a library other modules depend on.
 */
package com.eoiagent.examples;
