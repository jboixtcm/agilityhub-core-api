package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistTransitions} (S08 R-08-13, R-08-14; T-08-20, T-08-21): the last seat taken
 * sends the open ALL_AT_ONCE offers back to ACTIVE and returns them, every write is a compare-and-set that bumps
 * `version`, and a FIFO expiry returns the expired entry without its offer mark.
 */
class WaitlistTransitionsSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingEvents events = mock(BookingEvents.class);
    final WaitlistTransitions transitions = new WaitlistTransitions(context, waitlist, bookings, classes, events);

    @BeforeEach void setUp() {
        when(context.now()).thenReturn(NOW);
        when(waitlist.update(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test void T_08_20_theLastSeatTakenSendsTheOpenOffersBackToActiveWithTheirVersionBumped() {
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(context.waitlistMode()).thenReturn(WaitlistMode.ALL_AT_ONCE);
        // Capacity 2, now taken by two live bookings: no seat is left for the offer still open.
        when(classes.find("class-a")).thenReturn(Optional.of(new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"),
                "18:00", "19:00", STARTS, STARTS.plusSeconds(3600), "ring-1", List.of(), List.of("instructor-neus"), 2, 2, 2, false, null, 5L)));
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "dog-nala"), booking("booking-2", "dog-rock")));
        var notifiedAt = NOW.minusSeconds(900);
        // Eva's two dogs wait: Kira (joined first) has the open offer, Bruc is still queued.
        var offer = entry("entry-1", "dog-kira", 1, WaitlistState.NOTIFIED, notifiedAt, notifiedAt, 3L);
        var queued = entry("entry-2", "dog-bruc", 2, WaitlistState.ACTIVE, null, null, 0L);
        when(waitlist.live("class-a")).thenReturn(List.of(offer, queued));

        assertThat(transitions.demoteIfFull("class-a", BookingActor.system())).containsExactly("entry-1");

        var written = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(waitlist).update(written.capture(), eq(3L));
        assertThat(written.getValue().version()).isEqualTo(4L);
        assertThat(written.getValue().state()).isEqualTo(WaitlistState.ACTIVE);
        assertThat(written.getValue().notifiedAt()).isEqualTo(notifiedAt);
    }

    @Test void T_08_21_aFifoExpiryWritesTheExpiredEntryWithoutItsOfferMark() {
        var notifiedAt = NOW.minusSeconds(2400);
        var offer = new WaitlistEntry("entry-1", "club-a", "class-a", "dog-kira", "member-eva", "account-eva", NOW.minusSeconds(7200),
                WaitlistState.NOTIFIED, 1, notifiedAt, notifiedAt.plusSeconds(1800), notifiedAt, null, null, null, STARTS, "2026-10-04", 2L,
                NOW.minusSeconds(7200), "account-eva", notifiedAt, null);

        // Both callers ignore the returned entry (WaitlistService.java:159, WaitlistFifoJob.java:51): only the write is observable.
        transitions.expire(offer, NOW);

        var written = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(waitlist).update(written.capture(), eq(2L));
        assertThat(written.getValue().state()).isEqualTo(WaitlistState.EXPIRED);
        assertThat(written.getValue().offerNotifiedAt()).isNull();
        assertThat(written.getValue().notifiedAt()).isEqualTo(notifiedAt);
        assertThat(written.getValue().version()).isEqualTo(3L);
    }

    /** An ALL_AT_ONCE entry (no confirmBy): a queued one was last written by Eva's join, an offer by the system's notify. */
    static WaitlistEntry entry(String id, String dogId, int position, WaitlistState state, Instant notifiedAt, Instant offerNotifiedAt, long version) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", dogId, "member-eva", "account-eva", joined, state, position, notifiedAt, null, offerNotifiedAt,
                null, null, null, STARTS, "2026-10-04", version, joined, "account-eva", notifiedAt == null ? joined : notifiedAt,
                notifiedAt == null ? "account-eva" : null);
    }

    static Booking booking(String id, String dogId) {
        var booked = NOW.minusSeconds(86_400);
        return new Booking(id, "club-a", "class-a", dogId, "member-pau", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-pau", null, "Pau"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, "account-pau", booked, "account-pau");
    }
}
