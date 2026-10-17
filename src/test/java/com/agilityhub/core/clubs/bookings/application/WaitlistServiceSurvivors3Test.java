package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatHoldRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.bookings.application.WaitlistServiceSurvivorsTest.NOW;
import static com.agilityhub.core.clubs.bookings.application.WaitlistServiceSurvivorsTest.entry;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistService} (S08 R-08-15/16; T-08-19, T-08-22): leaving or claiming an entry that
 * cannot be read — before the lock or on the re-read under it — is `NOT_FOUND`, and nothing is written.
 */
class WaitlistServiceSurvivors3Test {
    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingConfirmationService confirmations = mock(BookingConfirmationService.class);
    final WaitlistService service = new WaitlistService(context, transactions, mock(BookingChecks.class), locks, waitlist, mock(BookingRepository.class),
            holds, transitions, new BookingEvents(publisher, mock(AuditActorProvider.class), context), counters, mock(BookingMemberAccess.class),
            mock(ClassSessionBookingAccess.class), mock(BookingViews.class), confirmations);

    final BookingActor laura = new BookingActor("account-laura", "member-laura", "Laura", null, BookingOrigin.APP, ActorRole.MEMBER);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
    }

    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_19_leavingAnUnknownEntryIsNotFoundAndTakesNoLock() {
        when(waitlist.findById("entry-9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(laura, "entry-9"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(locks, transitions, publisher, counters);
    }

    @Test void T_08_19_leavingAnEntryTheReReadUnderTheLockNoLongerFindsIsNotFoundAndWritesNothing() {
        var waiting = entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, null, null, 0L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(waiting), Optional.empty());

        assertThatThrownBy(() -> service.leave(laura, "entry-1"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(locks).lock("class-a");
        verifyNoInteractions(transitions, publisher, counters);
    }

    @Test void T_08_22_claimingAnUnknownEntryIsNotFoundAndConfirmsNothing() {
        when(waitlist.findById("entry-9")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.claim(laura, "entry-9", "hold-1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(locks, holds, confirmations);
    }

    @Test void T_08_22_aClaimWhoseEntryTheReReadUnderTheLockNoLongerFindsIsNotFoundAndConfirmsNothing() {
        // FIFO offer, 20 minutes left. Reads: the initial one, the claim lanes (WaitlistService#classes), then under the lock.
        var offer = entry("entry-1", "dog-duna", "member-laura", WaitlistState.NOTIFIED, NOW.minusSeconds(600), NOW.plusSeconds(1200), 1L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(offer), Optional.of(offer), Optional.empty());

        assertThatThrownBy(() -> service.claim(laura, "entry-1", "hold-1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(locks).lock("class-a");
        verify(waitlist, times(3)).findById("entry-1");
        verifyNoInteractions(holds, confirmations);
    }
}
