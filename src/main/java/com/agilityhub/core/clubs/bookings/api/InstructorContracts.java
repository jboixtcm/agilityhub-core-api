package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort.AttendanceStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S10 §6 wire forms of screens 20, 21, 22/D13, 25 and D12, field by field from the JSON examples. Instants are UTC;
 * `*Local`, `startTime`/`endTime` and `rows[]` are club-local `HH:mm` (or `YYYY-MM-DDTHH:mm` for `startsAtLocal`).
 * Nullable fields are optional and omitted when null; module-dependent fields are absent with the module off (S10 §9).
 */
public final class InstructorContracts {
    private InstructorContracts() { }
    static final String HHMM = "Club-local time HH:mm";
    static final String CLASS_STATES = "ACTIVE · FINISHED · CANCELLED (DRAFT never shown)";

    public enum WeekRelative { CURRENT, PAST, FUTURE }
    public enum WeekCellKind { CLASS, TRAINING, BLOCK }
    /** 22/D13 badges: «present» · «avisat» · «no presentat» · «anul·lada tard» · «sense marcar». */
    public enum LastClassState { PRESENT, NOTIFIED, NO_SHOW, CANCELLED_LATE, PENDING }
    public enum HistoryType { CLASS, TRAINING, ACTIVITY }
    public enum HistoryState { DONE, NO_SHOW, CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB }
    public enum HistoryDetailKind { BY_MEMBER, BY_MEMBER_IN_TIME, INSTRUCTOR_NOTICE, INSTRUCTOR_NOTICE_IN_TIME, BY_CLUB_ON_BEHALF, SYSTEM, BY_CLUB, NO_SHOW }

    public record InstructorRef(String id, String shortName) { }
    public record RingRef(String id, String name, String color) { }

    // ---- GET /instructor/day (20)
    public record InstructorDay(LocalDate date, String timeZone,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The caller's Instructor; an ADMIN without a profile gets the first by shortName (R-10-01); null when the club has none") String selectedInstructorId,
            @Schema(description = "Active instructors for the chip (no «Tot el club»)") List<InstructorRef> instructors,
            @Schema(description = "Seven day chips from today (assumption)") List<DayChip> days,
            @Schema(description = "Classes of the selected instructor on `date`") List<InstructorDayClass> classes,
            @Schema(description = "Every ring block of the day, whoever created it (R-10-01)") List<DayRingBlock> ringBlocks) { }
    public record DayChip(LocalDate date, boolean hasClasses) { }
    public record InstructorDayClass(String id, @Schema(description = HHMM) String startTime, @Schema(description = HHMM) String endTime,
            String displayDescription, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null for a class without ring") RingRef ring,
            @Schema(allowableValues = {"ACTIVE", "FINISHED", "CANCELLED"}, description = CLASS_STATES) String state, int booked, int capacity,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with WAITLIST («⏳ n»)") Integer waiting,
            @Schema(description = "capacity = 1") boolean individual, DayAttendance attendance) { }
    public record DayAttendance(AttendanceStatus status, int marked, int total) { }
    public record DayRingBlock(String id, String ringName, @Schema(description = HHMM) String fromLocal, @Schema(description = HHMM) String toLocal,
            @Schema(allowableValues = {"BLOCK", "RESERVATION"}) String kind,
            @Schema(allowableValues = {"MAINTENANCE", "PRIVATE_CLASS", "THERAPY", "PREPARATION", "ACTIVITY", "OTHER"}) String reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note, String createdByName) { }

    // ---- GET/PUT /class-sessions/{id}/attendance (21, D12)
    public record AttendanceSheet(SheetClassSession classSession, Sheet sheet,
            @Schema(description = "Live bookings (ACTIVE, PAYMENT_PENDING) plus the NOTIFIED ones, by bookedAt (R-10-02)") List<AttendanceRow> rows,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with WAITLIST") SheetWaitlist waitlist,
            @Schema(requiredMode = NOT_REQUIRED, description = "PUT response only: the bookingIds whose state changed (same key → same response)") List<String> applied) { }
    public record SheetClassSession(String id, LocalDate date, @Schema(description = HHMM) String startTime, @Schema(description = HHMM) String endTime,
            String displayDescription, @Schema(requiredMode = NOT_REQUIRED, nullable = true) RingRef ring,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«Marc, Neus» with classes.maxInstructorsPerClass > 1") String instructorName,
            @Schema(allowableValues = {"ACTIVE", "FINISHED", "CANCELLED"}, description = CLASS_STATES) String state, int capacity, int booked,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with WAITLIST") Integer waiting) { }
    public record Sheet(@Schema(description = "attendanceSummary.version: the optimistic lock of the PUT (R-10-04)") long version,
            @Schema(description = "PRESENT/NO_SHOW/PENDING allowed now (R-10-03)") boolean canMarkPresence,
            @Schema(description = "NOTIFIED allowed now (bookings.instructorLastMinuteNotice, now ≤ T1)") boolean canMarkNotice,
            @Schema(description = "T1: 23:59:59 local of classDate + attendance.editDays") Instant editableUntil,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant savedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String savedByName,
            @Schema(description = "messaging.noShowNoticeTime (HH:mm)") String noShowNoticeTime) { }
    public record AttendanceRow(String bookingId, String dogId, String dogName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Short-lived signed URL") String dogPhotoUrl,
            String memberId, String memberFirstName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Dog.handlerName: «{guia} + {gos}» = handlerName ?? memberFirstName (R-10-00)") String handlerName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null with levels.enabled = false") String levelCode,
            AttendanceState state,
            @JsonProperty("final") @Schema(description = "A saved NOTIFIED row is fixed (R-10-03)") boolean fixed,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant markedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String markedByName,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with TASKS") Integer pendingTasksCount,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only NOTIFIED (R-10-05)") AttendanceNotice notice,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only NO_SHOW (R-10-06)") NoShowNotice noShowNotice) { }
    public record AttendanceNotice(@Schema(description = HHMM) String atLocal, Instant at, boolean late,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null when afterClassEnd") Integer minutesBefore,
            boolean seatReleased, boolean waitlistNotified, boolean afterClassEnd,
            @Schema(description = "CANCELLED, CANCELLED_LATE, or ACTIVE when afterClassEnd") BookingState bookingState) { }
    public record NoShowNotice(@Schema(description = "Next messaging.noShowNoticeTime after the class date") Instant scheduledFor,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant queuedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "«avís ja enviat»") Instant sentAt) { }
    public record SheetWaitlist(WaitlistMode mode,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "waitlist.fifoConfirmMinutes; only in FIFO") Integer fifoConfirmMinutes,
            List<SheetWaitlistEntry> entries) { }
    public record SheetWaitlistEntry(String entryId, String dogName, String memberFirstName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelCode, Instant joinedAt, WaitlistState state) { }
    public record AttendanceSaveRequest(@NotNull @PositiveOrZero @Schema(description = "sheet.version read by the caller") Long version,
            @NotEmpty @Valid @Schema(description = "Items equal to the current state are no-ops") List<AttendanceSaveItem> items) { }
    public record AttendanceSaveItem(@NotBlank String bookingId, @NotNull AttendanceState state) { }
    /** `details` of the PUT errors (CATALEG_ERRORS §3 rule 2): the whole current sheet on STALE_VERSION (R-10-04). */
    public record StaleAttendanceDetails(AttendanceSheet current) { }
    public record AttendanceBookingNotActiveDetails(String bookingId) { }
    public record AttendanceWindowClosedDetails(@Schema(description = "T1") Instant editableUntil) { }

    // ---- GET /attendances (universal list)
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record AttendanceListItem(String id, String bookingId, String classSessionId, LocalDate classDate, Instant classStartsAt,
            String dogId, String dogName, String memberId, String memberName, AttendanceState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant markedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String markedByName) { }

    // ---- GET /instructor/week (D12)
    public record InstructorWeek(WeekRange week, WeekFilters filters,
            @Schema(description = "Distinct start times (HH:mm) of the week's cells") List<String> rows, List<WeekCell> cells) { }
    public record WeekRange(LocalDate startDate, LocalDate endDate, WeekRelative relative) { }
    public record WeekFilters(@Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null = «Tots»") String instructorId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null = «Totes les pistes»") String ringId,
            List<InstructorRef> instructors, List<RingRef> rings) { }
    @Schema(description = "CLASS: classId, displayDescription, ringColor, instructorName, booked, capacity, waiting (WAITLIST), state, attendanceStatus. "
            + "TRAINING (FREE_TRAINING only, half height of training.slotMinutes): who, trainingBookingId. BLOCK: blockId, reason, note, createdByName.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeekCell(LocalDate date, @Schema(description = HHMM) String time, @Schema(description = HHMM) String endTime, WeekCellKind kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED) String classId, @Schema(requiredMode = NOT_REQUIRED) String displayDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringColor, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String instructorName,
            @Schema(requiredMode = NOT_REQUIRED) Integer booked, @Schema(requiredMode = NOT_REQUIRED) Integer capacity,
            @Schema(requiredMode = NOT_REQUIRED, description = "CLASS with WAITLIST") Integer waiting,
            @Schema(requiredMode = NOT_REQUIRED, allowableValues = {"ACTIVE", "FINISHED", "CANCELLED"}) String state,
            @Schema(requiredMode = NOT_REQUIRED) AttendanceStatus attendanceStatus,
            @Schema(requiredMode = NOT_REQUIRED, description = "«{guia} + {gos}»") String who, @Schema(requiredMode = NOT_REQUIRED) String trainingBookingId,
            @Schema(requiredMode = NOT_REQUIRED) String blockId,
            @Schema(requiredMode = NOT_REQUIRED, allowableValues = {"MAINTENANCE", "PRIVATE_CLASS", "THERAPY", "PREPARATION", "ACTIVITY", "OTHER"}) String reason,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String note, @Schema(requiredMode = NOT_REQUIRED) String createdByName) { }

    // ---- GET /dogs/{id}/instructor-card (22, D13)
    public record InstructorCard(CardDog dog, CardMember member,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Absent with levels.enabled = false or no level") CardLevel level,
            CardMetrics metrics, @Schema(description = "At most 5, newest first (R-10-09)") List<LastClass> lastClasses,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with TASKS") InstructorNoteBlock instructorNote,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with TASKS") TasksBlock tasks,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with TASKS; never on /me/*") ObservationsBlock observations) { }
    public record CardDog(String id, String name, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String breed,
            @Schema(allowableValues = {"MALE", "FEMALE"}) String sex, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer ageYears,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String photoUrl,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "R-10-00: «{guia} + {gos}», plus «(abonat: …)» when it differs") String handlerName,
            String status) { }
    public record CardMember(String id, String firstName, String fullName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, allowableValues = {"MALE", "FEMALE", "OTHER"}) String gender, CardMemberStatus displayStatus) { }
    public record CardMemberStatus(@Schema(description = "S03 derived status (ACTIVE, LEAVING, …)") String kind,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate date) { }
    public record CardLevel(String code, @Schema(description = "Level.name in the request locale") String name,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Dog.levelAssignedAt («fa n mesos»)") Instant assignedAt) { }
    public record CardMetrics(@Schema(description = "Product constant 30") int windowDays,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "round(100 · present / classesCounted); null when 0 (R-10-08)") Integer attendancePct,
            int present, int noShow, int notified, int cancelledLate, int classesCounted,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with FREE_TRAINING") Integer trainingsCount,
            @Schema(requiredMode = NOT_REQUIRED, description = "Only with FREE_TRAINING; one decimal") Double trainingsPerWeek) { }
    public record LastClass(String bookingId, LocalDate date, String displayDescription,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String instructorName, LastClassState displayState) { }
    public record CardAttachment(String id, String name, String mimeType, @Schema(description = "Short-lived signed URL") String url) { }
    public record InstructorNoteBlock(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String text,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant updatedAt, List<CardAttachment> attachments) { }
    public record TasksBlock(int pendingCount, int doneCount, @Schema(requiredMode = NOT_REQUIRED, nullable = true) TaskSummary latest) { }
    public record TaskSummary(String id, String text, Instant createdAt, String createdByName) { }
    public record ObservationsBlock(@Schema(requiredMode = NOT_REQUIRED, nullable = true) String text,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant updatedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String updatedByName, List<CardAttachment> attachments,
            @Schema(description = "Dog version for PUT /dogs/{id}/observations") long version) { }

    // ---- GET /me/history (25)
    public record MemberHistory(int monthsVisible, @Schema(description = "Start of the local day − history.monthsVisible months") LocalDate from,
            @Schema(description = "More than one accessible dog") boolean showDog, List<HistoryDog> dogs,
            @Schema(description = "CLASS always; TRAINING with FREE_TRAINING; ACTIVITY with ACTIVITIES") List<HistoryType> types,
            @Schema(description = "startsAt desc; no paging (at most 500)") List<HistoryItem> items) { }
    public record HistoryDog(String id, String name, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelCode, boolean own,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Owner of a family-group dog") String ownerFirstName) { }
    public record HistoryItem(HistoryType type, String id, LocalDate date,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Club-local YYYY-MM-DDTHH:mm") String startsAtLocal, String title,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String dogId, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String dogName,
            HistoryState state, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Classes only: counts as done") Boolean counts,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "The front picks the sentence (R-10-14)") HistoryDetail detail) { }
    public record HistoryDetail(HistoryDetailKind kind, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant at,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = HHMM) String atLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "BY_CLUB: the club's adminText") String message) { }
}
