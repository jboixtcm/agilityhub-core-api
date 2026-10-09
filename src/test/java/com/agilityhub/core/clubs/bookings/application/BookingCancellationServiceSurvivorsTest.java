package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.AttendanceStatePort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingEvent;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.ChargeMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatHoldRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.DomainEvent;
import com.agilityhub.core.shared.domain.Money;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
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
 * E11-T06 PIT survivors of {@link BookingCancellationService} (S08 R-08-07, R-08-10, R-08-11, R-08-17, R-08-18, R-08-21;
 * T-08-07, T-08-18, T-08-24, T-08-25, T-08-28): every entry point takes the class's seat lock before re-reading the
 * booking, the cancelled booking's version is bumped, `SeatReleased` counts the other live bookings and only ACTIVE
 * waiting-list entries, `packRefunded` follows the refund and `cancelByClub` records who cancelled.
 */
class BookingCancellationServiceSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    /** Two days ahead: in time for the 240-minute threshold and the 30-minute waiting-list threshold. */
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final PackBalancePort packs = mock(PackBalancePort.class);
    final AttendanceStatePort attendance = mock(AttendanceStatePort.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingEvents events = new BookingEvents(publisher, mock(AuditActorProvider.class), context);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingAudit audit = mock(BookingAudit.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final BookingCancellationService service = new BookingCancellationService(context, transactions, locks, bookings, holds, waitlist, packs,
            attendance, events, counters, audit, classes, transitions);

    final BookingActor member = new BookingActor("account-laura", "member-laura", "Laura Serra", null, BookingOrigin.APP, ActorRole.MEMBER);
    /** Another member's live booking in class-a: what `forClass(LIVE)` still returns once the cancelled one left LIVE. */
    final Booking other = booking("booking-2", "class-a", "member-pau", "dog-nala", STARTS, BookingState.ACTIVE, 0L, null);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        when(context.today()).thenReturn(LocalDate.parse("2026-10-05"));
        when(context.integer("bookings.lateCancelThresholdMinutes")).thenReturn(240);
        when(context.integer("waitlist.notifyThresholdMinutes")).thenReturn(30);
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        // Capacity 3, two live bookings (the one cancelled + `other`), no waiting list.
        session(2, 0);
        // The read runs after the cancel write in the same transaction, so the cancelled booking is no longer LIVE.
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(other));
        when(bookings.update(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach void tearDown() { tenant.close(); }

    // --- seat locks of every entry point (lines 49, 57, 71, 90) and the version bump (line 139) ----------------------------

    @Test void T_08_18_aMemberCancellationLocksTheClassAndBumpsTheBookingVersion() {
        var active = booking("booking-1", BookingState.ACTIVE, 4L, null);
        when(bookings.require("booking-1")).thenReturn(active);

        var cancelled = service.cancel("booking-1", member, null);

        verify(locks).lock("class-a");
        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(4L));
        assertThat(updated.getValue().version()).isEqualTo(5L);
        assertThat(cancelled.state()).isEqualTo(BookingState.CANCELLED);
        assertThat(cancelled.cancelReason()).isEqualTo(BookingCancelReason.MEMBER);
    }

    @Test void T_08_18_aSystemCancellationLocksTheClass() {
        when(bookings.require("booking-1")).thenReturn(booking("booking-1", BookingState.ACTIVE, 4L, null));

        var cancelled = service.cancelBySystem("booking-1", BookingCancelReason.INACTIVITY);

        verify(locks).lock("class-a");
        assertThat(cancelled.cancelReason()).isEqualTo(BookingCancelReason.INACTIVITY);
    }

    @Test void T_08_24_aPaymentTimeoutLocksTheClassBeforeReReadingTheBooking() {
        var pending = booking("booking-1", BookingState.PAYMENT_PENDING, 2L, null);
        when(bookings.findById("booking-1")).thenReturn(Optional.of(pending));
        when(bookings.require("booking-1")).thenReturn(pending);

        var cancelled = service.cancelPaymentPending("booking-1", false);

        var order = inOrder(locks, bookings);
        order.verify(locks).lock("class-a");
        order.verify(bookings).require("booking-1");
        assertThat(cancelled).hasValueSatisfying(b -> assertThat(b.cancelReason()).isEqualTo(BookingCancelReason.PAYMENT_TIMEOUT));
    }

    @Test void T_08_18_cancellingTheFutureBookingsOfAMemberLocksEachClass() {
        var active = booking("booking-1", BookingState.ACTIVE, 4L, null);
        when(bookings.liveForMember("member-laura", NOW)).thenReturn(List.of(active));
        when(bookings.require("booking-1")).thenReturn(active);

        assertThat(service.cancelFutureByMember("member-laura", BookingActor.system(), BookingCancelReason.LEAVE, NOW)).isEqualTo(1);

        verify(locks).lock("class-a");
    }

    // --- SeatReleased (line 154; line 153's filter of the cancelled booking is a triage reason) ------------------------------

    @Test void T_08_07_seatReleasedCountsOnlyTheOtherLiveBookingsAndOnlyActiveEntries() {
        // FIFO: the third seat was released earlier and is offered to the only live entry, NOTIFIED until confirmBy.
        session(2, 1);
        when(bookings.require("booking-1")).thenReturn(booking("booking-1", BookingState.ACTIVE, 4L, null));
        when(waitlist.live("class-a")).thenReturn(List.of(notified("entry-1", 1)));

        service.cancel("booking-1", member, null);

        // Capacity 3, one live booking left → 2 free; nobody ACTIVE is left to tell (R-08-11 needs an ACTIVE entry).
        assertThat(seatReleased()).containsEntry("freeSeats", 2).containsEntry("notifyWaitlist", false);
    }

    @Test void T_08_07_anActiveEntryIsToldWhenTheSeatIsReleasedInTime() {
        // FIFO: the first entry holds the offer of the third seat, the second still queues ACTIVE behind it.
        session(2, 2);
        when(bookings.require("booking-1")).thenReturn(booking("booking-1", BookingState.ACTIVE, 4L, null));
        when(waitlist.live("class-a")).thenReturn(List.of(notified("entry-1", 1), active("entry-2", 2)));

        service.cancel("booking-1", member, null);

        assertThat(seatReleased()).containsEntry("freeSeats", 2).containsEntry("notifyWaitlist", true);
    }

    // --- packRefunded of the S10 notice (line 148) ---------------------------------------------------------------------------

    @Test void T_08_28_theNoticeCancellationReportsAPackRefundOnlyWhenOneWasMade() {
        var instructor = BookingActor.instructor("account-neus", "Neus");
        // Laura's two dogs and Pau's dog fill the class; each notice cancels one of Laura's bookings in turn.
        session(3, 0);
        var pack = booking("booking-pack", "class-a", "member-laura", "dog-duna", STARTS, BookingState.ACTIVE, 1L, "movement-1");
        var plain = booking("booking-plain", "class-a", "member-laura", "dog-bruc", STARTS, BookingState.ACTIVE, 1L, null);
        when(bookings.require("booking-pack")).thenReturn(pack);
        when(bookings.require("booking-plain")).thenReturn(plain);
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(plain, other), List.of(other));
        when(packs.refund("member-laura", "dog-duna", "booking-pack", LocalDate.parse("2026-10-05"))).thenReturn("refund-1");

        var withPack = service.cancelForNotice("booking-pack", instructor);
        var withoutPack = service.cancelForNotice("booking-plain", instructor);

        assertThat(withPack.packRefunded()).isTrue();
        assertThat(withPack.booking().packRefundMovementId()).isEqualTo("refund-1");
        assertThat(withoutPack.packRefunded()).isFalse();
    }

    // --- cancelByClub (lines 178, 180; timed out in the PIT run) ----------------------------------------------------------

    @Test void T_08_25_aClubCancellationBumpsTheVersionAndRecordsTheAdminOrTheSystem() {
        // Two classes, each cancelled once (S06 refuses a second cancellation): the admin's CLUB_MANUAL with its required
        // text (ClassCancellationUseCase), and today's class auto-cancelled by RiskReviewJob (actor null, formatted text).
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", BookingState.ACTIVE, 4L, null)));
        var today = Instant.parse("2026-10-05T16:00:00Z");
        when(bookings.forClass("class-b", BookingRepository.LIVE))
                .thenReturn(List.of(booking("booking-b1", "class-b", "member-pau", "dog-nala", today, BookingState.ACTIVE, 4L, null)));

        var byAdmin = service.cancelByClub("class-a", "CLUB_MANUAL", "Pluja", "account-admin");
        var bySystem = service.cancelByClub("class-b", "RISK_REVIEW", "Classe anul·lada: no arriba al mínim de 3 gossos.", null);

        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings, times(2)).update(updated.capture(), eq(4L));
        var admin = updated.getAllValues().get(0);
        assertThat(admin.version()).isEqualTo(5L);
        assertThat(admin.state()).isEqualTo(BookingState.CANCELLED_BY_CLUB);
        assertThat(admin.cancelledBy().role()).isEqualTo(ActorRole.ADMIN);
        assertThat(admin.cancelReason()).isEqualTo(BookingCancelReason.CLUB_CLASS_CANCELLED);
        var system = updated.getAllValues().get(1);
        assertThat(system.version()).isEqualTo(5L);
        assertThat(system.cancelledBy().role()).isEqualTo(ActorRole.SYSTEM);
        assertThat(system.cancelReason()).isEqualTo(BookingCancelReason.AUTO_CANCELLED);
        assertThat(byAdmin.bookings()).hasSize(1);
        assertThat(bySystem.bookings()).hasSize(1);
        verify(holds).deleteForClass("class-a");
        verify(holds).deleteForClass("class-b");
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    /** class-a as {@code ClassSessionBookingAccess} reads it: capacity 3 and the counters of the bookings/entries each test stubs. */
    private void session(int booked, int waiting) {
        var session = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"), "18:00", "19:00", STARTS,
                STARTS.plusSeconds(3600), "ring-1", List.of("level-1"), List.of("instructor-neus"), 3, booked, waiting, false, null, 3L);
        when(classes.require("class-a")).thenReturn(session);
        when(classes.find("class-a")).thenReturn(Optional.of(session));
    }

    private Map<String, Object> seatReleased() {
        var published = ArgumentCaptor.forClass(DomainEvent.class);
        verify(publisher, atLeastOnce()).publish(published.capture());
        return published.getAllValues().stream().map(BookingEvent.class::cast).filter(e -> e.kind() == BookingEvent.Kind.SeatReleased)
                .findFirst().orElseThrow().payload();
    }

    /** An entry still queued (WaitlistService#join: ACTIVE, never notified). */
    private static WaitlistEntry active(String id, int position) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", "dog-kira-" + position, "member-eva", "account-eva", joined, WaitlistState.ACTIVE, position,
                null, null, null, null, null, null, STARTS, "2026-10-04", 0L, joined, "account-eva", joined, "account-eva");
    }

    /** A FIFO offer (WaitlistTransitions#notify: NOTIFIED with notifiedAt and a confirmBy still ahead, version bumped once). */
    private static WaitlistEntry notified(String id, int position) {
        var joined = NOW.minusSeconds(7200); var notifiedAt = NOW.minusSeconds(1800);
        return new WaitlistEntry(id, "club-a", "class-a", "dog-kira-" + position, "member-eva", "account-eva", joined, WaitlistState.NOTIFIED, position,
                notifiedAt, notifiedAt.plusSeconds(7200), null, null, null, null, STARTS, "2026-10-04", 1L, joined, "account-eva", notifiedAt, null);
    }

    static Booking booking(String id, BookingState state, long version, String packMovementId) {
        return booking(id, "class-a", "member-laura", "dog-duna", STARTS, state, version, packMovementId);
    }

    static Booking booking(String id, String classId, String memberId, String dogId, Instant startsAt, BookingState state, long version,
            String packMovementId) {
        var booked = NOW.minusSeconds(86_400);
        var account = memberId.replace("member-", "account-");
        var charge = state == BookingState.PAYMENT_PENDING ? new Booking.Charge(ChargeMode.PAY_TO_BOOK, new Money(1200L, "EUR"), null, "cs_1", null, null, null) : null;
        return new Booking(id, "club-a", classId, dogId, memberId, state, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, "member-laura".equals(memberId) ? "Laura" : "Pau"), startsAt, startsAt.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, packMovementId, null,
                charge, null,
                version, booked, account, booked, account);
    }
}
