package com.instanthelp.task;

import java.util.UUID;

/**
 * Published (inside the closing transaction) when a task reaches a final state, so other modules can clean up,
 * e.g. release the helper's availability. {@code finalStatus} is COMPLETED or CANCELLED.
 */
public record TaskClosedEvent(UUID taskId, TaskStatus finalStatus) {}
