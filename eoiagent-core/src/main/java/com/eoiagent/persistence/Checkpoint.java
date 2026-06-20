package com.eoiagent.persistence;

import com.eoiagent.core.RunId;
import java.time.Instant;

/** A serialized snapshot of run state at a node, for resumption. */
public record Checkpoint(RunId run, String nodeId, byte[] state, Instant at, int seq) {
}
