package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.domain.LifecycleDecision;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S13 §6 request bodies (E8-T01): required by default, the `?` fields of §6 optional. Months are `YYYY-MM` (a malformed one
 * → 400 VALIDATION_ERROR); `version` is the period's optimistic lock (`409 STALE_VERSION`). S13 §3: `toMonth = null` is an open
 * period («— encara no ho sé»), so every inactivity body publishes it nullable, and the two PATCH bodies keep field presence
 * (R-13-04, E8-T01 round 2): an omitted field keeps its value, `toMonth: null` opens the period and `comments: null` clears them.
 */
public final class LifecycleRequests {
    private LifecycleRequests() { }
    static final String MONTH = LifecycleContracts.MONTH;
    static final String OPEN_END = "Absent or null = «— encara no ho sé» (open)";
    static final String PATCH_END = "Omitted = the end stays; null = the period becomes open («— encara no ho sé»)";

    public record InactivityRequest(@NotBlank @Pattern(regexp = MONTH) @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH, description = OPEN_END) @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String comments) { }
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "R-13-04: only the fields sent change; an unknown field → 400 VALIDATION_ERROR")
    public static final class InactivityPatchRequest extends MonthsPatch { }
    public record AdminInactivityRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotBlank @Pattern(regexp = MONTH) @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH, description = OPEN_END) @Pattern(regexp = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) @Size(max = 500) String comments,
            @Schema(requiredMode = NOT_REQUIRED, description = "«Salta el termini del dia {D}» (R-13-03, audited)") Boolean overrideDeadline) { }
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE, description = "R-13-04 from D-screens: only the fields sent change; an unknown field → 400 VALIDATION_ERROR")
    public static final class AdminInactivityPatchRequest extends MonthsPatch {
        @Schema(requiredMode = NOT_REQUIRED, description = "«Salta el termini del dia {D}» (R-13-03, audited)") public Boolean overrideDeadline;
    }
    /**
     * The months and comments of an inactivity PATCH with their presence: {@link #patch()} holds exactly the fields the body
     * sent. `fromMonth` is never null (a period always starts); `toMonth` and `comments` may be sent as null.
     */
    public abstract static class MonthsPatch {
        @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH, description = "Omitted = the start stays; never null") @Pattern(regexp = MONTH) public String fromMonth;
        @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH, description = PATCH_END) @Pattern(regexp = MONTH) public String toMonth;
        @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Omitted = the comments stay; null clears them") @Size(max = 500) public String comments;
        @NotNull @PositiveOrZero @Schema(minimum = "0") public Long version;
        @JsonIgnore private final Set<String> present = new HashSet<>();
        @JsonSetter("fromMonth") public void setFromMonth(String value) {
            if (value == null) { throw new IllegalArgumentException("fromMonth is never null"); }
            fromMonth = value; present.add("fromMonth");
        }
        @JsonSetter("toMonth") public void setToMonth(String value) { toMonth = value; present.add("toMonth"); }
        @JsonSetter("comments") public void setComments(String value) { comments = value; present.add("comments"); }
        /** The fields the body sent, in order, with their values; a null value means «cleared» (`toMonth`: open the period). */
        public Map<String, Object> patch() {
            var values = new LinkedHashMap<String, Object>();
            if (present.contains("fromMonth")) { values.put("fromMonth", fromMonth); }
            if (present.contains("toMonth")) { values.put("toMonth", toMonth); }
            if (present.contains("comments")) { values.put("comments", comments); }
            return values;
        }
        @JsonAnySetter public void rejectUnknown(String name, Object value) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
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
