package com.agilityhub.core.clubs.training.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S09 §6 request bodies. */
public final class TrainingRequests {
    private TrainingRequests() { }
    public record TrainingBookingRequest(@NotBlank String dogId, @NotNull Instant startsAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Absent = «Qualsevol»: first FREE ring by catalog order (R-09-07)") String ringId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Impersonation only (R-09-16); otherwise 422 OVERRIDE_NOT_ALLOWED") @Valid TrainingOverride override) { }
    public record TrainingOverride(@NotNull Boolean limit, @NotBlank @Size(max = 500) String reason) { }
    public record TrainingCancellationRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true,
            description = "Required for a late cancellation with the impersonation token (ADMIN_LATE, R-09-10)") @Size(max = 500) String reason) { }
}
