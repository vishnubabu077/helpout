package com.instanthelp.helper;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The calling helper's own availability. Only ever returned to that helper. */
public record AvailabilityResponse(
        @Schema(description = "Absent when the helper has no active availability") UUID id,
        AvailabilityStatus status,
        Double latitude,
        Double longitude,
        List<String> categoryCodes,
        Integer maxDistanceM,
        @Schema(description = "Task this helper is offered / assigned to") UUID currentTaskId,
        @Schema(description = "While OFFERED: the offer must be accepted before this instant") Instant offerExpiresAt,
        Instant lastHeartbeatAt,
        Long version) {

    static AvailabilityResponse from(HelperAvailability a) {
        return new AvailabilityResponse(a.id(), a.status(), a.latitude(), a.longitude(), a.categoryCodes(),
                a.maxDistanceM(), a.currentTaskId(), a.offerExpiresAt(), a.lastHeartbeatAt(), a.version());
    }

    static AvailabilityResponse offline() {
        return new AvailabilityResponse(null, AvailabilityStatus.OFFLINE, null, null, null, null, null, null, null, null);
    }
}
