package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.RelativeWeek;
import com.agilityhub.core.clubs.bookings.domain.WaitlistCancelReason;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatHold;
import com.agilityhub.core.clubs.bookings.persistence.SeatHoldRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WaitlistService} (S08 R-08-12…R-08-16; T-08-19, T-08-20, T-08-22, T-08-26, T-08-33,
 * T-08-34): every mutation takes the class's seat lock before it re-reads, the claim lanes, a claim's hold must be the
 * caller's and still live, a taken offer is SEAT_TAKEN only when other dogs fill the class, a redelivered
 * `WaitlistExpired` never expires an entry twice, the P8 sweep and the leave cancellations, and R-01-07's `reachable`.
 * Pure unit tests: collaborators are mocks, the transaction runs its work inline, events go through a real
 * {@link BookingEvents}.
 */
class WaitlistServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    /** Wednesday 7 October, 18:00 Madrid: two days ahead, in time for every waiting-list threshold. */
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

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

    final BookingActor laura = new BookingActor("account-laura", "member-laura", "Laura", null, BookingOrigin.APP, ActorRole.MEMBER);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
    }

    @AfterEach void tearDown() { tenant.close(); }

    // --- seat locks of join, leave and claim (lines 47, 93, 119) ---------------------------------------------------------

    @Test void T_08_19_joiningTakesTheSeatLockBeforeTheEligibilityChainEvenWhenTheClassIsNotFull() {
        // Capacity 2 and one live booking: CLASS_NOT_FULL, decided under the lock like any other answer of join.
        var subject = subject(2);
        when(checks.subject(laura, "class-a", "dog-duna", NOW)).thenReturn(subject);
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "class-a", "dog-nala", "member-pau")));

        assertThatThrownBy(() -> service.join(laura, "class-a", "dog-duna"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CLASS_NOT_FULL));

        var order = inOrder(locks, checks);
        order.verify(locks).lock("class-a");
        order.verify(checks).subject(laura, "class-a", "dog-duna", NOW);
    }

    @Test void T_08_19_leavingTakesTheSeatLockBeforeReReadingTheEntry() {
        var waiting = entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, null, null, 0L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(waiting));

        service.leave(laura, "entry-1");

        var order = inOrder(waitlist, locks, transitions);
        order.verify(waitlist).findById("entry-1");
        order.verify(locks).lock("class-a");
        order.verify(waitlist).findById("entry-1");
        order.verify(transitions).cancel(waiting, WaitlistCancelReason.MEMBER, NOW, "account-laura");
    }

    @Test void T_08_22_aClaimTakesTheSeatLockBeforeReReadingTheEntryAndConfirmsWithTheEntrysHold() {
        // FIFO offer made 10 minutes ago, 20 minutes left; Laura holds the seat for this entry.
        var offer = entry("entry-1", "dog-duna", "member-laura", WaitlistState.NOTIFIED, NOW.minusSeconds(600), NOW.plusSeconds(1200), 1L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(offer));
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold("hold-1", "dog-duna", "account-laura", "entry-1", NOW.plusSeconds(240))));

        service.claim(laura, "entry-1", "hold-1", null);

        var order = inOrder(locks, waitlist, confirmations);
        order.verify(locks).lock("class-a");
        order.verify(waitlist).findById("entry-1");
        order.verify(confirmations).confirm(laura, "hold-1", null);
    }

    // --- claim: lanes (lines 106-107), the hold filter (line 127) and SEAT_TAKEN (line 141) --------------------------------

    @Test void T_08_22_theClaimLanesAreTheEntrysClassThenTheClassOfTheSwappedBooking() {
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(
                entry("entry-1", "dog-duna", "member-laura", WaitlistState.NOTIFIED, NOW.minusSeconds(600), NOW.plusSeconds(1200), 1L)));
        // Duna's booking of the same week in another class, the one the claim would swap.
        when(bookings.findById("booking-old")).thenReturn(Optional.of(booking("booking-old", "class-b", "dog-duna", "member-laura")));

        assertThat(service.classes("entry-1", "booking-old")).containsExactly("class-a", "class-b");
        assertThat(service.classes("entry-1", null)).containsExactly("class-a");
    }

    @Test void T_08_33_aClaimWithAHoldPastItsExpiryButNotYetPurgedByTheTtlIsSeatHoldExpired() {
        var offer = entry("entry-1", "dog-duna", "member-laura", WaitlistState.NOTIFIED, NOW.minusSeconds(600), NOW.plusSeconds(1200), 1L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(offer));
        // The TTL monitor removes expired holds up to 60 s late: the document is still there, 5 s past expiresAt.
        when(holds.findById("hold-1")).thenReturn(Optional.of(hold("hold-1", "dog-duna", "account-laura", "entry-1", NOW.minusSeconds(5))));

        assertThatThrownBy(() -> service.claim(laura, "entry-1", "hold-1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SEAT_HOLD_EXPIRED));
        verify(confirmations, never()).confirm(any(), any(), any());
    }

    @Test void T_08_20_aTakenOfferIsNotSeatTakenWhenOnlyItsOwnDogsHoldFillsTheClass() {
        // ALL_AT_ONCE: Duna's offer was taken (demoted: ACTIVE, notifiedAt kept). Later a booking was cancelled and, before
        // the SeatReleased consumer offered the seat again, Laura held it for Duna directly (a hold needs no live entry check,
        // BookingChecks#notBookedYet); the app then retries the old claim.
        var demoted = entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, NOW.minusSeconds(3600), null, 2L);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(demoted));
        when(classes.find("class-a")).thenReturn(Optional.of(session(2, 1, STARTS)));
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "class-a", "dog-nala", "member-pau")));
        when(holds.live("class-a", NOW)).thenReturn(List.of(hold("hold-1", "dog-duna", "account-laura", null, NOW.plusSeconds(240))));

        assertThatThrownBy(() -> service.claim(laura, "entry-1", "hold-1", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.WAITLIST_NOT_NOTIFIED));
    }

    // --- offerNext (lines 151, 156, 158) ---------------------------------------------------------------------------------

    @Test void T_08_34_aWaitlistExpiredDeliveryNeverExpiresTheEntryAgainAndOffersTheSeatToTheNextUnderTheLock() {
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(context.waitlistMode()).thenReturn(WaitlistMode.FIFO);
        when(context.integer("waitlist.notifyThresholdMinutes")).thenReturn(30);
        when(context.integer("waitlist.fifoConfirmMinutes")).thenReturn(30);
        // P6 already expired entry-1 (WaitlistFifoJob) and published the event this consumer handles.
        var expired = entry("entry-1", "dog-duna", "member-laura", WaitlistState.EXPIRED, NOW.minusSeconds(2400), NOW.minusSeconds(600), 2L);
        var next = entry("entry-2", "dog-kira", "member-eva", WaitlistState.ACTIVE, null, null, 0L);
        var confirmBy = NOW.plusSeconds(1800);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(expired));
        when(classes.find("class-a")).thenReturn(Optional.of(session(2, 1, STARTS)));
        when(waitlist.live("class-a")).thenReturn(List.of(next));
        when(transitions.freeSeats("class-a")).thenReturn(1);
        when(transitions.notify(next, NOW, confirmBy, null))
                .thenReturn(entry("entry-2", "dog-kira", "member-eva", WaitlistState.NOTIFIED, NOW, confirmBy, 1L));

        // The `WaitlistExpired` consumer ignores the count (WaitlistConsumers.java:42): only the offer it writes is observable.
        service.offerNext("class-a", "entry-1");

        verify(transitions, never()).expire(any(), any());
        var order = inOrder(locks, transitions);
        order.verify(locks).lock("class-a");
        order.verify(transitions).notify(next, NOW, confirmBy, null);
    }

    // --- sweep (lines 214, 219) and the leave cancellations (cancelLocked, lines 248, 255) ---------------------------------

    @Test void T_08_19_theClassStartedSweepReadsTheClassStartUnderTheLockAndSkipsAnEntryNoLongerLive() {
        var started = Instant.parse("2026-10-05T07:30:00Z");
        when(classes.find("class-a")).thenReturn(Optional.of(session(2, 2, started)));
        var waiting = entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, null, null, 0L, started);
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(waiting));

        assertThat(service.sweepStarted("entry-1", NOW)).isTrue();

        var order = inOrder(locks, classes, transitions);
        order.verify(locks).lock("class-a");
        order.verify(classes).find("class-a");
        order.verify(transitions).cancel(waiting, WaitlistCancelReason.CLASS_STARTED, NOW, null);

        // An entry claimed before the class started is no longer live: nothing to sweep.
        when(waitlist.findById("entry-2")).thenReturn(Optional.of(
                entry("entry-2", "dog-kira", "member-eva", WaitlistState.CONSOLIDATED, NOW.minusSeconds(5400), null, 3L, started)));
        assertThat(service.sweepStarted("entry-2", NOW)).isFalse();
    }

    @Test void T_08_19_aSweptEntryLeftBetweenTheReadAndTheLockRecountsNothing() {
        // P8 (S15 R-15-18a) reads Laura's entry live (WaitlistService.java:213); she leaves the list (WaitlistService#leave)
        // before the sweep takes the class's seat lock, so the re-read under the lock (:251) finds it CANCELLED{MEMBER}.
        var started = Instant.parse("2026-10-05T07:30:00Z");
        when(classes.find("class-a")).thenReturn(Optional.of(session(2, 2, started)));
        var listed = entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, null, null, 0L, started);
        var left = new WaitlistEntry("entry-1", "club-a", "class-a", "dog-duna", "member-laura", "account-laura", NOW.minusSeconds(7200),
                WaitlistState.CANCELLED, 1, null, null, null, null, NOW.minusSeconds(1), WaitlistCancelReason.MEMBER, started, "2026-10-04", 1L,
                NOW.minusSeconds(7200), "account-laura", NOW.minusSeconds(1), "account-laura");
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(listed), Optional.of(left));

        assertThat(service.sweepStarted("entry-1", NOW)).isFalse();

        verify(transitions, never()).cancel(any(), any(), any(), any());
        verify(counters, never()).recount(anyString(), anyBoolean(), any());
    }

    // --- reachable (lines 83-84) -----------------------------------------------------------------------------------------

    @Test void T_08_26_reachableIsTrueForTheMembersOwnEntryFalseForAnotherMembersAndNotFoundForAnUnknownOne() {
        // An account with ADMIN and MEMBER leaving an entry (BookingsController#leaveWaitlist asks reachable first).
        when(waitlist.findById("entry-1")).thenReturn(Optional.of(entry("entry-1", "dog-duna", "member-laura", WaitlistState.ACTIVE, null, null, 0L)));
        when(waitlist.findById("entry-2")).thenReturn(Optional.of(entry("entry-2", "dog-kira", "member-eva", WaitlistState.ACTIVE, null, null, 0L)));
        when(census.reachableMembers("member-laura")).thenReturn(Set.of("member-laura"));

        assertThat(service.reachable("entry-1", "member-laura")).isTrue();
        assertThat(service.reachable("entry-2", "member-laura")).isFalse();
        assertThatThrownBy(() -> service.reachable("entry-9", "member-laura"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    /** class-a as {@code ClassSessionBookingAccess} reads it (club-local date and times of `startsAt`, one hour long). */
    static ClassSessionBookingAccess.Session session(int capacity, int booked, Instant startsAt) {
        var local = startsAt.atZone(java.time.ZoneId.of("Europe/Madrid"));
        return new ClassSessionBookingAccess.Session("class-a", "ACTIVE", local.toLocalDate(), local.toLocalTime().toString(),
                local.plusHours(1).toLocalTime().toString(), startsAt, startsAt.plusSeconds(3600), "ring-1", List.of(), List.of("instructor-neus"),
                capacity, booked, 0, false, null, 3L);
    }

    /** What BookingChecks#subject returns for Laura's dog Duna in class-a. */
    static BookingChecks.Subject subject(int capacity) {
        var dog = new BookingMemberAccess.Dog("dog-duna", "Duna", "FEMALE", "member-laura", null, "ACTIVE");
        var owner = new BookingMemberAccess.Member("member-laura", "account-laura", "Laura", "Laura Serra", "ACTIVE", null, false, null, null, null,
                "ca", "laura@example.test", List.of());
        var week = new BookingWeeks.Week("2026-10-04", Instant.parse("2026-10-04T18:00:00Z"), Instant.parse("2026-10-11T18:00:00Z"));
        return new BookingChecks.Subject(session(capacity, 1, STARTS), dog, owner, owner, week, RelativeWeek.CURRENT, Optional.empty());
    }

    static WaitlistEntry entry(String id, String dogId, String memberId, WaitlistState state, Instant notifiedAt, Instant confirmBy, long version) {
        return entry(id, dogId, memberId, state, notifiedAt, confirmBy, version, STARTS);
    }

    static WaitlistEntry entry(String id, String dogId, String memberId, WaitlistState state, Instant notifiedAt, Instant confirmBy, long version,
            Instant classStartsAt) {
        var joined = NOW.minusSeconds(7200);
        var account = memberId.replace("member-", "account-");
        // Positions follow the joins (maxPosition + 1): entry-N joined N-th.
        int position = Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
        return new WaitlistEntry(id, "club-a", "class-a", dogId, memberId, account, joined, state, position, notifiedAt, confirmBy, null,
                state == WaitlistState.CONSOLIDATED ? "booking-9" : null, null, null, classStartsAt, "2026-10-04", version, joined, account,
                notifiedAt == null ? joined : notifiedAt, account);
    }

    static SeatHold hold(String id, String dogId, String accountId, String waitlistEntryId, Instant expiresAt) {
        return new SeatHold(id, "club-a", "class-a", dogId, "member-laura", accountId, waitlistEntryId, expiresAt.minusSeconds(300), expiresAt);
    }

    static Booking booking(String id, String classId, String dogId, String memberId) {
        var booked = NOW.minusSeconds(86_400);
        var account = memberId.replace("member-", "account-");
        var startsAt = "class-a".equals(classId) ? STARTS : STARTS.plusSeconds(86_400);
        return new Booking(id, "club-a", classId, dogId, memberId, BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, "Soci"), startsAt, startsAt.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, account, booked, account);
    }
}
