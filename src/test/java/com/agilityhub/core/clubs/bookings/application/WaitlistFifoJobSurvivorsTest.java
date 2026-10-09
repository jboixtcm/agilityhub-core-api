package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistCancelReason;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobRunRecorder;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistFifoJob} (S15 P6, S08 R-08-14; T-08-21, T-08-34): the expiry takes the class's
 * seat lock before it re-reads the entry, and an offer claimed between the plan and the item is left alone.
 */
class WaitlistFifoJobSurvivorsTest {
    static final Instant AT = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final BookingCounters counters = mock(BookingCounters.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final Clock clock = Clock.fixed(AT.plusSeconds(2), ZoneOffset.UTC);
    final WaitlistFifoJob job = new WaitlistFifoJob(waitlist, locks, transitions, counters, publisher, clock);
    final JobContext context = new JobContext("club-a", ZoneId.of("Europe/Madrid"), AT, LocalDate.parse("2026-10-05"), false,
            mock(ClubConfig.class), mock(JobRunRecorder.class), "run-1");
    final JobItem item = new JobItem("WaitlistEntry", "entry-1", "EXPIRE", Map.of("entryId", "entry-1", "position", 1));
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { tenant = TenantContext.open("club-a"); }
    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_21_anExpiryTakesTheSeatLockBeforeReReadingTheEntry() {
        var due = entry(WaitlistState.NOTIFIED, AT.minusSeconds(60));
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(due));

        var effect = job.apply(context, item);

        assertThat(effect.action()).isEqualTo("EXPIRE");
        var order = inOrder(waitlist, locks, transitions);
        order.verify(waitlist).findById("entry-1");
        order.verify(locks).lock("class-a");
        order.verify(waitlist).findById("entry-1");
        order.verify(transitions).expire(due, clock.instant());
    }

    @Test void T_08_34_anOfferLeftBetweenThePlanAndTheItemIsNotInScope() {
        // Planned as NOTIFIED past confirmBy; Eva left the waiting list (WaitlistService#leave, allowed while NOTIFIED whatever
        // confirmBy says) before the item ran. A claim cannot happen there: past confirmBy it is WAITLIST_OFFER_EXPIRED.
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(entry(WaitlistState.CANCELLED, AT.minusSeconds(60))));

        var effect = job.apply(context, item);

        assertThat(effect.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(effect.counters()).isEmpty();
        verify(transitions, never()).expire(any(), any());
        verifyNoInteractions(counters, publisher);
    }

    /** Eva's FIFO offer for Kira (N-15 delivered); a CANCELLED one was left by Eva a second ago (WaitlistTransitions#cancel keeps the offer). */
    static WaitlistEntry entry(WaitlistState state, Instant confirmBy) {
        var joined = AT.minusSeconds(7200); var notifiedAt = confirmBy.minusSeconds(1800);
        boolean left = state == WaitlistState.CANCELLED; var leftAt = AT.plusSeconds(1);
        return new WaitlistEntry("entry-1", "club-a", "class-a", "dog-kira", "member-eva", "account-eva", joined, state, 1, notifiedAt, confirmBy,
                notifiedAt, null, left ? leftAt : null, left ? WaitlistCancelReason.MEMBER : null, STARTS, "2026-10-04", left ? 2L : 1L, joined,
                "account-eva", left ? leftAt : notifiedAt, left ? "account-eva" : null);
    }
}
