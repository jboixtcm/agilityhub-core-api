package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.application.ports.WaitlistConsolidationPort;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
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
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
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
 * E11-T06 PIT survivors of {@link BookingConfirmationService} (S08 R-08-07, R-08-08, R-08-09, R-08-18, R-08-19; T-08-14,
 * T-08-15, T-08-17, T-08-24, T-08-29): the transaction lanes, the seat locks, the capacity check with `heldOnly`, the
 * booker's name of an impersonated booking, the swap's `swapToBookingId` version, the `BookingCreated` payload and the
 * PAY_TO_BOOK settlement. Pure unit tests: collaborators are mocks, the transaction runs its work inline and the events go
 * through a real {@link BookingEvents}.
 */
class BookingConfirmationServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    /** The class of the swapped booking: the next day, same booking week (opened 2026-10-04 18:00Z). */
    static final Instant OLD_STARTS = STARTS.plusSeconds(86_400);

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final BookingChecks checks = mock(BookingChecks.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final PackBalancePort packs = mock(PackBalancePort.class);
    final WaitlistConsolidationPort waitlist = mock(WaitlistConsolidationPort.class);
    final SingleClassChargePort charges = mock(SingleClassChargePort.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingEvents events = new BookingEvents(publisher, mock(AuditActorProvider.class), context);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingAudit audit = mock(BookingAudit.class);
    final BookingViews views = mock(BookingViews.class);
    final SeatHoldService seatHolds = mock(SeatHoldService.class);
    final BookingConfirmationService service = new BookingConfirmationService(context, transactions, checks, locks, holds, bookings, cancellations,
            packs, waitlist, charges, census, events, counters, audit, views, seatHolds);

    /** The member route actor: its display name is the JWT member's first name (BookingActors#member). */
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

    // --- classes(): the transaction lanes (lines 45-47) ------------------------------------------------------------------

    @Test void T_08_17_theLanesAreTheHeldClassThenTheClassOfTheSwappedBooking() {
        when(bookings.findById("booking-old")).thenReturn(Optional.of(booking("booking-old", "class-b", BookingState.ACTIVE, 4L, null, null, OLD_STARTS)));

        assertThat(service.classes("hold-1", "booking-old")).containsExactly("class-a", "class-b");
        assertThat(service.classes("hold-1", null)).containsExactly("class-a");
    }

    // --- capacity (lines 74-75) --------------------------------------------------------------------------------------------
    // A live hold guarantees its seat when it is taken (SeatHoldService#admit), so a full class at confirmation needs the
    // administrator to have lowered the capacity meanwhile: allowed down to the booked count, holds ignored
    // (ClassSessionService#validate, CAPACITY_BELOW_BOOKINGS), and the holds survive the edit.

    @Test void T_08_29_aClassFullOfBookingsRefusesTheConfirmationWithHeldOnlyFalse() {
        // Capacity 3 with two bookings and Laura's hold, then lowered to 2 (= booked).
        var subject = stubSubject(actor, 2, 2);
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(
                otherDogsBooking("booking-1", "dog-kira", "member-eva"), otherDogsBooking("booking-2", "dog-rufo", "member-pau")));
        when(holds.live("class-a", NOW)).thenReturn(List.of(hold));

        assertThatThrownBy(() -> service.confirm(actor, "hold-1", null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL);
            assertThat(e.details()).containsEntry("heldOnly", false);
        });
        verify(bookings, never()).insert(any());
        assertThat(subject.session().capacity()).isEqualTo(2);
    }

    @Test void T_08_14_anotherLiveHoldOnTheLastSeatAnswersClassFullHeldOnly() {
        // Capacity 3 with one booking, Laura's hold and Eva's hold, then lowered to 2 (>= booked 1).
        stubSubject(actor, 2, 1);
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(otherDogsBooking("booking-1", "dog-rufo", "member-pau")));
        var other = new SeatHold("hold-2", "club-a", "class-a", "dog-kira", "member-eva", "account-eva", null, NOW.minusSeconds(30), NOW.plusSeconds(270));
        when(holds.live("class-a", NOW)).thenReturn(List.of(hold, other));

        assertThatThrownBy(() -> service.confirm(actor, "hold-1", null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL);
            assertThat(e.details()).containsEntry("heldOnly", true);
        });
        verify(bookings, never()).insert(any());
    }

    // --- the confirmed booking (lines 58, 89) ------------------------------------------------------------------------------

    @Test void T_08_15_anImpersonatedBookingIsActiveLockedOnItsClassAndNamesTheAdministrator() {
        // R-08-19: impersonating, the acting account is the admin's (it owns the hold) and the member is the impersonated one.
        var admin = new BookingActor("account-marta", "member-laura", "Marta Puig", "member-laura", BookingOrigin.BACKOFFICE, ActorRole.ADMIN);
        var adminHold = new SeatHold("hold-1", "club-a", "class-a", "dog-duna", "member-laura", "account-marta", null, NOW.minusSeconds(60),
                NOW.plusSeconds(240));
        when(holds.findById("hold-1")).thenReturn(Optional.of(adminHold));
        stubSubject(admin, 6, 0);

        var confirmed = service.confirm(admin, "hold-1", null);

        var booking = confirmed.booking();
        assertThat(booking.state()).isEqualTo(BookingState.ACTIVE);
        assertThat(booking.origin()).isEqualTo(BookingOrigin.BACKOFFICE);
        assertThat(booking.bookedBy()).isEqualTo(new Booking.Actor("account-marta", "member-laura", "Marta Puig"));
        verify(locks).lock("class-a");
        verify(holds).deleteById("hold-1");
        verify(counters).recount("class-a", false, admin);
        verify(audit).createdByClub(booking);
    }

    // --- swap across classes (lines 60 and 155) ----------------------------------------------------------------------------

    @Test void T_08_17_aSwapFromAnotherClassLocksBothClassesAndPointsTheOldBookingToTheNewOne() {
        var old = booking("booking-old", "class-b", BookingState.ACTIVE, 4L, null, null, OLD_STARTS);
        // Monday's class of the same week already started: counted, not swappable (DONE).
        var monday = booking("booking-mon", "class-m", BookingState.ACTIVE, 1L, null, null, Instant.parse("2026-10-05T07:00:00Z"));
        // What cancelLocked writes for a swap: CANCELLED/SWAP, never late, version + 1 (BookingCancellationService#cancel).
        var cancelledOld = new Booking("booking-old", "club-a", "class-b", "dog-duna", "member-laura", BookingState.CANCELLED, BookingOrigin.APP,
                old.bookedAt(), old.bookedBy(), OLD_STARTS, OLD_STARTS.plusSeconds(3600), "2026-10-04",
                NOW, new Booking.Canceller("account-laura", ActorRole.MEMBER, "Laura", null), BookingCancelReason.SWAP, null, false, 4800,
                null, null, null, null, null, null, null,
                5L, old.createdAt(), "account-laura", NOW, "account-laura");
        when(bookings.findById("booking-old")).thenReturn(Optional.of(old));
        var limit = BookingLimits.evaluate(List.of(BookingChecks.counted(old), BookingChecks.counted(monday)), LimitUnit.DOG, "dog-duna", "member-laura",
                2, NOW, Duration.ofMinutes(120));
        var subject = stubSubject(actor, 6, 0, limit);
        when(cancellations.cancelLocked(old, actor, BookingCancelReason.SWAP, null, "class-a")).thenReturn(cancelledOld);

        var confirmed = service.confirm(actor, "hold-1", "booking-old");

        assertThat(limit.reached()).isTrue();
        assertThat(limit.canSwap("booking-old")).isTrue();
        verify(locks).lock("class-a");
        verify(locks).lock("class-b");
        assertThat(confirmed.booking().swapFromBookingId()).isEqualTo("booking-old");
        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(5L));
        assertThat(updated.getValue().id()).isEqualTo("booking-old");
        assertThat(updated.getValue().swapToBookingId()).isEqualTo(confirmed.booking().id());
        assertThat(updated.getValue().version()).isEqualTo(6L);
        assertThat(subject.session().id()).isEqualTo("class-a");
    }

    // --- BookingCreated payload (line 148) ---------------------------------------------------------------------------------

    @Test void T_08_19_bookingCreatedNamesTheWaitlistEntryOnlyWhenTheBookingConsolidatedOne() {
        service.created(booking("booking-1", "class-a", BookingState.ACTIVE, 0L, "entry-1", null), actor);
        service.created(booking("booking-2", "class-b", BookingState.ACTIVE, 0L, null, null, OLD_STARTS), actor);

        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, times(2)).publish(published.capture());
        var first = (BookingEvent) published.getAllValues().get(0);
        var second = (BookingEvent) published.getAllValues().get(1);
        assertThat(first.kind()).isEqualTo(BookingEvent.Kind.BookingCreated);
        assertThat(first.payload()).containsEntry("waitlistEntryId", "entry-1");
        assertThat(second.payload()).doesNotContainKey("waitlistEntryId");
    }

    // --- PAY_TO_BOOK (lines 135, 173, 184) ---------------------------------------------------------------------------------

    @Test void T_08_24_aCheckoutUrlIsNotWrittenOnABookingThatAlreadyLeftPaymentPending() {
        var created = booking("booking-1", "class-a", BookingState.PAYMENT_PENDING, 0L, null, charge(null));
        // UpfrontPaymentSucceeded was consumed between the commit and the URL write: ACTIVE, paid, version + 1.
        var paid = booking("booking-1", "class-a", BookingState.ACTIVE, 1L, null, charge(NOW));
        var checkout = new SingleClassChargePort.Pending("cs_1", "payment-1", "member-laura", "booking-1", new Money(1200L, "EUR"), "Classe", NOW.plusSeconds(1800));
        when(charges.open(checkout)).thenReturn("https://checkout.example.test/cs_1");
        when(bookings.require("booking-1")).thenReturn(paid);

        var opened = service.openCheckout(new BookingConfirmationService.Confirmed(created, null, checkout));

        assertThat(opened.booking()).isSameAs(paid);
        assertThat(opened.checkoutUrl()).isEqualTo("https://checkout.example.test/cs_1");
        verify(locks).lock("class-a");
        verify(bookings, never()).update(any(), anyLong());
    }

    @Test void T_08_24_upfrontPaymentSucceededLocksTheClassActivatesTheBookingAndRecountsIt() {
        var pending = booking("booking-1", "class-a", BookingState.PAYMENT_PENDING, 2L, null, charge(null));
        when(bookings.findById("booking-1")).thenReturn(Optional.of(pending));
        when(bookings.require("booking-1")).thenReturn(pending);

        service.paymentSucceeded("booking-1");

        verify(locks).lock("class-a");
        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(2L));
        assertThat(updated.getValue().state()).isEqualTo(BookingState.ACTIVE);
        assertThat(updated.getValue().charge().paidAt()).isEqualTo(NOW);
        verify(counters).recount("class-a", false, BookingActor.system());
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    private BookingChecks.Subject stubSubject(BookingActor by, int capacity, int booked) {
        return stubSubject(by, capacity, booked, new BookingLimits.Result(false, LimitUnit.DOG, 0, 2, List.of(), List.of()));
    }

    /** What BookingChecks#subject returns for Laura's dog Duna in class-a; `booked` mirrors the live bookings the test stubs. */
    private BookingChecks.Subject stubSubject(BookingActor by, int capacity, int booked, BookingLimits.Result limit) {
        var session = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"), "18:00", "19:00", STARTS,
                STARTS.plusSeconds(3600), "ring-1", List.of(), List.of(), capacity, booked, 0, false, null, 3L);
        var dog = new BookingMemberAccess.Dog("dog-duna", "Duna", "F", "member-laura", null, "ACTIVE");
        var laura = new BookingMemberAccess.Member("member-laura", "account-laura", "Laura", "Laura Serra", "ACTIVE", null, false, null, null, null,
                "ca", "laura@example.test", List.of());
        var week = new BookingWeeks.Week("2026-10-04", Instant.parse("2026-10-04T18:00:00Z"), Instant.parse("2026-10-11T18:00:00Z"));
        var subject = new BookingChecks.Subject(session, dog, laura, laura, week, RelativeWeek.CURRENT, Optional.empty());
        when(checks.subject(by, "class-a", "dog-duna", NOW)).thenReturn(subject);
        when(checks.limit(subject, NOW)).thenReturn(limit);
        return subject;
    }

    /** A PAY_TO_BOOK charge prepared at confirmation (checkout session cs_1); `paidAt` is set once the payment succeeded. */
    private static Booking.Charge charge(Instant paidAt) {
        return new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_1", null, paidAt, null);
    }

    /** Another member's ACTIVE booking of another dog in class-a (a dog has one live booking per class: BookingChecks#notBookedYet). */
    private static Booking otherDogsBooking(String id, String dogId, String memberId) {
        var booked = NOW.minusSeconds(86_400);
        String accountId = memberId.replace("member-", "account-");
        return new Booking(id, "club-a", "class-a", dogId, memberId, BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor(accountId, null, "Soci"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, accountId, booked, accountId);
    }

    static Booking booking(String id, String classId, BookingState state, long version, String waitlistEntryId, Booking.Charge charge) {
        return booking(id, classId, state, version, waitlistEntryId, charge, STARTS);
    }

    static Booking booking(String id, String classId, BookingState state, long version, String waitlistEntryId, Booking.Charge charge, Instant startsAt) {
        var booked = NOW.minusSeconds(86_400);
        return new Booking(id, "club-a", classId, "dog-duna", "member-laura", state, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), startsAt, startsAt.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, waitlistEntryId, null, null,
                charge, null,
                version, booked, "account-laura", booked, "account-laura");
    }
}
