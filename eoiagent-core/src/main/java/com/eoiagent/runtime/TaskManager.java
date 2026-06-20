package com.eoiagent.runtime;

import com.eoiagent.core.Plan;
import com.eoiagent.core.TaskId;
import com.eoiagent.core.TaskList;
import com.eoiagent.core.TaskStatus;

/** Port managing the task list lifecycle for a run. */
public interface TaskManager {

    TaskList create(Plan plan);

    void update(TaskId id, TaskStatus status, String note);

    TaskList current();
}
