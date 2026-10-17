package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.application.ports.WaitlistConsolidationPort;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingLimits;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.domain.LimitUnit;
import com.agilityhub.core.clubs.bookings.domain.RelativeWeek;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatHold;
import com.agilityhub.core.clubs.bookings.persistence.SeatHoldRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingConfirmationService} (S08 R-08-08, R-08-18; T-08-15, T-08-24): the
 * confirmation runs the ALREADY_BOOKED check again under the seat lock, and both PAY_TO_BOOK writes (the kept checkout URL
 * and the settled payment) store the booking with its version bumped by exactly one (the compare-and-set of
 * BookingRepository#update expects the stored version next time). Collaborators are mocks, the transaction runs inline and
 * the events go through a real {@link BookingEvents}.
 */
class BookingConfirmationServiceSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final BookingChecks checks = mock(BookingChecks.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final SingleClassChargePort charges = mock(SingleClassChargePort.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingEvents events = new BookingEvents(publisher, mock(AuditActorProvider.class), context);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingConfirmationService service = new BookingConfirmationService(context, transactions, checks, locks, holds, bookings,
            mock(BookingCancellationService.class), mock(PackBalancePort.class), mock(WaitlistConsolidationPort.class), charges,
            mock(BookingMemberAccess.class), events, counters, mock(BookingAudit.class), mock(BookingViews.class), mock(SeatHoldService.class));

    final BookingActor actor = new BookingActor("account-laura", "member-laura", "Laura", null, BookingOrigin.APP, ActorRole.MEMBER);
    final SeatHold hold = new SeatHold("hold-1", "club-a", "class-a", "dog-duna", "member-laura", "account-laura", null, NOW.minusSeconds(60),
            NOW.plusSeconds(240));
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold));
        when(bookings.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        when(bookings.update(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach void tearDown() { tenant.close(); }

    @Test void T_08_15_theConfirmationChecksAgainThatTheDogIsNotBookedYet() {
        // R-08-08: every check of the hold runs again inside the confirmation transaction; Duna got a live booking of class-a meanwhile.
        var subject = stubSubject();
        doThrow(new ApiException(ErrorCode.ALREADY_BOOKED)).when(checks).notBookedYet(subject);

        assertThatThrownBy(() -> service.confirm(actor, "hold-1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ALREADY_BOOKED));
        verify(locks).lock("class-a");
        verify(bookings, never()).insert(any());
        verify(holds, never()).deleteById(any());
        verifyNoInteractions(publisher, counters);
    }

    @Test void T_08_24_keepingTheCheckoutUrlBumpsTheBookingVersionByOne() {
        var created = booking(BookingState.PAYMENT_PENDING, 0L, charge(null, null));
        var checkout = new SingleClassChargePort.Pending("cs_1", "payment-1", "member-laura", "booking-1", new Money(1200L, "EUR"), "Classe", NOW.plusSeconds(1800));
        when(charges.open(checkout)).thenReturn("https://checkout.example.test/cs_1");
        when(bookings.require("booking-1")).thenReturn(created);

        var opened = service.openCheckout(new BookingConfirmationService.Confirmed(created, null, checkout));

        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(0L));
        assertThat(updated.getValue().charge().checkoutUrl()).isEqualTo("https://checkout.example.test/cs_1");
        assertThat(updated.getValue().version()).isEqualTo(1L);
        assertThat(opened.booking().version()).isEqualTo(1L);
    }

    @Test void T_08_24_aSettledPaymentBumpsTheBookingVersionByOne() {
        var pending = booking(BookingState.PAYMENT_PENDING, 2L, charge(null, "https://checkout.example.test/cs_1"));
        when(bookings.findById("booking-1")).thenReturn(Optional.of(pending));
        when(bookings.require("booking-1")).thenReturn(pending);

        service.paymentSucceeded("booking-1");

        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(2L));
        assertThat(updated.getValue().state()).isEqualTo(BookingState.ACTIVE);
        assertThat(updated.getValue().version()).isEqualTo(3L);
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    /** What BookingChecks#subject returns for Laura's dog Duna in class-a (capacity 6, nothing booked, limit not reached). */
    private BookingChecks.Subject stubSubject() {
        var session = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"), "18:00", "19:00", STARTS,
                STARTS.plusSeconds(3600), "ring-1", List.of(), List.of(), 6, 0, 0, false, null, 3L);
        var dog = new BookingMemberAccess.Dog("dog-duna", "Duna", "F", "member-laura", null, "ACTIVE");
        var laura = new BookingMemberAccess.Member("member-laura", "account-laura", "Laura", "Laura Serra", "ACTIVE", null, false, null, null, null,
                "ca", "laura@example.test", List.of());
        var week = new BookingWeeks.Week("2026-10-04", Instant.parse("2026-10-04T18:00:00Z"), Instant.parse("2026-10-11T18:00:00Z"));
        var subject = new BookingChecks.Subject(session, dog, laura, laura, week, RelativeWeek.CURRENT, Optional.empty());
        when(checks.subject(actor, "class-a", "dog-duna", NOW)).thenReturn(subject);
        when(checks.limit(subject, NOW)).thenReturn(new BookingLimits.Result(false, LimitUnit.DOG, 0, 2, List.of(), List.of()));
        return subject;
    }

    /** A PAY_TO_BOOK charge prepared at confirmation (checkout session cs_1). */
    private static Booking.Charge charge(Instant paidAt, String url) {
        return new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_1", null, paidAt, url);
    }

    /** Laura's booking of Duna in class-a. */
    private static Booking booking(BookingState state, long version, Booking.Charge charge) {
        var booked = NOW.minusSeconds(86_400);
        return new Booking("booking-1", "club-a", "class-a", "dog-duna", "member-laura", state, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                charge, null,
                version, booked, "account-laura", booked, "account-laura");
    }
}
