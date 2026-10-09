package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.WaitlistCancelReason;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatHoldRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistService} added since the 5 October report (S08 R-08-16; T-08-19): the silent
 * cancellations of a member's live waiting-list entries by an inactivity period (`cancelForInactivity`, club-local class
 * dates inside `[from, to]`, `to` open when null) and by a leave (`cancelByMember(…, after)`, classes starting after the
 * leave). Neither has a production caller today (S13 cancels entry by entry through `cancelEntries`); the data are the
 * ones such a caller would pass. Pure unit tests: collaborators are mocks, the transaction runs its work inline.
 */
class WaitlistServiceSurvivors2Test {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final BookingChecks checks = mock(BookingChecks.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingEvents events = new BookingEvents(publisher, mock(AuditActorProvider.class), context);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingViews views = mock(BookingViews.class);
    final BookingConfirmationService confirmations = mock(BookingConfirmationService.class);
    final WaitlistService service = new WaitlistService(context, transactions, checks, locks, waitlist, bookings, holds, transitions, events,
            counters, census, classes, views, confirmations);

    /** Laura's live entries, as `liveForMember` returns them (by class start): Tuesday 6 to Saturday 10 October, Madrid. */
    final WaitlistEntry tuesday = entry("entry-1", "class-1", "2026-10-06T16:00:00Z");   // Tue 6, 18:00
    final WaitlistEntry wednesday = entry("entry-2", "class-2", "2026-10-07T16:00:00Z"); // Wed 7, 18:00
    final WaitlistEntry friday = entry("entry-3", "class-3", "2026-10-09T17:00:00Z");    // Fri 9, 19:00
    final WaitlistEntry saturday = entry("entry-4", "class-4", "2026-10-10T08:00:00Z");  // Sat 10, 10:00
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        when(context.zone()).thenReturn(MADRID);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        var live = List.of(tuesday, wednesday, friday, saturday);
        when(waitlist.liveForMember("member-laura")).thenReturn(live);
        // Re-read under each class's lock: still live.
        live.forEach(e -> when(waitlist.findById(e.id())).thenReturn(Optional.of(e)));
    }

    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_19_aClosedInactivityPeriodCancelsSilentlyTheEntriesOfItsFirstToItsLastDayOnly() {
        // Inactivity from Wednesday 7 to Friday 9 October, both included.
        assertThat(service.cancelForInactivity("member-laura", LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-09"))).isEqualTo(2);

        verify(transitions).cancel(wednesday, WaitlistCancelReason.INACTIVITY, NOW, null);
        verify(transitions).cancel(friday, WaitlistCancelReason.INACTIVITY, NOW, null);
        verifyNoMoreInteractions(transitions);
        verify(locks).lock("class-2");
        verify(locks).lock("class-3");
        verifyNoMoreInteractions(locks);
    }

    @Test void T_08_19_anOpenInactivityPeriodCancelsSilentlyEveryEntryFromItsFirstDay() {
        // Inactivity from Friday 9 October with no end date.
        assertThat(service.cancelForInactivity("member-laura", LocalDate.parse("2026-10-09"), null)).isEqualTo(2);

        verify(transitions).cancel(friday, WaitlistCancelReason.INACTIVITY, NOW, null);
        verify(transitions).cancel(saturday, WaitlistCancelReason.INACTIVITY, NOW, null);
        verifyNoMoreInteractions(transitions);
    }

    @Test void T_08_19_aLeaveCancelsSilentlyOnlyTheEntriesOfClassesStartingAfterIt() {
        // Leave dated Friday 9 October: the instant before that day starts in Madrid, as the other contexts compute it.
        var after = LocalDate.parse("2026-10-09").atStartOfDay(MADRID).toInstant().minusNanos(1);

        assertThat(service.cancelByMember("member-laura", WaitlistCancelReason.MEMBER_LEFT, after)).isEqualTo(2);

        verify(transitions).cancel(friday, WaitlistCancelReason.MEMBER_LEFT, NOW, null);
        verify(transitions).cancel(saturday, WaitlistCancelReason.MEMBER_LEFT, NOW, null);
        verifyNoMoreInteractions(transitions);
    }

    static WaitlistEntry entry(String id, String classId, String startsAt) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", classId, "dog-duna", "member-laura", "account-laura", joined, WaitlistState.ACTIVE, 1, null, null, null,
                null, null, null, Instant.parse(startsAt), "2026-10-04", 0L, joined, "account-laura", joined, "account-laura");
    }
}
