package com.instanthelp.helper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public record GoAvailableRequest(
        @Schema(example = "54.6872")
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,

        @Schema(example = "25.2797")
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,

        @Schema(description = "Categories you will help with, see GET /v1/categories", example = "[\"SHOPPING\", \"CLEANING\"]")
        @NotEmpty @Size(max = 10) List<@NotBlank String> categoryCodes,

        @Schema(description = "How far you are willing to travel, in metres", example = "3000")
        @Min(100) @Max(50000) int maxDistanceM) {}
