package com.instanthelp.task;

import java.time.Instant;
import java.util.UUID;

/** Kafka payload on the task-events topic (keyed by taskId). */
public record TaskOutboxEvent(UUID taskId, UUID requesterId, UUID helperId, TaskStatus status, Instant occurredAt) {}
