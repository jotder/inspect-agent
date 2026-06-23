package com.eoiagent.persistence;

import com.eoiagent.core.RunId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only, in-memory {@link CheckpointStore} — the offline default. Checkpoints for a run are kept
 * in arrival order: {@link #save} appends (never overwrites), {@link #latest} returns the most recently
 * saved checkpoint, and {@link #history} returns them oldest-to-newest. State is discarded on JVM exit;
 * for resume-after-restart use {@link PostgresCheckpointStore}.
 *
 * <p>Thread-safe: concurrent saves to distinct runs are independent, and concurrent appends to one run
 * are serialized by a {@link CopyOnWriteArrayList}. Reads return immutable snapshots.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final ConcurrentMap<RunId, CopyOnWriteArrayList<Checkpoint>> byRun = new ConcurrentHashMap<>();

    @Override
    public void save(RunId id, Checkpoint cp) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cp, "cp");
        byRun.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(cp);
    }

    @Override
    public Optional<Checkpoint> latest(RunId id) {
        Objects.requireNonNull(id, "id");
        List<Checkpoint> list = byRun.get(id);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    @Override
    public List<Checkpoint> history(RunId id) {
        Objects.requireNonNull(id, "id");
        List<Checkpoint> list = byRun.get(id);
        return list == null ? List.of() : List.copyOf(list); // oldest -> newest, immutable snapshot
    }
}
