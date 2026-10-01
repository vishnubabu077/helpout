package com.instanthelp.helper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kafka payload for every availability change. Keyed by helperId; consumers must ignore an event whose
 * {@code version} is not newer than the one they already applied for {@code availabilityId}.
 */
public record HelperAvailabilityEvent(
        UUID availabilityId,
        UUID helperId,
        AvailabilityStatus status,
        double latitude,
        double longitude,
        List<String> categories,
        int maxDistance,
        long version,
        Instant heartbeatAt) {

    public static HelperAvailabilityEvent from(HelperAvailability row) {
        return new HelperAvailabilityEvent(row.id(), row.helperId(), row.status(), row.latitude(), row.longitude(),
                row.categoryCodes(), row.maxDistanceM(), row.version(), row.lastHeartbeatAt());
    }
}
