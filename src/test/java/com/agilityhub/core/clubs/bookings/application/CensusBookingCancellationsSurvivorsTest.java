package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.ports.LifecycleCancellation;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link CensusBookingCancellations} (S13 leave/inactivity through the census port; S08 R-08-07
 * lock order): a cancelling run takes each class's seat lock and then locks the class before re-reading it; a preview
 * only reads the class and locks nothing.
 */
class CensusBookingCancellationsSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final ClassSessionBookingAccess.Session SESSION = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"),
            "18:00", "19:00", STARTS, STARTS.plusSeconds(3600), "ring-1", List.of("level-1"), List.of("instructor-neus"), 3, 2, 0, false, null, 3L);
    static final Booking BOOKING = booking();

    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final WaitlistService waiting = mock(WaitlistService.class);
    final BookingContext context = mock(BookingContext.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final CensusBookingCancellations port = new CensusBookingCancellations(bookings, waitlist, cancellations, waiting, context);

    @BeforeEach void setUp() {
        // The three collaborators Spring injects into fields.
        ReflectionTestUtils.setField(port, "classes", classes);
        ReflectionTestUtils.setField(port, "locks", locks);
        ReflectionTestUtils.setField(port, "transactions", transactions);
        when(context.enabled(Module.WAITLIST)).thenReturn(false);
        when(context.zone()).thenReturn(MADRID);
        when(context.now()).thenReturn(NOW);
        when(bookings.liveForMember("member-laura")).thenReturn(List.of(BOOKING));
        when(bookings.require("booking-1")).thenReturn(BOOKING);
    }

    @Test void E11_T06_aLeaveCancellationTakesTheSeatLockThenLocksTheClass() {
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        when(classes.lock("class-a")).thenReturn(SESSION);

        var result = port.inside("member-laura", LocalDate.parse("2026-10-05"), null, true, true);

        assertThat(result).containsExactly(new LifecycleCancellation("CLASS", "booking-1", LocalDate.parse("2026-10-07")));
        var order = inOrder(locks, classes, cancellations);
        order.verify(locks).lock("class-a");
        order.verify(classes).lock("class-a");
        order.verify(cancellations).cancelBySystem("booking-1", BookingCancelReason.LEAVE);
        verify(classes, never()).require(any());
    }

    @Test void E11_T06_aPreviewReadsTheClassWithoutAnyLock() {
        when(classes.require("class-a")).thenReturn(SESSION);

        var result = port.inside("member-laura", LocalDate.parse("2026-10-05"), null, false, true);

        assertThat(result).containsExactly(new LifecycleCancellation("CLASS", "booking-1", LocalDate.parse("2026-10-07")));
        verify(locks, never()).lock(any());
        verify(classes, never()).lock(any());
        verify(transactions, never()).write(any(), any());
        verify(cancellations, never()).cancelBySystem(any(), any());
    }

    /** Laura's live booking of class-a (Wednesday 18:00 Madrid). */
    private static Booking booking() {
        var booked = NOW.minusSeconds(86_400);
        return new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                1L, booked, "account-laura", booked, "account-laura");
    }
}
