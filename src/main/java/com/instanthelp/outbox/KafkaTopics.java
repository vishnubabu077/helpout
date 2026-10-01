package com.instanthelp.outbox;

import java.util.Map;

public final class KafkaTopics {
    private KafkaTopics() {}

    public static final String HELPER_AVAILABILITY = "helper-availability";
    public static final String TASK_EVENTS = "task-events";

    /** Outbox aggregate_type -> topic. */
    static final Map<String, String> BY_AGGREGATE = Map.of(
            "HELPER_AVAILABILITY", HELPER_AVAILABILITY,
            "TASK", TASK_EVENTS);
}
