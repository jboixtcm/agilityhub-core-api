package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.events.SchedulerEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistConsumers} (S08 §7, R-08-13/14; T-08-20, T-08-21): the two consumer beans are
 * real handlers that open the event's club and hand the class over to {@link WaitlistService}.
 */
class WaitlistConsumersSurvivors3Test {
    static final Instant AT = Instant.parse("2026-10-05T08:00:00Z");

    final WaitlistService waitlist = mock(WaitlistService.class);
    final WaitlistEntryRepository entries = mock(WaitlistEntryRepository.class);
    final WaitlistConsumers consumers = new WaitlistConsumers();

    /** Outbox deliveries run without a request tenant: the handler opens the event's club itself. */
    @BeforeEach void noTenant() { TenantContext.clear(); }

    @Test void T_08_20_theSeatReleasedConsumerOffersTheReleasedSeatsOfTheClassInTheEventsClub() throws Exception {
        var tenants = new ArrayList<String>();
        doAnswer(inv -> { tenants.add(TenantContext.current()); return 2; }).when(waitlist).offerSeats("class-a", 2);
        var released = new BookingEvent(BookingEvent.Kind.SeatReleased, "club-a", "class-a", AT,
                Map.of("classId", "class-a", "freeSeats", 2, "notifyWaitlist", true), "account-laura", null, DomainEvent.Origin.APP);

        var handler = consumers.seatReleased(waitlist);
        assertThat(handler.eventType()).isEqualTo("SeatReleased");
        assertThat(handler.eventClass()).isEqualTo(BookingEvent.class);
        handler.handle("event-1", released);

        verify(waitlist).offerSeats("class-a", 2);
        assertThat(tenants).containsExactly("club-a");
        assertThat(TenantContext.current()).isNull();
    }

    @Test void T_08_21_theWaitlistExpiredConsumerOffersTheNextEntryOfTheExpiredOnesClassInTheEventsClub() throws Exception {
        var tenants = new ArrayList<String>();
        doAnswer(inv -> { tenants.add(TenantContext.current()); return 1; }).when(waitlist).offerNext("class-a", "entry-1");
        var expired = new SchedulerEvent(SchedulerEvent.Kind.WaitlistExpired, "club-a", "entry-1", AT,
                Map.of("entryId", "entry-1", "classId", "class-a"), null, null, DomainEvent.Origin.SYSTEM);

        var handler = consumers.waitlistExpired(waitlist, entries);
        assertThat(handler.eventType()).isEqualTo("WaitlistExpired");
        assertThat(handler.eventClass()).isEqualTo(SchedulerEvent.class);
        handler.handle("event-2", expired);

        verify(waitlist).offerNext("class-a", "entry-1");
        assertThat(tenants).isEqualTo(List.of("club-a"));
        verifyNoInteractions(entries);
        assertThat(TenantContext.current()).isNull();
    }
}
