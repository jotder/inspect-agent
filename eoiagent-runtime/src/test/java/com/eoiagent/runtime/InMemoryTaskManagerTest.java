package com.eoiagent.runtime;

import com.eoiagent.core.Plan;
import com.eoiagent.core.PlanStep;
import com.eoiagent.core.Task;
import com.eoiagent.core.TaskId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.TaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** InMemoryTaskManager: create seeds PENDING tasks, transitions are validated, snapshots are defensive (T-201). */
class InMemoryTaskManagerTest {

    private static Plan twoStepPlan() {
        return new Plan(List.of(
                new PlanStep(new TaskId("step-1"), "read the runbook", false),
                new PlanStep(new TaskId("step-2"), "rerun the load", true)), "two steps");
    }

    @Test
    void createSeedsOnePendingTaskPerStepPreservingIds() {
        TaskList list = new InMemoryTaskManager().create(twoStepPlan());

        assertThat(list.tasks()).extracting(Task::id).extracting(TaskId::value).containsExactly("step-1", "step-2");
        assertThat(list.tasks()).allMatch(t -> t.status() == TaskStatus.PENDING);
        assertThat(list.tasks()).allMatch(t -> t.note().isEmpty());
    }

    @Test
    void legalTransitionsAreApplied() {
        InMemoryTaskManager tm = new InMemoryTaskManager();
        tm.create(twoStepPlan());

        tm.update(new TaskId("step-1"), TaskStatus.IN_PROGRESS, "");
        tm.update(new TaskId("step-1"), TaskStatus.DONE, "answered");
        tm.update(new TaskId("step-2"), TaskStatus.NEEDS_APPROVAL, "awaiting human");
        tm.update(new TaskId("step-2"), TaskStatus.BLOCKED, "denied");

        Task step1 = tm.current().tasks().get(0);
        Task step2 = tm.current().tasks().get(1);
        assertThat(step1.status()).isEqualTo(TaskStatus.DONE);
        assertThat(step1.note()).isEqualTo("answered");
        assertThat(step2.status()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    void illegalTransitionFromTerminalStateThrows() {
        InMemoryTaskManager tm = new InMemoryTaskManager();
        tm.create(twoStepPlan());
        tm.update(new TaskId("step-1"), TaskStatus.IN_PROGRESS, "");
        tm.update(new TaskId("step-1"), TaskStatus.DONE, "");

        assertThatThrownBy(() -> tm.update(new TaskId("step-1"), TaskStatus.IN_PROGRESS, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DONE -> IN_PROGRESS");
    }

    @Test
    void updatingUnknownTaskThrows() {
        InMemoryTaskManager tm = new InMemoryTaskManager();
        tm.create(twoStepPlan());

        assertThatThrownBy(() -> tm.update(new TaskId("nope"), TaskStatus.DONE, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void nullNoteIsRejected() {
        InMemoryTaskManager tm = new InMemoryTaskManager();
        tm.create(twoStepPlan());

        assertThatThrownBy(() -> tm.update(new TaskId("step-1"), TaskStatus.IN_PROGRESS, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void currentReturnsDefensiveSnapshot() {
        InMemoryTaskManager tm = new InMemoryTaskManager();
        tm.create(twoStepPlan());

        TaskList snapshot = tm.current();
        tm.update(new TaskId("step-1"), TaskStatus.IN_PROGRESS, "now");

        // The earlier snapshot is unaffected by the later update (it is a copy).
        assertThat(snapshot.tasks().get(0).status()).isEqualTo(TaskStatus.PENDING);
        assertThat(tm.current().tasks().get(0).status()).isEqualTo(TaskStatus.IN_PROGRESS);
    }
}
