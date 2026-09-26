package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.shared.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S08 §6 wire forms (screens 03, 04, 06/29, 07). `*Local` fields are club-local `YYYY-MM-DDTHH:mm` strings;
 * instants are UTC. Nothing here is computed by the front: row states come from the back (R-08-03).
 */
public final class BookingContracts {
    private BookingContracts() { }
    static final String LOCAL = "Club-local date-time YYYY-MM-DDTHH:mm";

    public enum LimitUnit { DOG, MEMBER }
    public enum BookingWeek { CURRENT, NEXT, LATER }
    public enum ReservationType { CLASS, CLASS_WAITLIST, TRAINING, ACTIVITY }
    public enum ReservationState { CONFIRMED, PAYMENT_PENDING, WAITLISTED, REGISTERED }
    public enum BookableState { BOOKABLE, WAITLIST_OPEN, WAITLIST_FULL, FULL, WEEKLY_LIMIT_DONE, NOT_YET_OPEN, PACK_EMPTY, NOT_BOOKABLE }
    public enum NotBookableReason { BLOCKED, INACTIVITY, LEAVING }
    public enum PackState { ACTIVE, EXPIRING, EXPIRED, EMPTY }
    public enum NotSelectableReason { DONE, LATE_WINDOW }
    /** 07 labels: confirmada · feta · no presentat · anul·lada · anul·lada tard · cancel·lada pel club · pendent de pagament. */
    public enum DisplayState { CONFIRMED, DONE, NO_SHOW, CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB, PAYMENT_PENDING }

    public record BookingImpersonation(String actorName) { }

    // ---- GET /me/home (03)
    public record MeHome(HomeMember member, List<HomeDog> dogs,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null = «Tots»") String selectedDogId,
            HomeLimits limits, @Schema(description = "Future rows only (endsAt > now), ordered by startsAt") List<ReservationRow> reservations,
            HomeHistory history, HomeNotifications notifications,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Present while an admin acts as the member") BookingImpersonation impersonation) { }
    public record HomeMember(String id, String firstName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, allowableValues = {"MALE", "FEMALE", "OTHER"}, description = "Null when never declared") String gender) { }
    public record HomeDog(String id, String name, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelName, boolean own,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Owner of a family-group dog") String ownerFirstName) { }
    public record HomeLimits(LimitUnit unit, WeekCount currentWeek, WeekCount nextWeek) { }
    public record WeekCount(int count, int max, @Schema(description = "bookingWeekKey YYYY-MM-DD (R-08-01)") String weekKey) { }
    public record ReservationRow(ReservationType type, String id, ReservationState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with «Tots» (no dogId); null when a dog is selected (T-08-12)") String dogName, String title,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant startsAt,
            @Schema(description = LOCAL) String startsAtLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = LOCAL) String endsAtLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Null until bookings.showInstructorHoursBefore (R-08-20)") String instructorName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant instructorVisibleAt) { }
    public record HomeHistory(int monthsVisible) { }
    public record HomeNotifications(int unreadCount) { }

    // ---- GET /me/bookable-classes (04)
    public record BookableClasses(BookableDog dog, List<HomeDog> dogs,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with PACKS and a PACK plan") PackCard pack,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with SINGLE_CLASS and a SINGLE_CLASS plan") SingleClassTerms singleClass,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) BookingBlockNotice bookingBlock,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Absent (null) without ACTIVITIES (S08 §9)") List<BookableActivity> activities, List<BookableClass> classes) { }
    public record BookableDog(String id, String name, @Schema(allowableValues = {"MALE", "FEMALE"}) String sex,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String levelName, boolean own) { }
    public record PackCard(String planName, int sessionsTotal, int consumed, int available, LocalDate expiresOn, PackState state) { }
    public record SingleClassTerms(ChargeMode chargeMode, Money pricePerClass) { }
    public record BookingBlockNotice(String reason) { }
    public record BookableActivity(String id, String title, @Schema(description = LOCAL) String startsAtLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Integer freeSeats) { }
    public record BookableClass(String id, @Schema(description = LOCAL) String startsAtLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = LOCAL) String endsAtLocal, String description,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringColor, BookingWeek week, BookableState state,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with state NOT_BOOKABLE") NotBookableReason notBookableReason,
            int freeSeats,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with WAITLIST") Integer waiting,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "waitlist.maxPerClass; only with WAITLIST") Integer waitlistMax,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with state NOT_YET_OPEN") Instant opensAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with SINGLE_CLASS") Money price) { }

    // ---- POST /seat-holds (06/29)
    public record SeatHoldResponse(String id, String classSessionId, String dogId, Instant expiresAt,
            @Schema(description = "Countdown = expiresAt - serverNow, immune to a skewed device clock") Instant serverNow,
            @Schema(description = "bookings.seatHoldSeconds") int holdSeconds, HoldClassSession classSession, HoldDog dog, LimitStatus limit,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with SINGLE_CLASS") HoldPayment payment,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with PACKS") PackBalance pack) { }
    public record HoldClassSession(@Schema(description = LOCAL) String startsAtLocal, @Schema(description = LOCAL) String endsAtLocal,
            String description, List<String> levelNames,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringColor) { }
    public record HoldDog(String id, String name, @Schema(allowableValues = {"MALE", "FEMALE"}) String sex) { }
    public record LimitStatus(boolean reached, LimitUnit unit, BookingWeek week, int count, int max,
            @Schema(description = "R-08-09: ACTIVE bookings of that week cancellable in time") List<SwapOption> swappable,
            List<NotSelectable> notSelectable) { }
    public record SwapOption(String bookingId, @Schema(description = LOCAL) String startsAtLocal, String description,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName) { }
    public record NotSelectable(String bookingId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = LOCAL) String startsAtLocal,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String description, NotSelectableReason reason) { }
    public record HoldPayment(ChargeMode mode, Money price) { }
    public record PackBalance(int available, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate expiresOn) { }

    // ---- Booking (POST /bookings, claim, GET /bookings/{id}, cancellation)
    @Schema(description = "S08 booking. INSTRUCTOR and ADMIN read the same form; PAYMENT_PENDING carries checkoutUrl.")
    public record Booking(String id, BookingState state, BookingOrigin origin, String classSessionId, String dogId,
            @Schema(description = "Owner of the dog (the member + dog unit)") String memberId,
            BookingClassSession classSession, Instant bookedAt, BookedBy bookedBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String swapFromBookingId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with PACKS") PackBalance pack,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "Only with SINGLE_CLASS") BookingCharge charge,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, format = "uri", description = "Only while PAYMENT_PENDING (PAY_TO_BOOK)") String checkoutUrl,
            CalendarLinks calendarLinks,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) BookingCancellation cancellation,
            @Schema(requiredMode = NOT_REQUIRED, description = "Derived label for 07; added by GET /bookings/{id}") DisplayState displayState) { }
    public record BookingClassSession(@Schema(description = LOCAL) String startsAtLocal, @Schema(description = LOCAL) String endsAtLocal,
            String description, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String instructorName,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant instructorVisibleAt) { }
    public record BookedBy(String displayName, @Schema(description = "True when booked by the club (BACKOFFICE)") boolean viaClub) { }
    public record BookingCharge(ChargeMode mode, Money price, @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant paidAt) { }
    public record CalendarLinks(@Schema(format = "uri") String google, @Schema(format = "uri") String outlook,
            @Schema(format = "uri", description = "Signed token valid until classEndsAt") String ics) { }
    public record BookingCancellation(Instant at, String byDisplayName, ActorRole byRole, boolean late,
            @Schema(description = "Negative once the class has started") int minutesBefore,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String message) { }
    public record MemberBookings(List<Booking> items) { }
    @com.agilityhub.core.shared.application.contract.SparseListItem
    public record BookingListItem(String id, BookingState state, BookingOrigin origin, String classSessionId, Instant classStartsAt,
            String bookingWeekKey, String dogId, String dogName, String memberId, String memberName, Instant bookedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean late) { }
    /** A row of `GET /class-sessions/{id}/bookings`: always whole, unlike the sparse {@link BookingListItem} of the list (E5-T22). */
    public record ClassBookingItem(String id, BookingState state, BookingOrigin origin, String classSessionId, Instant classStartsAt,
            String bookingWeekKey, String dogId, String dogName, String memberId, String memberName, Instant bookedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Boolean late) { }
    public record ClassBookings(List<ClassBookingItem> items) { }

    // ---- Waitlist
    public record WaitlistEntry(String id, WaitlistState state, String classSessionId, String dogId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String dogName, String memberId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "FIFO order (R-08-14); null when waitlist.mode = ALL_AT_ONCE") Integer position, Instant joinedAt,
            BookingClassSession classSession,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant notifiedAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "FIFO only (R-08-14)") Instant confirmBy,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) String bookingId,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true) WaitlistCancelReason cancelReason) { }
    public record ClassWaitlist(List<WaitlistEntry> items) { }

    // ---- Error details (CATALEG_ERRORS §3 rule 2)
    @Schema(description = "details of 409 BOOKING_LIMIT_REACHED")
    public record BookingLimitReachedDetails(LimitUnit unit, BookingWeek week, int limit, int current, List<SwapOption> swappable,
            List<NotSelectable> notSelectable, @Schema(description = "Start of the next booking week") Instant nextBookableAt) { }
    @Schema(description = "details of 409 CLASS_FULL; heldOnly = a live hold of another dog takes the last seat")
    public record ClassFullDetails(boolean heldOnly) { }
    @Schema(description = "details of 422 NOT_YET_OPEN")
    public record NotYetOpenDetails(Instant opensAt) { }
    public enum WaitlistLimitScope { CLASS, DOG_WEEK }
    @Schema(description = "details of 409 WAITLIST_LIMIT")
    public record WaitlistLimitDetails(WaitlistLimitScope scope) { }
    @Schema(description = "details of 422 INACTIVITY_PERIOD")
    public record InactivityPeriodDetails(LocalDate from, @Schema(requiredMode = NOT_REQUIRED, nullable = true) LocalDate to) { }
}
