package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
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
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistFifoJob} (S15 P6, S08 R-08-14; T-08-34): a planned entry that can no longer
 * be read when its item runs is recorded as NOT_IN_SCOPE, with no lock, no expiry, no recount and no event.
 */
class WaitlistFifoJobSurvivors3Test {
    static final Instant AT = Instant.parse("2026-10-05T08:00:00Z");

    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final BookingCounters counters = mock(BookingCounters.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final WaitlistFifoJob job = new WaitlistFifoJob(waitlist, locks, transitions, counters, publisher, Clock.fixed(AT.plusSeconds(2), ZoneOffset.UTC));
    final JobContext context = new JobContext("club-a", ZoneId.of("Europe/Madrid"), AT, LocalDate.parse("2026-10-05"), false,
            mock(ClubConfig.class), mock(JobRunRecorder.class), "run-1");
    TenantContext.Scope tenant;

    @BeforeEach void setUp() { tenant = TenantContext.open("club-a"); }
    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_34_anItemWhoseEntryCannotBeReadIsNotInScopeAndTouchesNothing() {
        when(waitlist.findById("entry-1")).thenReturn(Optional.empty());

        var effect = job.apply(context, new JobItem("WaitlistEntry", "entry-1", "EXPIRE", Map.of("entryId", "entry-1", "position", 1)));

        assertThat(effect).isNotNull();
        assertThat(effect.action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(effect.detail()).isEmpty();
        assertThat(effect.counters()).isEmpty();
        verifyNoInteractions(locks, transitions, counters, publisher);
    }
}
