package com.instanthelp.task;

import com.instanthelp.common.PagedResponse;
import com.instanthelp.common.SecurityUtils;
import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/v1/tasks")
@Tag(name = "Tasks", description = "Help requests")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class TaskController {

    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Create a help request",
            description = "Validates the request and publishes it as OPEN. The requester is taken from the JWT.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "Validation failed")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token")
    @ApiResponse(responseCode = "422", description = "Business rule violated (category, time window, duration)")
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        TaskResponse created = service.create(SecurityUtils.userId(jwt), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/mine")
    @Operation(summary = "List my help requests", description = "Newest first.")
    public PagedResponse<TaskResponse> mine(@AuthenticationPrincipal Jwt jwt,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return service.listMine(SecurityUtils.userId(jwt), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one of my help requests")
    @ApiResponse(responseCode = "404", description = "Not found (or not yours)")
    public TaskResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.getOwned(SecurityUtils.userId(jwt), id);
    }
}
