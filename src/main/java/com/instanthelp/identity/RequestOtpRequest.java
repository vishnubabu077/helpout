package com.instanthelp.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RequestOtpRequest(
        @Schema(description = "Phone number in E.164 format", example = "+15555550101")
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in E.164 format, e.g. +15555550101")
        String phone) {}
