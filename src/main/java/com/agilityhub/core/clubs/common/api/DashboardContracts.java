package com.agilityhub.core.clubs.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.agilityhub.core.shared.application.contract.SignupWarning;
import com.fasterxml.jackson.annotation.JsonInclude;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** E2 public contracts. Implementations map explicit allowlists into these DTOs. */
public final class DashboardContracts {
    private DashboardContracts() { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "S14 §6: module/parameter-controlled blocks are explicitly null when disabled; contract only.")
    public record Dashboard(
            @Schema(requiredMode = REQUIRED) Instant generatedAt,
            @Schema(requiredMode = REQUIRED) LocalDate today,
            @Schema(requiredMode = REQUIRED) DashboardWeek week,
            @Schema(requiredMode = REQUIRED) DashboardKpis kpis,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) RiskReview riskReview,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) PendingSignups pendingSignups,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) DogsByLevel dogsByLevel) { }
    public record DashboardWeek(
            @Schema(requiredMode = REQUIRED) LocalDate start,
            @Schema(requiredMode = REQUIRED) LocalDate end) { }
    public record DashboardCounters(
            @Schema(requiredMode = REQUIRED) int pendingSignups,
            @Schema(requiredMode = REQUIRED) int pendingRequests,
            @Schema(requiredMode = REQUIRED) int followUpUnread) { }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DashboardKpis(
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) ActiveMembersKpi activeMembers,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) ClassOccupancyKpi classOccupancy,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) TrainingBookingsKpi trainingBookings,
            @Schema(requiredMode = REQUIRED, types = {"object", "null"}) PendingSignupsKpi pendingSignups) { }
    public record ActiveMembersKpi(
            @Schema(requiredMode = REQUIRED) int value,
            @Schema(requiredMode = REQUIRED) int deltaThisMonth) { }
    public record ClassOccupancyKpi(
            @Schema(requiredMode = REQUIRED) double percent,
            @Schema(requiredMode = REQUIRED) int booked,
            @Schema(requiredMode = REQUIRED) int capacity,
            @Schema(requiredMode = REQUIRED) int waitingTotal) { }
    public record TrainingBookingsKpi(
            @Schema(requiredMode = REQUIRED) int value,
            @Schema(requiredMode = REQUIRED) int distinctMembers) { }
    public record PendingSignupsKpi(
            @Schema(requiredMode = REQUIRED) int value,
            @Schema(requiredMode = REQUIRED) int olderThanWarn,
            @Schema(requiredMode = REQUIRED) int warnDays) { }
    public record RiskReview(
            @Schema(requiredMode = REQUIRED) String reviewTime,
            @Schema(requiredMode = REQUIRED) int lookaheadDays,
            @Schema(requiredMode = REQUIRED) boolean autoCancelSameDay,
            @Schema(requiredMode = REQUIRED) int count,
            @Schema(requiredMode = REQUIRED) List<RiskItem> items) { }
    public enum RiskStatus { CANCELLED, AT_RISK, WILL_CANCEL, PENDING_DECISION }
    public record RiskItem(
            @Schema(requiredMode = REQUIRED, format = "uuid") String classSessionId,
            @Schema(requiredMode = REQUIRED) LocalDate date,
            @Schema(requiredMode = REQUIRED) String startTime,
            @Schema(requiredMode = REQUIRED) String displayDescription,
            @Schema(requiredMode = REQUIRED) String ringName,
            @Schema(requiredMode = REQUIRED) int booked,
            @Schema(requiredMode = REQUIRED) RiskStatus status,
            @Schema(requiredMode = REQUIRED) List<RiskNotified> notified,
            @Schema(requiredMode = REQUIRED) Instant reviewAt) { }
    public record RiskNotified(
            @Schema(requiredMode = REQUIRED) String memberFirstName,
            @Schema(requiredMode = REQUIRED, allowableValues = {"MALE", "FEMALE", "OTHER"}) String gender,
            @Schema(requiredMode = REQUIRED) String dogName) { }
    public record PendingSignups(
            @Schema(requiredMode = REQUIRED) int count,
            @Schema(requiredMode = REQUIRED) List<PendingSignup> items) { }
    public record PendingSignup(
            @Schema(requiredMode = REQUIRED, format = "uuid") String memberId,
            @Schema(requiredMode = REQUIRED) String shortName,
            @Schema(requiredMode = REQUIRED) List<PendingSignupDog> dogs,
            @Schema(requiredMode = REQUIRED) String planName,
            @Schema(requiredMode = NOT_REQUIRED, allowableValues = {"SEPA_DD", "CARD", "MANUAL"}) String paymentMethodType,
            @Schema(requiredMode = REQUIRED) List<SignupWarning> warnings,
            @Schema(requiredMode = REQUIRED) Instant submittedAt,
            @Schema(requiredMode = REQUIRED) int pendingDays) { }
    public record PendingSignupDog(
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String breed,
            @Schema(requiredMode = REQUIRED) boolean isAddDog) { }
    public record DogsByLevel(
            @Schema(requiredMode = REQUIRED) int totalActiveDogs,
            @Schema(requiredMode = REQUIRED) int activeDogWeeks,
            @Schema(requiredMode = REQUIRED) List<DashboardLevel> levels,
            @Schema(requiredMode = REQUIRED) int others) { }
    public record DashboardLevel(
            @Schema(requiredMode = REQUIRED, format = "uuid") String levelId,
            @Schema(requiredMode = REQUIRED) String code,
            @Schema(requiredMode = REQUIRED) String name,
            @Schema(requiredMode = REQUIRED) String color,
            @Schema(requiredMode = REQUIRED) int total,
            @Schema(requiredMode = REQUIRED) int withRecentBooking) { }

}
