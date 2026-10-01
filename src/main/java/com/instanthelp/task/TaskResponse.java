package com.instanthelp.task;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID requesterId,
        String title,
        String description,
        String categoryCode,
        String address,
        double latitude,
        double longitude,
        Instant startTime,
        Instant endTime,
        BigDecimal offeredAmount,
        String currency,
        TaskStatus status,
        Instant createdAt) {

    static TaskResponse from(Task t) {
        return new TaskResponse(t.getId(), t.getRequesterId(), t.getTitle(), t.getDescription(),
                t.getCategoryCode(), t.getAddress(), t.getLatitude(), t.getLongitude(),
                t.getTimeStart(), t.getTimeEnd(), t.getOfferedAmount(), t.getCurrency(),
                t.getStatus(), t.getCreatedAt());
    }
}
