package com.instanthelp.identity;

import com.instanthelp.common.NotFoundException;
import com.instanthelp.common.SecurityUtils;
import com.instanthelp.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "Profile")
@SecurityRequirement(name = OpenApiConfig.BEARER)
public class MeController {

    private final AppUserRepository users;

    public MeController(AppUserRepository users) {
        this.users = users;
    }

    @GetMapping
    @Operation(summary = "Current user")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        AppUser u = users.findById(SecurityUtils.userId(jwt))
                .orElseThrow(() -> new NotFoundException("User not found"));
        return new MeResponse(u.getId(), u.getPhone(), u.getDisplayName(), u.getRole());
    }
}
