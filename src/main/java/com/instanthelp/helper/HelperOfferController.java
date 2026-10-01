package com.instanthelp.helper;

import com.instanthelp.common.SecurityUtils;
import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/helpers/me/offers/current")
@Tag(name = "Helper offers", description = "Accept or decline the task you were offered")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class HelperOfferController {

    private final HelperOfferService service;

    public HelperOfferController(HelperOfferService service) {
        this.service = service;
    }

    @PostMapping("/accept")
    @Operation(summary = "Accept the current offer",
            description = "Availability becomes ASSIGNED and the task becomes ASSIGNED to you. Only works while the "
                    + "offer is still OFFERED and has not expired.")
    @ApiResponse(responseCode = "200", description = "Assigned", content = @Content(examples = @ExampleObject(value = """
            {"id": "5b2f6e0e-6a3a-4f0e-9d58-0c3f1e1f6a11", "status": "ASSIGNED", "latitude": 54.6872,
             "longitude": 25.2797, "categoryCodes": ["SHOPPING"], "maxDistanceM": 3000,
             "currentTaskId": "0d7a3c1e-2b5f-4c6d-8e9a-1f2b3c4d5e6f",
             "lastHeartbeatAt": "2035-01-01T10:00:20Z", "version": 3}""")))
    @ApiResponse(responseCode = "404", description = "NO_CURRENT_OFFER: you have no active availability")
    @ApiResponse(responseCode = "409", description = "OFFER_EXPIRED: the offer expired, was withdrawn or already handled")
    public AvailabilityResponse accept(@AuthenticationPrincipal Jwt jwt) {
        return service.accept(SecurityUtils.userId(jwt));
    }

    @PostMapping("/decline")
    @Operation(summary = "Decline the current offer",
            description = "You become AVAILABLE again (and reappear in matching); the task goes back to OPEN.")
    @ApiResponse(responseCode = "200", description = "Available again", content = @Content(examples = @ExampleObject(value = """
            {"id": "5b2f6e0e-6a3a-4f0e-9d58-0c3f1e1f6a11", "status": "AVAILABLE", "latitude": 54.6872,
             "longitude": 25.2797, "categoryCodes": ["SHOPPING"], "maxDistanceM": 3000,
             "lastHeartbeatAt": "2035-01-01T10:00:20Z", "version": 3}""")))
    @ApiResponse(responseCode = "404", description = "NO_CURRENT_OFFER")
    public AvailabilityResponse decline(@AuthenticationPrincipal Jwt jwt) {
        return service.decline(SecurityUtils.userId(jwt));
    }
}
