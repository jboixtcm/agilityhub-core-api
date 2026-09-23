package com.agilityhub.core.clubs.training.api;

import com.agilityhub.core.clubs.training.domain.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S09 §6 wire forms (screen 08 and the admin usage register). Slots are computed, never stored (R-09-03);
 * `slotId` = `{ringId}_{startsAt}`. `seatIndex` is internal and never exposed.
 */
public final class TrainingContracts {
    private TrainingContracts() { }
    static final String STAFF = "Only for INSTRUCTOR and ADMIN; never in the MEMBER projection (R-09-12).";

    public enum RightSource { LEVEL, MANUAL }

    // ---- GET /training-slots
    public record TrainingSlots(@Schema(example = "Europe/Madrid") String timeZone, @Schema(description = "training.slotMinutes") int slotMinutes,
            TrainingWindow window, @Schema(description = "Whether dogId may free-train (R-09-01); true without dogId") boolean dogEligible,
            List<TrainingRing> rings, List<TrainingDay> days) { }
    public record TrainingWindow(LocalDate from, LocalDate to) { }
    public record TrainingRing(String id, String name, String shortName, String color, int order,
            @Schema(description = "Ring.trainingCapacity ?? training.capacityPerRingSlot") int capacity,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with COURSES (and courses.showSetupToMembers for MEMBER), R-09-15") RingSetupSummary setup) { }
    public record RingSetupSummary(String id, String kind, List<String> levelNames, Instant builtAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expectedUntil) { }
    public record TrainingDay(LocalDate date, @Schema(description = "Holiday or no opening hours: no slots") boolean closed, List<TrainingSlot> slots) { }
    public record TrainingSlot(Instant startsAt, @Schema(example = "08:30") String startsAtLocal, @Schema(example = "09:00") String endsAtLocal,
            @Schema(description = "Some ring is FREE («Qualsevol»)") boolean anyFree,
            @Schema(description = "MEMBER: FREE, startsAt > now and inside the window (R-09-04)") boolean bookable,
            @Schema(description = "Cell per ring id") Map<String, SlotCell> rings) { }
    public record SlotCell(SlotState state, @Schema(requiredMode = NOT_REQUIRED, nullable = true) SlotReason reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Own booking (OWN_TRAINING)") String bookingId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = STAFF) List<SlotOccupant> occupants,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = STAFF) SlotBlock block,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = STAFF) SlotClassSession classSession) { }
    public record SlotOccupant(String bookingId, String memberName, String dogName) { }
    public record SlotBlock(String id, @Schema(allowableValues = {"BLOCK", "RESERVATION"}) String kind,
            @Schema(allowableValues = {"MAINTENANCE", "PRIVATE_CLASS", "THERAPY", "PREPARATION", "ACTIVITY", "OTHER"}) String reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note, String createdByName) { }
    public record SlotClassSession(String id, String description) { }

    // ---- GET /me/training-summary
    public record TrainingSummary(List<EligibleDog> eligibleDogs,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Member.lastDogForTraining while eligible, else the first eligible dog") String defaultDogId,
            @Schema(allowableValues = {"DOG", "MEMBER"}) String limitUnit, WeekOpensAt weekOpensAt, TrainingWeek week, TrainingCounter counter,
            @Schema(description = "Future ACTIVE bookings still cancellable (R-09-10)") List<CancellableTraining> cancellableBookings,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) TrainingBookingBlock bookingBlock,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) InactivityWindow inactivity) { }
    public record EligibleDog(String id, String name, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Owner of a family-group dog") String ownerName, RightSource rightSource) { }
    public record WeekOpensAt(DayOfWeek dayOfWeek, @Schema(example = "20:00") String time) { }
    public record TrainingWeek(Instant start, Instant end, boolean current) { }
    public record TrainingCounter(int used, int limit, int remaining,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant resetsAt) { }
    public record CancellableTraining(String id, Instant startsAt, String ringName, Instant cancellableUntil) { }
    public record TrainingBookingBlock(String reason) { }
    public record InactivityWindow(LocalDate from, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate to) { }

    // ---- TrainingBooking
    public record TrainingImpersonation(String actorName) { }
    public record TrainingBooking(String id, String dogId, String dogName, String memberId, String ringId, String ringName, String slotId,
            Instant startsAt, Instant endsAt, @Schema(example = "08:30") String startsAtLocal, @Schema(example = "09:00") String endsAtLocal,
            LocalDate date, TrainingBookingState state, TrainingOrigin origin,
            @Schema(description = "startsAt - training.cancelThresholdMinutes") Instant cancellableUntil, Instant createdAt, TrainingCounter counter,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) TrainingImpersonation impersonation,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) TrainingCancelledBy cancelledBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) TrainingCancelReason cancelReason) { }
    public record MemberTrainingBookings(List<TrainingBooking> items) { }
    public record TrainingBookingListItem(String id, LocalDate date, Instant startsAt, @Schema(example = "08:30") String startsAtLocal,
            String ringId, String ringName, String memberId, String memberName, String dogId, String dogName,
            TrainingBookingState state, TrainingOrigin origin, Instant createdAt) { }

    // ---- Error details (CATALEG_ERRORS §3 rule 2)
    @Schema(description = "details of 409 TRAINING_LIMIT_REACHED")
    public record TrainingLimitReachedDetails(int limit, int used, Instant weekStart, Instant weekEnd, List<CancellableTraining> cancellableBookings) { }
    @Schema(description = "details of 409 SLOT_TAKEN")
    public record SlotTakenDetails(String ringId, Instant startsAt, SlotReason reason, List<String> freeRings) { }
    @Schema(description = "details of 422 SLOT_OUT_OF_WINDOW")
    public record SlotOutOfWindowDetails(LocalDate from, LocalDate to) { }
    @Schema(description = "details of 422 TRAINING_CANCEL_TOO_LATE")
    public record TrainingCancelTooLateDetails(int thresholdMinutes, int minutesBefore) { }
    @Schema(description = "details of 422 RING_HAS_BOOKINGS")
    public record RingHasBookingsDetails(List<SlotOccupant> bookings) { }
}
