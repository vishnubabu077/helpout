package com.instanthelp.matching;

import com.instanthelp.common.SecurityUtils;
import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/tasks")
@Tag(name = "Matching", description = "Find the nearest ready helper for a task")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class MatchingController {

    private final MatchingService service;

    public MatchingController(MatchingService service) {
        this.service = service;
    }

    @PostMapping("/{taskId}/match")
    @Operation(summary = "Match an OPEN task with the nearest available helper",
            description = "Only the task's requester. Reserves the nearest eligible helper (the task becomes MATCHING "
                    + "and the helper has app.matching.offer-ttl to accept). The helper's exact location is never "
                    + "returned, only an approximate distance.")
    @ApiResponse(responseCode = "200", description = "A helper holds an offer", content = @Content(
            examples = @ExampleObject(value = """
                    {"taskId": "0d7a3c1e-2b5f-4c6d-8e9a-1f2b3c4d5e6f", "taskStatus": "MATCHING",
                     "offerExpiresAt": "2035-01-01T10:00:30Z",
                     "helper": {"id": "5b2f6e0e-6a3a-4f0e-9d58-0c3f1e1f6a11", "displayName": "Alex"},
                     "approximateDistanceM": 600}""")))
    @ApiResponse(responseCode = "404", description = "NO_HELPER_AVAILABLE (task stays OPEN), or task not found / not yours")
    @ApiResponse(responseCode = "409", description = "TASK_NOT_OPEN")
    public MatchResponse match(@PathVariable UUID taskId, @AuthenticationPrincipal Jwt jwt) {
        return service.match(SecurityUtils.userId(jwt), taskId);
    }
}
