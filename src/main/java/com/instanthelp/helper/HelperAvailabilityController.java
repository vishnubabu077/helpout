package com.instanthelp.helper;

import com.instanthelp.common.SecurityUtils;
import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/helpers/me/availability")
@Tag(name = "Helper availability", description = "\"Ready to help\": go available, send heartbeats, go offline")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class HelperAvailabilityController {

    private static final String AVAILABILITY_EXAMPLE = """
            {"id": "5b2f6e0e-6a3a-4f0e-9d58-0c3f1e1f6a11", "status": "AVAILABLE", "latitude": 54.6872,
             "longitude": 25.2797, "categoryCodes": ["SHOPPING", "CLEANING"], "maxDistanceM": 3000,
             "lastHeartbeatAt": "2035-01-01T10:00:00Z", "version": 1}""";

    private final HelperAvailabilityService service;

    public HelperAvailabilityController(HelperAvailabilityService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Go available",
            description = "Starts an availability session with a location, categories and a maximum distance. "
                    + "The helper is taken from the JWT. Send heartbeats (PUT .../location) at least every "
                    + "heartbeat-timeout (default 90s) or the session expires.")
    @ApiResponse(responseCode = "201", description = "Available", content = @Content(
            examples = @ExampleObject(value = AVAILABILITY_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "409", description = "ALREADY_AVAILABLE: an active availability exists")
    @ApiResponse(responseCode = "422", description = "UNSUPPORTED_CATEGORY")
    public ResponseEntity<AvailabilityResponse> goAvailable(@Valid @RequestBody GoAvailableRequest request,
                                                            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.goAvailable(SecurityUtils.userId(jwt), request));
    }

    @PutMapping("/location")
    @Operation(summary = "Heartbeat + location update",
            description = "Updates the location and refreshes last_heartbeat_at. Works in any active state.")
    @ApiResponse(responseCode = "200", description = "Updated", content = @Content(
            examples = @ExampleObject(value = AVAILABILITY_EXAMPLE)))
    @ApiResponse(responseCode = "404", description = "NOT_AVAILABLE: no active availability, go available again")
    public AvailabilityResponse updateLocation(@Valid @RequestBody LocationUpdateRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        return service.updateLocation(SecurityUtils.userId(jwt), request);
    }

    @DeleteMapping
    @Operation(summary = "Go offline", description = "Only allowed while AVAILABLE.")
    @ApiResponse(responseCode = "204", description = "Offline")
    @ApiResponse(responseCode = "404", description = "NOT_AVAILABLE")
    @ApiResponse(responseCode = "409", description = "HELPER_BUSY: holding an offer or an assigned task")
    public ResponseEntity<Void> goOffline(@AuthenticationPrincipal Jwt jwt) {
        service.goOffline(SecurityUtils.userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Current availability",
            description = "Returns status OFFLINE with no other fields when there is no active availability.")
    @ApiResponse(responseCode = "200", description = "Current state", content = @Content(
            examples = @ExampleObject(value = AVAILABILITY_EXAMPLE)))
    public AvailabilityResponse current(@AuthenticationPrincipal Jwt jwt) {
        return service.current(SecurityUtils.userId(jwt));
    }
}
