package com.instanthelp.helper;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record LocationUpdateRequest(
        @Schema(example = "54.6890")
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,

        @Schema(example = "25.2810")
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude) {}
