package com.instanthelp.helper;

import com.instanthelp.task.TaskClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When a task is completed or cancelled, the helper's availability row is released (OFFLINE). Runs in the
 * transaction that closes the task. The helper chooses to go available again explicitly.
 */
@Component
class TaskClosedListener {

    private final HelperAvailabilityService availability;

    TaskClosedListener(HelperAvailabilityService availability) {
        this.availability = availability;
    }

    @EventListener
    void onTaskClosed(TaskClosedEvent event) {
        availability.releaseForTask(event.taskId());
    }
}
