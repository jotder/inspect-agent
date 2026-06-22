package com.eoiagent.runtime;

import com.eoiagent.core.RunId;
import com.eoiagent.persistence.Checkpoint;
import com.eoiagent.persistence.CheckpointStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory, append-only {@link CheckpointStore} test double: enough to assert that the orchestrator
 * saves one checkpoint per node in order. The production {@code InMemory}/{@code Postgres} stores are
 * the deliverable of T-302.
 */
final class RecordingCheckpointStore implements CheckpointStore {

    private final Map<RunId, List<Checkpoint>> byRun = new LinkedHashMap<>();

    @Override
    public void save(RunId id, Checkpoint cp) {
        byRun.computeIfAbsent(id, k -> new ArrayList<>()).add(cp);
    }

    @Override
    public Optional<Checkpoint> latest(RunId id) {
        List<Checkpoint> list = byRun.get(id);
        return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    @Override
    public List<Checkpoint> history(RunId id) {
        return List.copyOf(byRun.getOrDefault(id, List.of()));
    }
}
