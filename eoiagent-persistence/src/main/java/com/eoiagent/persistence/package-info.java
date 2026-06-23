/**
 * Durability adapters (Component 8): {@link com.eoiagent.persistence.CheckpointStore} implementations
 * behind the core port. {@link com.eoiagent.persistence.InMemoryCheckpointStore} is the offline
 * default; {@link com.eoiagent.persistence.PostgresCheckpointStore} is durable and multi-node (a run
 * survives a JVM restart). Both are append-only — {@code save} never overwrites, {@code latest}
 * returns the most recent checkpoint (empty for an unknown run), and {@code history} returns
 * checkpoints oldest-to-newest — which is what enables resume-after-restart and time-travel/replay
 * for the LangGraph investigation flow (Flow E; consumed by {@code LangGraphOrchestrator}).
 *
 * <p>The {@code CheckpointStore} port and {@code Checkpoint} record live in {@code eoiagent-core}; this
 * module shares the package on the classpath (the same split-package pattern as the other adapters).
 */
package com.eoiagent.persistence;
