package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingNotificationFacts#stillNotified} (S11 R-11-11 `CLAIM_SEAT.enabled`,
 * read by `NotificationFeedService` for the feed; T-11-19): the action is enabled only while the entry is still NOTIFIED and,
 * in FIFO, its `confirmBy` is after now (ALL_AT_ONCE offers carry no `confirmBy`).
 */
class BookingNotificationFactsSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final Instant NOTIFIED_AT = NOW.minusSeconds(600);

    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingNotificationFacts facts = new BookingNotificationFacts(mock(BookingRepository.class), waitlist, mock(AttendanceRepository.class),
            mock(BookingMemberAccess.class), mock(ClassSessionBookingAccess.class), mock(BookingContext.class), mock(BookingViews.class),
            mock(IcuMessageSource.class), mock(WaitlistTransitions.class), mock(BookingTransactions.class), mock(SeatLockRepository.class));

    @Test void T_11_19_aClaimSeatActionIsEnabledOnlyWhileTheOfferIsOpen() {
        // FIFO offer (WaitlistRules#confirmBy: notifiedAt + 30 min) still open, and the same offer expired.
        stub(entry("entry-open", WaitlistState.NOTIFIED, NOTIFIED_AT.plusSeconds(1800)));
        stub(entry("entry-expired", WaitlistState.NOTIFIED, NOW.minusSeconds(1)));
        // ALL_AT_ONCE offer: no confirmBy.
        stub(entry("entry-all", WaitlistState.NOTIFIED, null));
        // Demoted back to ACTIVE (R-08-13) and consolidated entries keep notifiedAt but are no longer offers.
        stub(entry("entry-demoted", WaitlistState.ACTIVE, null));
        stub(entry("entry-consolidated", WaitlistState.CONSOLIDATED, NOTIFIED_AT.plusSeconds(1800)));

        assertThat(facts.stillNotified("entry-open", NOW)).isTrue();
        assertThat(facts.stillNotified("entry-all", NOW)).isTrue();
        assertThat(facts.stillNotified("entry-expired", NOW)).isFalse();
        assertThat(facts.stillNotified("entry-demoted", NOW)).isFalse();
        assertThat(facts.stillNotified("entry-consolidated", NOW)).isFalse();
        assertThat(facts.stillNotified("entry-unknown", NOW)).isFalse();
    }

    private void stub(WaitlistEntry e) { when(waitlist.findById(e.id())).thenReturn(Optional.of(e)); }

    /** Eva's entry for Kira in class-a, offered at NOTIFIED_AT. */
    private static WaitlistEntry entry(String id, WaitlistState state, Instant confirmBy) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", "dog-kira", "member-eva", "account-eva", joined, state, 1,
                NOTIFIED_AT, confirmBy, NOTIFIED_AT, state == WaitlistState.CONSOLIDATED ? "booking-9" : null, null, null, STARTS, "2026-10-04",
                2L, joined, "account-eva", NOTIFIED_AT, null);
    }
}
