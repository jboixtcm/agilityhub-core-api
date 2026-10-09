package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ClassReminders} (S15 R-15-14 P4, T-15-17): only an ACTIVE booking without
 * `reminderSentAt` is still to remind, and `markSent` reports whether the conditional mark was written.
 */
class ClassRemindersSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-07T14:50:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:50:00Z");

    final BookingRepository bookings = mock(BookingRepository.class);
    final ClassReminders reminders = new ClassReminders(bookings);

    @Test void T_15_17_onlyAnActiveBookingNotRemindedYetIsCurrent() {
        stub(booking("booking-due", BookingState.ACTIVE, null));
        stub(booking("booking-reminded", BookingState.ACTIVE, NOW.minusSeconds(60)));
        stub(booking("booking-cancelled", BookingState.CANCELLED, null));

        assertThat(reminders.current("booking-due"))
                .contains(new ClassReminders.Reminder("booking-due", "member-laura", "dog-duna", STARTS, NOW.minusSeconds(86_400)));
        assertThat(reminders.current("booking-reminded")).isEmpty();
        assertThat(reminders.current("booking-cancelled")).isEmpty();
    }

    @Test void T_15_17_markSentReportsWhetherTheMarkWasWritten() {
        when(bookings.markReminderSent("booking-due", STARTS, NOW)).thenReturn(true);
        // Another run (or a class moved since the read) already wrote it: the conditional update matches nothing.
        when(bookings.markReminderSent("booking-reminded", STARTS, NOW)).thenReturn(false);

        assertThat(reminders.markSent("booking-due", STARTS, NOW)).isTrue();
        assertThat(reminders.markSent("booking-reminded", STARTS, NOW)).isFalse();
    }

    private void stub(Booking b) { when(bookings.findById(b.id())).thenReturn(Optional.of(b)); }

    /** Three bookings of class-a, each of another dog (a dog has one live booking per class): Laura's Duna, Marc's Nala, Eva's Kira. */
    private static Booking booking(String id, BookingState state, Instant reminderSentAt) {
        var booked = NOW.minusSeconds(86_400);
        boolean cancelled = state == BookingState.CANCELLED;
        String owner = switch (id) { case "booking-due" -> "laura"; case "booking-reminded" -> "marc"; default -> "eva"; };
        String dog = switch (owner) { case "laura" -> "dog-duna"; case "marc" -> "dog-nala"; default -> "dog-kira"; };
        Instant cancelledAt = cancelled ? NOW.minusSeconds(43_200) : null;
        // BookingRepository#markReminderSent sets only `reminderSentAt` (no version bump, no audit fields).
        return new Booking(id, "club-a", "class-a", dog, "member-" + owner, state, BookingOrigin.APP, booked,
                new Booking.Actor("account-" + owner, null, owner), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                cancelledAt, cancelled ? new Booking.Canceller("account-" + owner, ActorRole.MEMBER, owner, null) : null,
                cancelled ? BookingCancelReason.MEMBER : null, null, cancelled ? Boolean.FALSE : null, cancelled ? 840 : null,
                null, null, null, null, null, null, reminderSentAt,
                cancelled ? 1L : 0L, booked, "account-" + owner, cancelled ? cancelledAt : booked, "account-" + owner);
    }
}
