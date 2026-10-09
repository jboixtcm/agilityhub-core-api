package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingLimits;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.LimitUnit;
import com.agilityhub.core.clubs.bookings.domain.RelativeWeek;
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
import com.agilityhub.core.platform.application.audit.AuditActorProvider;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Instant;
import java.time.LocalDate;
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
 * E11-T06 PIT survivors of {@link SeatHoldService} (S08 R-08-07, R-08-15; T-08-14, T-08-22): a plain hold is pre-checked
 * outside the lock, and only a `CLASS_FULL{heldOnly: true}` pre-check goes on to the locked path, which decides again;
 * a claim's hold must name the dog's own entry of that class.
 */
class SeatHoldServiceSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final BookingChecks checks = mock(BookingChecks.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final SeatHoldRepository holds = mock(SeatHoldRepository.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final EventPublisher publisher = mock(EventPublisher.class);
    final BookingEvents events = new BookingEvents(publisher, mock(AuditActorProvider.class), context);
    final SingleClassChargePort charges = mock(SingleClassChargePort.class);
    final BookingViews views = mock(BookingViews.class);
    final SeatHoldService service = new SeatHoldService(context, transactions, checks, locks, holds, bookings, waitlist, events, charges, views);

    final BookingActor laura = new BookingActor("account-laura", "member-laura", "Laura", null, BookingOrigin.APP, ActorRole.MEMBER);
    TenantContext.Scope tenant;

    @BeforeEach void setUp() {
        tenant = TenantContext.open("club-a");
        when(context.now()).thenReturn(NOW);
        when(context.integer("bookings.seatHoldSeconds")).thenReturn(300);
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        when(holds.upsert(any())).thenAnswer(inv -> inv.getArgument(0));
        // Duna in class-a, capacity 2, weekly limit not reached.
        var subject = subject();
        when(checks.subject(laura, "class-a", "dog-duna", NOW)).thenReturn(subject);
        when(checks.limit(subject, NOW)).thenReturn(new BookingLimits.Result(false, LimitUnit.DOG, 0, 2, List.of(), List.of()));
    }

    @AfterEach void tearDown() { tenant.close(); }

    // --- precheck (lines 43, 60) -------------------------------------------------------------------------------------------

    @Test void T_08_14_aClassFullOfBookingsIsRefusedBeforeTheLockAndTheTransaction() {
        when(bookings.forClass("class-a", BookingRepository.LIVE))
                .thenReturn(List.of(booking("booking-1", "dog-nala", "member-pau"), booking("booking-2", "dog-kira", "member-eva")));

        assertThatThrownBy(() -> service.hold(laura, "class-a", "dog-duna", null)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.CLASS_FULL);
            assertThat(e.details()).containsEntry("heldOnly", false);
        });
        verifyNoInteractions(transactions, locks);
    }

    @Test void T_08_14_aSeatHeldOnlyByAnotherDogIsDecidedAgainUnderTheLockAndTakenOnceThatHoldIsReleased() {
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "dog-nala", "member-pau")));
        // Eva holds the last seat at the pre-check and releases it (DELETE /seat-holds) before Laura's locked path reads.
        var evasHold = new SeatHold("hold-eva", "club-a", "class-a", "dog-kira", "member-eva", "account-eva", null, NOW.minusSeconds(60), NOW.plusSeconds(240));
        when(holds.live("class-a", NOW)).thenReturn(List.of(evasHold), List.of());

        var held = service.hold(laura, "class-a", "dog-duna", null);

        assertThat(held.hold().dogId()).isEqualTo("dog-duna");
        assertThat(held.hold().expiresAt()).isEqualTo(NOW.plusSeconds(300));
        verify(locks).lock("class-a");
    }

    // --- offer (line 105) ---------------------------------------------------------------------------------------------------

    @Test void T_08_22_aClaimHoldNamingAnotherDogsEntryIsNotFound() {
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "dog-nala", "member-pau")));
        // Laura's other dog Bruc has the open offer (ALL_AT_ONCE, no confirmBy); the app sends it with Duna selected.
        var joined = NOW.minusSeconds(7200);
        var brucsOffer = new WaitlistEntry("entry-bruc", "club-a", "class-a", "dog-bruc", "member-laura", "account-laura", joined, WaitlistState.NOTIFIED, 1,
                NOW.minusSeconds(600), null, null, null, null, null, STARTS, "2026-10-04", 1L, joined, "account-laura", NOW.minusSeconds(600), null);
        when(waitlist.findById("entry-bruc")).thenReturn(Optional.of(brucsOffer));

        assertThatThrownBy(() -> service.hold(laura, "class-a", "dog-duna", "entry-bruc"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        verify(holds, never()).upsert(any());
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    static BookingChecks.Subject subject() {
        var session = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"), "18:00", "19:00", STARTS,
                STARTS.plusSeconds(3600), "ring-1", List.of(), List.of("instructor-neus"), 2, 1, 0, false, null, 3L);
        var dog = new BookingMemberAccess.Dog("dog-duna", "Duna", "FEMALE", "member-laura", null, "ACTIVE");
        var owner = new BookingMemberAccess.Member("member-laura", "account-laura", "Laura", "Laura Serra", "ACTIVE", null, false, null, null, null,
                "ca", "laura@example.test", List.of());
        var week = new BookingWeeks.Week("2026-10-04", Instant.parse("2026-10-04T18:00:00Z"), Instant.parse("2026-10-11T18:00:00Z"));
        return new BookingChecks.Subject(session, dog, owner, owner, week, RelativeWeek.CURRENT, Optional.empty());
    }

    static Booking booking(String id, String dogId, String memberId) {
        var booked = NOW.minusSeconds(86_400);
        var account = memberId.replace("member-", "account-");
        return new Booking(id, "club-a", "class-a", dogId, memberId, BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, "Soci"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, account, booked, account);
    }
}
