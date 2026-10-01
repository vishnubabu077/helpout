package com.instanthelp.task;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public record CreateTaskRequest(
        @Schema(example = "Help carrying groceries upstairs")
        @NotBlank @Size(min = 3, max = 120) String title,

        @Schema(example = "About 6 bags, third floor, no elevator.")
        @Size(max = 1000) String description,

        @Schema(description = "Category code, see GET /v1/categories", example = "SHOPPING")
        @NotBlank String categoryCode,

        @Schema(example = "12 Example Street, Apt 5")
        @NotBlank @Size(max = 255) String address,

        @Schema(example = "54.6872")
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,

        @Schema(example = "25.2797")
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,

        @Schema(description = "Start of the time window (UTC, must be in the future)", example = "2035-01-01T10:00:00Z")
        @NotNull @Future Instant startTime,

        @Schema(description = "End of the time window (UTC, after start, minimum duration applies)",
                example = "2035-01-01T11:00:00Z")
        @NotNull Instant endTime,

        @Schema(description = "Offered pay for the task", example = "15.00")
        @NotNull @DecimalMin(value = "0.01", message = "must be greater than 0")
        @Digits(integer = 8, fraction = 2) BigDecimal offeredAmount,

        @Schema(description = "ISO 4217 code; defaults to the platform currency", example = "EUR")
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter ISO currency code") String currency) {}
