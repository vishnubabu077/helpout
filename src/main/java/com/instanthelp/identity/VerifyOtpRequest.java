package com.instanthelp.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @Schema(description = "Phone number in E.164 format", example = "+15555550101")
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in E.164 format, e.g. +15555550101")
        String phone,
        @Schema(description = "6-digit code. With the local profile it is always 000000", example = "000000")
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be 6 digits")
        String code) {}
