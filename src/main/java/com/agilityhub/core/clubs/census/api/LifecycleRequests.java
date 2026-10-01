package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.domain.LifecycleDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S13 §6 request bodies (E8-T01): required by default, the `?` fields of §6 optional. Months are `YYYY-MM` (a malformed one
 * → 400 VALIDATION_ERROR); `version` is the period's optimistic lock (`409 STALE_VERSION`).
 */
public final class LifecycleRequests {
    private LifecycleRequests() { }
    static final String MONTH = LifecycleContracts.MONTH;

    public record InactivityRequest(@NotBlank @Pattern(regexp = MONTH) @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH, description = "Absent or null = «— encara no ho sé» (open)") @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String comments) { }
    public record InactivityPatchRequest(@Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) @Pattern(regexp = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String comments, @NotNull Long version) { }
    public record AdminInactivityRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotBlank @Pattern(regexp = MONTH) @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String comments,
            @Schema(requiredMode = NOT_REQUIRED, description = "«Salta el termini del dia {D}» (R-13-03, audited)") Boolean overrideDeadline) { }
    public record AdminInactivityPatchRequest(@Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) @Pattern(regexp = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String comments, @Schema(requiredMode = NOT_REQUIRED) Boolean overrideDeadline,
            @NotNull Long version) { }
    public record DecisionRequest(@NotNull LifecycleDecision decision, @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record TerminationRequest(@NotBlank @Pattern(regexp = MONTH) @Schema(pattern = MONTH) String toMonth) { }
    public record AdminCancellationRequest(@Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record LeaveCreateRequest(@NotNull @Schema(description = "Club-local; today or later (422 LEAVE_DATE_INVALID)") LocalDate requestedDate,
            @NotBlank @Schema(description = "A key of leave.reasons (another → 422 LEAVE_REASON_UNKNOWN)") String reasonKey,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with leave.npsEnabled (otherwise 403 READ_ONLY)") @Min(0) @Max(10) Integer nps,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 2000) String comment) { }
    public record LeaveDecisionRequest(@NotNull LifecycleDecision decision,
            @Schema(requiredMode = NOT_REQUIRED, description = "APPROVED only; default requestedDate; today or later (422 LEAVE_DATE_INVALID)") LocalDate effectiveDate,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record DirectLeaveRequest(@NotNull LocalDate effectiveDate, @Schema(requiredMode = NOT_REQUIRED) String reasonKey,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record ReactivationRequest(@Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "Required with BILLING (R-13-16)") String planId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "Required with BILLING") String priceId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Required with BILLING") LocalDate nextInvoiceDate) { }
}
