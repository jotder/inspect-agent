package com.eoiagent.runtime;

import com.eoiagent.core.Plan;
import com.eoiagent.core.PlanStep;
import com.eoiagent.core.Task;
import com.eoiagent.core.TaskId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.TaskStatus;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Per-run, in-memory {@link TaskManager}: the host-visible source of truth for a run's progress.
 *
 * <p>{@link #create(Plan)} seeds one {@code PENDING} {@link Task} per {@link PlanStep} (ids
 * preserved). {@link #update} enforces a closed transition table — illegal moves (e.g.
 * {@code DONE → IN_PROGRESS}) throw {@link IllegalStateException}; {@code DONE} and {@code FAILED}
 * are terminal. {@link #current()} returns a defensive snapshot callers must not mutate.
 *
 * <p>Scoped to a single run and <strong>not</strong> thread-safe across concurrent runs (spec).
 */
public final class InMemoryTaskManager implements TaskManager {

    /** Legal next states per current state; absent (or empty) means terminal / illegal. */
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = new EnumMap<>(TaskStatus.class);

    static {
        TRANSITIONS.put(TaskStatus.PENDING,
                Set.of(TaskStatus.IN_PROGRESS, TaskStatus.NEEDS_APPROVAL, TaskStatus.BLOCKED, TaskStatus.FAILED));
        TRANSITIONS.put(TaskStatus.IN_PROGRESS,
                Set.of(TaskStatus.DONE, TaskStatus.FAILED, TaskStatus.BLOCKED, TaskStatus.NEEDS_APPROVAL));
        TRANSITIONS.put(TaskStatus.NEEDS_APPROVAL,
                Set.of(TaskStatus.IN_PROGRESS, TaskStatus.DONE, TaskStatus.BLOCKED, TaskStatus.FAILED));
        TRANSITIONS.put(TaskStatus.BLOCKED,
                Set.of(TaskStatus.IN_PROGRESS, TaskStatus.FAILED));
        TRANSITIONS.put(TaskStatus.DONE, Set.of());
        TRANSITIONS.put(TaskStatus.FAILED, Set.of());
    }

    private final List<Task> tasks = new ArrayList<>();

    @Override
    public TaskList create(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        tasks.clear();
        for (PlanStep step : plan.steps()) {
            tasks.add(new Task(step.id(), step.description(), TaskStatus.PENDING, ""));
        }
        return current();
    }

    @Override
    public void update(TaskId id, TaskStatus status, String note) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(note, "note"); // may be empty, never null (spec)

        int idx = indexOf(id);
        if (idx < 0) {
            throw new IllegalArgumentException("unknown task: " + id.value());
        }
        Task existing = tasks.get(idx);
        if (!TRANSITIONS.getOrDefault(existing.status(), Set.of()).contains(status)) {
            throw new IllegalStateException(
                    "illegal task transition " + existing.status() + " -> " + status + " for " + id.value());
        }
        tasks.set(idx, new Task(existing.id(), existing.description(), status, note));
    }

    @Override
    public TaskList current() {
        return new TaskList(List.copyOf(tasks));
    }

    private int indexOf(TaskId id) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
