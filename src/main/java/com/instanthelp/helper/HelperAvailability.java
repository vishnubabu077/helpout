package com.instanthelp.helper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of helper_availability. {@code version} increases on every state change. */
public record HelperAvailability(
        UUID id,
        UUID helperId,
        AvailabilityStatus status,
        double latitude,
        double longitude,
        List<String> categoryCodes,
        int maxDistanceM,
        UUID currentTaskId,
        Instant offerExpiresAt,
        Instant lastHeartbeatAt,
        long version) {}
