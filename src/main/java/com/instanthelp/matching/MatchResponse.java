package com.instanthelp.matching;

import com.instanthelp.task.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** The helper who now holds an offer for the task. Never contains the helper's exact location. */
public record MatchResponse(
        UUID taskId,
        @Schema(example = "MATCHING") TaskStatus taskStatus,
        @Schema(description = "The helper must accept before this instant, otherwise the task is offered again")
        Instant offerExpiresAt,
        OfferedHelper helper,
        @Schema(description = "Distance to the helper, rounded up to the next 100 m", example = "600")
        int approximateDistanceM) {

    /** Public profile only: no phone number, no location. */
    public record OfferedHelper(
            @Schema(example = "5b2f6e0e-6a3a-4f0e-9d58-0c3f1e1f6a11") UUID id,
            @Schema(example = "Alex") String displayName) {}
}
