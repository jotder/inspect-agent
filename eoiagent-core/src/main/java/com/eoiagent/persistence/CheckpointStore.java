package com.eoiagent.persistence;

import com.eoiagent.core.RunId;
import java.util.List;
import java.util.Optional;

/** Port for saving and loading run checkpoints. */
public interface CheckpointStore {

    void save(RunId id, Checkpoint cp);

    Optional<Checkpoint> latest(RunId id);

    List<Checkpoint> history(RunId id);
}
