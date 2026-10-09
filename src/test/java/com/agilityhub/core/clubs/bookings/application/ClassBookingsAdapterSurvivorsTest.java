package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistCancelReason;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.BookingRef;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.CancellationEffects;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort.WaitlistRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link ClassBookingsAdapter} (S06 port served by S08, R-08-17, R-08-21; T-08-25): every requested
 * class is a key of the by-class reads, retained bookings and entries come back in the order of the ids, a ref tells
 * whether a pack paid the seat, and the club cancellation without text delegates to S08.
 */
class ClassBookingsAdapterSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final ClassBookingsAdapter adapter = new ClassBookingsAdapter(bookings, waitlist, cancellations);

    @Test void T_08_25_everyRequestedClassIsAKeyEvenWithoutLiveBookings() {
        var classIds = List.of("class-a", "class-b");
        when(bookings.forClasses(classIds, BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "class-a", BookingState.ACTIVE, null)));

        var byClass = adapter.activeBookingsByClass(classIds);

        assertThat(byClass).containsOnlyKeys("class-a", "class-b");
        assertThat(byClass.get("class-a")).containsExactly(new BookingRef("booking-1", "member-laura", "dog-duna", false));
        assertThat(byClass.get("class-b")).isEmpty();
    }

    @Test void T_08_25_retainedBookingsAndEntriesComeBackInTheOrderOfTheIds() {
        // `$in` gives no order.
        when(bookings.byIds(any())).thenReturn(List.of(booking("booking-1", "class-a", BookingState.CANCELLED_BY_CLUB, null),
                booking("booking-2", "class-a", BookingState.CANCELLED_BY_CLUB, null)));
        when(waitlist.byIds(any())).thenReturn(List.of(entry("entry-1"), entry("entry-2")));

        assertThat(adapter.bookings(List.of("booking-2", "booking-1"))).extracting(BookingRef::bookingId).containsExactly("booking-2", "booking-1");
        assertThat(adapter.waitlistEntries(List.of("entry-2", "entry-1"))).extracting(WaitlistRef::entryId).containsExactly("entry-2", "entry-1");
    }

    @Test void T_08_25_aRefTellsWhetherAPackPaidTheSeat() {
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking("booking-1", "class-a", BookingState.ACTIVE, "movement-1"),
                booking("booking-2", "class-a", BookingState.ACTIVE, null)));

        assertThat(adapter.activeBookings("class-a")).extracting(BookingRef::paidWithPack).containsExactly(true, false);
    }

    @Test void T_08_25_theClubCancelledBookingsOfAClassAreReadByState() {
        when(bookings.forClass("class-a", List.of(BookingState.CANCELLED_BY_CLUB)))
                .thenReturn(List.of(booking("booking-1", "class-a", BookingState.CANCELLED_BY_CLUB, "movement-1")));

        assertThat(adapter.clubCancelled("class-a")).containsExactly(new BookingRef("booking-1", "member-laura", "dog-duna", true));
    }

    @Test void T_08_25_aClubCancellationWithoutTextDelegatesToS08() {
        // ClassCancellationUseCase.java:31-32 requires the admin text as soon as the class has a live booking or entry: a
        // cancellation without text is one of a class nobody booked or waits for.
        when(cancellations.cancelByClub("class-a", "CLUB_MANUAL", null, "account-admin"))
                .thenReturn(new BookingCancellationService.ClubCancellation(List.of(), List.of()));

        var effects = adapter.cancelAllByClub("class-a", "CLUB_MANUAL", "account-admin");

        assertThat(effects).isEqualTo(new CancellationEffects(List.of(), List.of()));
        verify(cancellations).cancelByClub("class-a", "CLUB_MANUAL", null, "account-admin");
    }

    @Test void T_08_25_aClubCancellationWithTextReturnsTheCancelledRefs() {
        when(cancellations.cancelByClub("class-a", "CLUB_MANUAL", "Ring flooded", "account-admin")).thenReturn(new BookingCancellationService.ClubCancellation(
                List.of(booking("booking-1", "class-a", BookingState.CANCELLED_BY_CLUB, "movement-1")), List.of(cancelledEntry("entry-1"))));

        var effects = adapter.cancelAllByClub("class-a", "CLUB_MANUAL", "Ring flooded", "account-admin");

        assertThat(effects).isEqualTo(new CancellationEffects(List.of(new BookingRef("booking-1", "member-laura", "dog-duna", true)),
                List.of(new WaitlistRef("entry-1", "member-eva", "dog-kira"))));
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    /** booking-1 is Laura's Duna, booking-2 Marc's Nala: a dog has at most one live booking per class. */
    private static Booking booking(String id, String classId, BookingState state, String packMovementId) {
        var booked = NOW.minusSeconds(86_400);
        boolean byClub = state == BookingState.CANCELLED_BY_CLUB;
        boolean laura = id.equals("booking-1");
        String account = laura ? "account-laura" : "account-marc";
        // BookingCancellationService#cancelByClub: a canceller without display name, the admin's text as message, never late.
        return new Booking(id, "club-a", classId, laura ? "dog-duna" : "dog-nala", laura ? "member-laura" : "member-marc", state, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, laura ? "Laura" : "Marc"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                byClub ? NOW : null, byClub ? new Booking.Canceller("account-admin", ActorRole.ADMIN, null, null) : null,
                byClub ? BookingCancelReason.CLUB_CLASS_CANCELLED : null, byClub ? "Ring flooded" : null, byClub ? Boolean.FALSE : null, byClub ? 3360 : null,
                null, null, null, packMovementId, byClub && packMovementId != null ? "refund-" + id : null, null, null,
                byClub ? 1L : 0L, booked, account, byClub ? NOW : booked, byClub ? "account-admin" : account);
    }

    /** entry-1 is Eva's Kira (position 1), entry-2 Pau's Bruc (position 2). */
    private static WaitlistEntry entry(String id) {
        var joined = NOW.minusSeconds(7200);
        boolean eva = id.equals("entry-1");
        String account = eva ? "account-eva" : "account-pau";
        return new WaitlistEntry(id, "club-a", "class-a", eva ? "dog-kira" : "dog-bruc", eva ? "member-eva" : "member-pau", account, joined,
                WaitlistState.ACTIVE, eva ? 1 : 2, null, null, null, null, null, null, STARTS, "2026-10-04", 0L, joined, account, joined, account);
    }

    /** WaitlistTransitions#cancelAll of Eva's entry. */
    private static WaitlistEntry cancelledEntry(String id) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", "dog-kira", "member-eva", "account-eva", joined, WaitlistState.CANCELLED, 1,
                null, null, null, null, NOW, WaitlistCancelReason.CLASS_CANCELLED, STARTS, "2026-10-04", 1L, joined, "account-eva", NOW, "account-admin");
    }
}
