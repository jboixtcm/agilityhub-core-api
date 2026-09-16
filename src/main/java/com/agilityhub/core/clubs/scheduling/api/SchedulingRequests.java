package com.agilityhub.core.clubs.scheduling.api;

import com.agilityhub.core.clubs.scheduling.domain.*;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

public final class SchedulingRequests {
    private SchedulingRequests() { }
    public record WeekTemplateCreateRequest(@NotBlank @Size(max = 40) String name, @NotNull TemplateKind kind,
            @Schema(requiredMode = NOT_REQUIRED) String copyFromId) { }
    public record WeekTemplatePatchRequest(@Schema(requiredMode = NOT_REQUIRED) @Size(max = 40) String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String notes,
            @Schema(requiredMode = NOT_REQUIRED) Boolean active, @NotNull @PositiveOrZero Long version) { }
    public record TimeBandCreateRequest(@NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String startTime,
            @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String endTime) { }
    public record TimeBandPatchRequest(@Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "\\d{2}:\\d{2}") String startTime,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "\\d{2}:\\d{2}") String endTime, @NotNull @PositiveOrZero Long version) { }
    public record TemplateClassCreateRequest(@NotBlank String bandId, @NotNull DayOfWeek dayOfWeek,
            @NotEmpty List<String> instructorIds, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId,
            @NotNull List<String> levelIds, @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 40) String description) { }
    public record TemplateClassPatchRequest(@Schema(requiredMode = NOT_REQUIRED) String bandId,
            @Schema(requiredMode = NOT_REQUIRED) DayOfWeek dayOfWeek, @Schema(requiredMode = NOT_REQUIRED) List<String> instructorIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId, @Schema(requiredMode = NOT_REQUIRED) List<String> levelIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 40) String description,
            @NotNull @PositiveOrZero Long version) { }
    public record WeekCreateRequest(@NotNull @Schema(description = "ISO Monday in the club time zone; otherwise VALIDATION_ERROR") LocalDate startDate) { }
    public record GenerationRequest(@NotBlank String weekdayTemplateId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String saturdayTemplateId) { }
    @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record EmptyRequest() { }
    public record ClassSessionCreateRequest(@NotNull LocalDate date, @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String startTime,
            @NotBlank @Pattern(regexp = "\\d{2}:\\d{2}") String endTime, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId,
            @NotNull List<String> levelIds, @NotEmpty List<String> instructorIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 40) String description,
            @Schema(requiredMode = NOT_REQUIRED) Boolean cancelBookings) { }
    public record ClassSessionPatchRequest(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringId,
            @Schema(requiredMode = NOT_REQUIRED) List<String> levelIds, @Schema(requiredMode = NOT_REQUIRED) List<String> instructorIds,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Positive Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "\\d{2}:\\d{2}") String startTime,
            @Schema(requiredMode = NOT_REQUIRED) @Pattern(regexp = "\\d{2}:\\d{2}") String endTime,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 40) String description,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String notes,
            @Schema(requiredMode = NOT_REQUIRED) Boolean riskExempt, @Schema(requiredMode = NOT_REQUIRED) Boolean cancelBookings,
            @NotNull @PositiveOrZero Long version) {
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    public enum ManualClassCancellationReason { CLUB_MANUAL, DELETED }
    public record ClassCancellationRequest(@NotNull ManualClassCancellationReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String adminText) { }
    public record RiskExemptionRequest(@NotNull Boolean exempt) { }
    public record RingBlockCreateRequest(@NotBlank String ringId, @NotNull Instant from, @NotNull Instant to,
            @NotNull @Schema(description = "RESERVATION requires FREE_TRAINING; enforced by E4-T03") RingBlockKind kind,
            @NotNull RingBlockReason reason, @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 200) String note,
            @Schema(requiredMode = NOT_REQUIRED, description = "ADMIN only") Boolean cancelBookings) { }
    public record RingBlockPatchRequest(@Schema(requiredMode = NOT_REQUIRED) String ringId,
            @Schema(requiredMode = NOT_REQUIRED) Instant from, @Schema(requiredMode = NOT_REQUIRED) Instant to,
            @Schema(requiredMode = NOT_REQUIRED) RingBlockKind kind, @Schema(requiredMode = NOT_REQUIRED) RingBlockReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 200) String note,
            @Schema(requiredMode = NOT_REQUIRED, description = "ADMIN only") Boolean cancelBookings, @NotNull @PositiveOrZero Long version) { }
}
