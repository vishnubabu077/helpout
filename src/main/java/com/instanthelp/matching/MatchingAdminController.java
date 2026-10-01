package com.instanthelp.matching;

import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/matching")
@Tag(name = "Admin", description = "Operations for administrators (ADMIN role)")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class MatchingAdminController {

    private final GeoIndexRebuilder rebuilder;

    public MatchingAdminController(GeoIndexRebuilder rebuilder) {
        this.rebuilder = rebuilder;
    }

    @PostMapping("/rebuild")
    @Operation(summary = "Rebuild the Redis helper index from Postgres",
            description = "Re-adds every AVAILABLE helper with a fresh heartbeat and drops orphaned GEO members. "
                    + "Idempotent and safe while the system is running.")
    @ApiResponse(responseCode = "200", description = "Rebuilt", content = @Content(
            examples = @ExampleObject(value = "{\"helpersIndexed\": 12, \"orphansRemoved\": 1}")))
    @ApiResponse(responseCode = "403", description = "Not an administrator")
    public GeoIndexRebuilder.Result rebuild() {
        return rebuilder.rebuild();
    }
}
