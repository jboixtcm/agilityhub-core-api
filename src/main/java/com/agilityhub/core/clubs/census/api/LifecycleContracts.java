package com.agilityhub.core.clubs.census.api;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.shared.application.contract.ApiContracts.Filter;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S13 §3/§6 response forms of inactivity and leave (E8-T01, WP-13-A). Required by default; a nullable field is not required
 * and is sent as `null`. Months are `YYYY-MM`, business dates club-local `date`, instants UTC, money `Money`. Without
 * `BILLING` every fee is `null` (R-13-19).
 */
public final class LifecycleContracts {
    private LifecycleContracts() { }
    static final String MONTH = "\\d{4}-(0[1-9]|1[0-2])";

    // ---- Shared parts (S13 §3)
    @Schema(description = "Who asked: the member's account, or the admin's under impersonation (impersonatedMemberId set, origin BACKOFFICE)")
    public record Requester(@Schema(format = "uuid") String accountId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String impersonatedMemberId) { }
    @Schema(description = "A booking R-13-06/R-13-12 cancelled; sessionDate is club-local")
    public record CancelledBooking(CancelledBookingType type, @Schema(format = "uuid") String id, LocalDate sessionDate) { }
    @Schema(description = "A member as the S13 lists and details show them")
    public record LifecycleMember(@Schema(format = "uuid") String id, String fullName, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer memberNumber) { }
    @Schema(description = "The inactivity fee (R-13-08): the first month's and each following month's, from the parameters or frozen at approval")
    public record InactivityFee(Money firstMonth, Money followingMonths) { }

    // ---- Inactivity (S13 §3 `InactivityPeriod`)
    @Schema(description = "R-13-05: the admin's decision; deadlineOverridden when R-13-03's day-25 rule was skipped (audited)")
    public record InactivityDecision(Instant at, @Schema(format = "uuid") String byAccountId, LifecycleDecision decision,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, maxLength = 500) String note, boolean deadlineOverridden) { }
    @Schema(description = "R-13-04: one row per change of months, append-only")
    public record InactivityHistoryEntry(Instant at, @Schema(format = "uuid") String byAccountId, @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH) String toMonth, ChangeSource source) { }
    @Schema(description = "What the caller may still change now (R-13-03/04): the months and the withdrawal")
    public record InactivityEditable(boolean fromMonth, boolean toMonth, boolean cancel) { }
    @Schema(description = "S13 §3 InactivityPeriod, whole months from the 1st of fromMonth to the end of toMonth (null = open, «encara no ho sé»). "
            + "feeSnapshot is frozen at approval with BILLING (R-13-08), null before or without BILLING. The member is the period's owner.")
    public record InactivityPeriod(@Schema(format = "uuid") String id, LifecycleMember member, @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, maxLength = 500) String comments, InactivityState state, LifecycleOrigin origin,
            Instant requestedAt, Requester requestedBy, @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityDecision decision,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFee feeSnapshot, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant startedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant finishedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFinishReason finishReason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LifecycleCanceller cancelledBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityCancelReason cancelReason, List<CancelledBooking> cancelledBookings,
            List<InactivityHistoryEntry> history, InactivityEditable editable, long version) { }
    @Schema(description = "A row of «Inactivitats» (universal list, CONVENCIONS_API §4): only the row id is required, fields= leaves out the rest")
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record InactivityPeriodListItem(@Schema(format = "uuid") String id, @Schema(requiredMode = NOT_REQUIRED) LifecycleMember member,
            @Schema(requiredMode = NOT_REQUIRED, pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH) String toMonth,
            @Schema(requiredMode = NOT_REQUIRED) InactivityState state, @Schema(requiredMode = NOT_REQUIRED) LifecycleOrigin origin,
            @Schema(requiredMode = NOT_REQUIRED) Instant requestedAt, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String comments,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFee feeSnapshot,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityDecision decision) { }
    public record InactivityPeriodPage(List<InactivityPeriodListItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @Schema(description = "A period of the caller in screen 14's context (S13 §6 JSON). version is the period's optimistic lock: the member's "
            + "PATCH /me/inactivity-periods/{id} sends it back (R-13-04; an old one → 409 STALE_VERSION, T-13-26)")
    public record MeInactivityPeriod(@Schema(format = "uuid") String id, @Schema(pattern = MONTH) String fromMonth,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, pattern = MONTH) String toMonth, InactivityState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String comments, @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFee fee,
            InactivityEditable editable, @Schema(minimum = "0") long version) { }
    @Schema(description = "S13 §6 GET /me/inactivity-periods: screen 14's context. earliestFromMonth = proposedFromMonth = E(today) (R-13-01); "
            + "deadlineDay = inactivity.requestDeadlineDay; fee null without BILLING; the caller's periods, live ones first.")
    public record MeInactivityContext(@Schema(pattern = MONTH) String earliestFromMonth, @Schema(pattern = MONTH) String proposedFromMonth,
            @Schema(minimum = "1", maximum = "31") int deadlineDay, @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFee fee,
            List<MeInactivityPeriod> periods) { }
    @Schema(description = "Screen 14's yellow note: the caller's live bookings inside the months; a counter of a module that is off "
            + "(WAITLIST, FREE_TRAINING, ACTIVITIES) is absent")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BookingsInside(@Schema(minimum = "0") int classes, @Schema(requiredMode = NOT_REQUIRED, minimum = "0") Integer waitlist,
            @Schema(requiredMode = NOT_REQUIRED, minimum = "0") Integer trainings, @Schema(requiredMode = NOT_REQUIRED, minimum = "0") Integer activities,
            @Schema(minimum = "0") int total) { }
    public record FeeScheduleItem(@Schema(pattern = MONTH) String month, Money amount) { }
    @Schema(description = "S13 §6 GET /me/inactivity-periods/preview (R-13-06/08): feeSchedule empty without BILLING; earliestMonthViolation when "
            + "fromMonth is before E(today) (null when it is not checked)")
    public record InactivityPreview(BookingsInside bookingsInside, List<FeeScheduleItem> feeSchedule,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean earliestMonthViolation) { }

    // ---- Leave (S13 §3 `LeaveRequest`)
    @Schema(description = "R-13-10: effectiveDate becomes Member.leaveDate; null on a DENIED decision")
    public record LeaveDecision(Instant at, @Schema(format = "uuid") String byAccountId, LifecycleDecision decision,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate effectiveDate,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, maxLength = 500) String note) { }
    @Schema(description = "The member of a leave request with their leave state (S13 §3): leaveDate while a leave is planned, leftAt/leftReason once LEFT")
    public record LeaveMember(@Schema(format = "uuid") String id, String fullName, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer memberNumber,
            @Schema(description = "PENDING · ACTIVE · LEFT") String status, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate leaveDate,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant leftAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) MemberLeftReason leftReason) { }
    @Schema(description = "S13 §3 LeaveRequest. reason = the label of reasonKey in the reader's locale (leave.reasons). Decided requests carry "
            + "decision; the cancelled bookings of R-13-12 are traced in cancelledBookings.")
    public record LeaveRequest(@Schema(format = "uuid") String id, LeaveMember member, LeaveSource source, LifecycleOrigin origin, Instant requestedAt,
            Requester requestedBy, LocalDate requestedDate, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reasonKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reason, @Schema(requiredMode = NOT_REQUIRED, nullable = true, minimum = "0", maximum = "10") Integer nps,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, maxLength = 2000) String comment, LeaveRequestState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LeaveDecision decision, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant executedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LifecycleCanceller cancelledBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LeaveCancelReason cancelReason, List<CancelledBooking> cancelledBookings,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String packBalanceId, long version) { }
    @Schema(description = "A row of «Baixes» (universal list, CONVENCIONS_API §4): only the row id is required")
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record LeaveRequestListItem(@Schema(format = "uuid") String id, @Schema(requiredMode = NOT_REQUIRED) LifecycleMember member,
            @Schema(requiredMode = NOT_REQUIRED) LeaveSource source, @Schema(requiredMode = NOT_REQUIRED) LeaveRequestState state,
            @Schema(requiredMode = NOT_REQUIRED) Instant requestedAt, @Schema(requiredMode = NOT_REQUIRED) LocalDate requestedDate,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate effectiveDate,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reasonKey, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer nps,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String comment) { }
    public record LeaveRequestPage(List<LeaveRequestListItem> items, @Schema(minimum = "0") int page, @Schema(minimum = "1") int size,
            @Schema(minimum = "0") long totalItems, @Schema(minimum = "0") int totalPages, List<Filter> appliedFilters) { }
    @Schema(description = "S13 §3 projection: a leave the club accepted, while the member is ACTIVE and leaveDate is set. since = when it was "
            + "decided; cancellable = DELETE /members/{id}/planned-leave applies (R-13-15); requestId null for a migrated leave")
    public record PlannedLeave(LocalDate date, LeaveSource source, @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uuid") String requestId,
            Instant since, boolean cancellable) { }
    @Schema(description = "A leave reason of leave.reasons in the reader's locale")
    public record LeaveReason(String key, String label) { }
    @Schema(description = "A request of the caller in screen 15's context")
    public record MeLeaveRequest(@Schema(format = "uuid") String id, LocalDate requestedDate, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String reasonKey,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer nps, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String comment,
            LeaveRequestState state, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LeaveDecision decision) { }
    @Schema(description = "S13 §6 GET /me/leave-requests: screen 15's context. offerInactivity = INACTIVITY on and the caller's plan may request it; "
            + "fee null without BILLING; defaultDate = today (club-local); reasons = leave.reasons of the MEMBER audience in the reader's locale; "
            + "plannedLeave null without one.")
    public record MeLeaveContext(boolean offerInactivity, @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityFee fee, LocalDate defaultDate,
            boolean fullMonthIfLater, boolean npsEnabled, List<LeaveReason> reasons, @Schema(requiredMode = NOT_REQUIRED, nullable = true) PlannedLeave plannedLeave,
            List<MeLeaveRequest> requests) { }

    // ---- Error details (CATALEG_ERRORS §3 rule 2)
    public record InactivityDeadlineDetails(@Schema(pattern = MONTH) String earliestMonth) { }
    public record InactivityOverlapDetails(@Schema(format = "uuid") String periodId, OverlapHint hint) { }
    public record MemberLeavingDetails(LocalDate leaveDate) { }
}
