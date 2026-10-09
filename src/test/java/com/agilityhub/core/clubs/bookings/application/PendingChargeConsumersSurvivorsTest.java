package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.payments.application.PendingChargeService;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PendingChargeConsumers} (S10 R-10-07, S12 R-12-25; T-10-13): `AttendanceMarked` and a
 * late `BookingCancelled` of a `CHARGE_ON_ATTENDANCE` booking reach `payments` with the booking's view (dog name,
 * club-local class date) and the states as strings; a live charge is stamped on the booking, a voided one cleared;
 * bookings of another mode or without a charge, and in-time cancellations, are left alone; both handlers delegate.
 */
class PendingChargeConsumersSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-07T17:05:00Z");
    /** Wednesday 7 October, 18:00 Madrid (16:00Z): the club-local class date is 2026-10-07. */
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final PendingChargeService.ChargedBooking VIEW =
            new PendingChargeService.ChargedBooking("booking-1", "member-laura", "dog-duna", "Duna", LocalDate.parse("2026-10-07"));

    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final PendingChargeService charges = mock(PendingChargeService.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final PendingChargeConsumers consumers = new PendingChargeConsumers(bookings, census, charges, configs);

    @BeforeEach void setUp() {
        var club = new ClubConfig.ClubView("club-a", "club-a", "Club Agility Example", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null,
                "ACTIVE", null);
        when(configs.get("club-a")).thenReturn(new ClubConfig(club, Map.of(), Set.of(Module.BILLING, Module.SINGLE_CLASS), null, Map.of()));
        when(census.dog("dog-duna")).thenReturn(Optional.of(new BookingMemberAccess.Dog("dog-duna", "Duna", "FEMALE", "member-laura", null, "ACTIVE")));
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking("booking-1", ChargeMode.CHARGE_ON_ATTENDANCE)));
    }

    // --- AttendanceMarked (lines 38, 43, 55-56, 61, 66-67, 70) --------------------------------------------------------------

    @Test void T_10_13_aPresentMarkChargesTheBookingAndStampsItsLiveCharge() {
        when(charges.attendance(VIEW, "PRESENT", "PENDING")).thenReturn(Optional.of(new PendingChargeService.BookingCharge("charge-1", true)));

        consumers.attendanceMarked(marked("booking-1", "PRESENT", "PENDING"));

        verify(charges).attendance(VIEW, "PRESENT", "PENDING");
        verify(bookings).stampChargeRef("booking-1", "charge-1");
        verify(bookings, never()).clearChargeRef(any(), any());
    }

    @Test void T_10_13_aMarkBackToPendingClearsTheVoidedCharge() {
        when(charges.attendance(VIEW, "PENDING", "PRESENT")).thenReturn(Optional.of(new PendingChargeService.BookingCharge("charge-1", false)));

        consumers.attendanceMarked(marked("booking-1", "PENDING", "PRESENT"));

        verify(bookings).clearChargeRef("booking-1", "charge-1");
        verify(bookings, never()).stampChargeRef(any(), any());
    }

    @Test void T_10_13_bookingsOfAnotherModeOrWithoutAChargeAreLeftAlone() {
        when(bookings.findById("booking-2")).thenReturn(Optional.of(booking("booking-2", ChargeMode.PAY_TO_BOOK)));
        when(bookings.findById("booking-3")).thenReturn(Optional.of(booking("booking-3", null)));

        consumers.attendanceMarked(marked("booking-2", "PRESENT", "PENDING"));
        consumers.attendanceMarked(marked("booking-3", "PRESENT", "PENDING"));

        verifyNoInteractions(charges);
        verify(bookings, never()).stampChargeRef(any(), any());
    }

    // --- BookingCancelled (lines 46, 50) -----------------------------------------------------------------------------------

    @Test void E11_T06_aLateCancellationChargesTheBookingAndAnInTimeOneIsLeftAlone() {
        when(charges.cancellation(VIEW, true)).thenReturn(Optional.of(new PendingChargeService.BookingCharge("charge-1", true)));

        // An in-time cancellation (two days ahead) of one booking of Duna, and a late one (an hour before) of another.
        when(bookings.findById("booking-0")).thenReturn(Optional.of(booking("booking-0", ChargeMode.CHARGE_ON_ATTENDANCE, BookingState.CANCELLED)));
        consumers.bookingCancelled(cancelled("booking-0", false));
        verifyNoInteractions(charges);

        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking("booking-1", ChargeMode.CHARGE_ON_ATTENDANCE, BookingState.CANCELLED_LATE)));
        consumers.bookingCancelled(cancelled("booking-1", true));
        verify(charges).cancellation(VIEW, true);
        verify(bookings).stampChargeRef("booking-1", "charge-1");
    }

    // --- the outbox handlers (lines 77, 83) --------------------------------------------------------------------------------

    @Test void T_10_13_bothHandlersDelegateTheirEventToTheConsumers() throws Exception {
        var delegate = mock(PendingChargeConsumers.class);
        var handlers = new PendingChargeConsumers.Handlers();
        var marked = marked("booking-1", "PRESENT", "PENDING");
        var cancelled = cancelled("booking-1", true);

        handlers.attendanceMarked(delegate).handle("event-1", marked);
        handlers.bookingCancelled(delegate).handle("event-2", cancelled);

        verify(delegate).attendanceMarked(marked);
        verify(delegate).bookingCancelled(cancelled);
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    /** S10 `AttendanceMarked` as the outbox delivers it (AttendanceSheetService.java:98-100): states as strings. */
    static AttendanceEvent marked(String bookingId, String state, String previousState) {
        var payload = new HashMap<String, Object>();
        payload.put("bookingId", bookingId); payload.put("classSessionId", "class-a"); payload.put("dogId", "dog-duna"); payload.put("memberId", "member-laura");
        payload.put("state", state); payload.put("previousState", previousState); payload.put("by", Map.of("accountId", "account-neus", "role", "INSTRUCTOR"));
        return new AttendanceEvent(AttendanceEvent.Kind.AttendanceMarked, "club-a", "attendance-" + bookingId, NOW, payload, "account-neus", null,
                DomainEvent.Origin.INSTRUCTOR);
    }

    /** BookingCancellationService#cancel's `BookingCancelled` of Laura's own cancellation (BookingCancellationService.java:140-141). */
    static BookingEvent cancelled(String bookingId, boolean late) {
        int minutesBefore = late ? 60 : 2880;
        return new BookingEvent(BookingEvent.Kind.BookingCancelled, "club-a", bookingId, STARTS.minusSeconds(minutesBefore * 60L),
                Map.of("bookingId", bookingId, "by", "MEMBER", "late", late, "minutesBefore", minutesBefore, "origin", "APP", "reason", "MEMBER"),
                "account-laura", null, DomainEvent.Origin.APP);
    }

    static Booking booking(String id, ChargeMode mode) { return booking(id, mode, BookingState.ACTIVE); }

    /**
     * A booking of class-a (booking-2 is Marc's Nala, booking-3 Eva's Kira, every other one Laura's Duna: a dog has one live
     * booking per class): a CHARGE_ON_ATTENDANCE charge as created (BookingConfirmationService.java:77), a PAY_TO_BOOK
     * one paid through its checkout session (:85, :176); a CANCELLED/CANCELLED_LATE one carries Laura's cancellation (two days or
     * one hour before the class).
     */
    static Booking booking(String id, ChargeMode mode, BookingState state) {
        var booked = STARTS.minusSeconds(5 * 86_400);
        var charge = mode == null ? null : new Booking.Charge(mode, new Money(1200L, "EUR"), null, mode == ChargeMode.PAY_TO_BOOK ? "cs_" + id : null, null,
                mode == ChargeMode.PAY_TO_BOOK ? booked.plusSeconds(300) : null, null);
        boolean late = state == BookingState.CANCELLED_LATE, cancelled = late || state == BookingState.CANCELLED;
        Integer minutesBefore = cancelled ? (late ? 60 : 2880) : null;
        Instant cancelledAt = cancelled ? STARTS.minusSeconds(minutesBefore * 60L) : null;
        String owner = switch (id) { case "booking-2" -> "marc"; case "booking-3" -> "eva"; default -> "laura"; };
        String dog = switch (owner) { case "marc" -> "dog-nala"; case "eva" -> "dog-kira"; default -> "dog-duna"; };
        return new Booking(id, "club-a", "class-a", dog, "member-" + owner, state, BookingOrigin.APP, booked,
                new Booking.Actor("account-" + owner, null, owner), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                cancelledAt, cancelled ? new Booking.Canceller("account-" + owner, ActorRole.MEMBER, owner, null) : null,
                cancelled ? BookingCancelReason.MEMBER : null, null, cancelled ? late : null, minutesBefore,
                null, null, null, null, null,
                charge, null,
                cancelled ? 1L : 0L, booked, "account-" + owner, cancelled ? cancelledAt : booked, "account-" + owner);
    }
}
