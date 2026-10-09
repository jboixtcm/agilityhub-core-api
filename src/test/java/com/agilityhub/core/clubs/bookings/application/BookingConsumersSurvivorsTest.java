package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingConsumers} (S08 §7, §9, R-08-07, R-08-21): a real `ClubModulesChanged` (one
 * self-service module toggled) never reads as WAITLIST switched on; `ClassSessionUpdated` rewrites only
 * the stale denormalised copies, under the class's seat lock, with the version bumped by one.
 */
class BookingConsumersSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    /** Wednesday 2026-10-07 18:00 Madrid: booking week opened Sunday 2026-10-04 20:00 (key `2026-10-04`). */
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final String KEY = "2026-10-04";

    final BookingRepository bookings = mock(BookingRepository.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingContext context = mock(BookingContext.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final BookingConfirmationService confirmations = mock(BookingConfirmationService.class);
    final BookingCancellationService cancellations = mock(BookingCancellationService.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final BookingCounters counters = mock(BookingCounters.class);
    final BookingConsumers consumers = new BookingConsumers(bookings, classes, context, transactions, locks, confirmations, cancellations, waitlist, counters);

    @BeforeEach void setUp() {
        when(context.now()).thenReturn(NOW);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        // Full by its one live booking (WaitlistService#join needs a class full by bookings), one live entry.
        when(classes.find("class-a")).thenReturn(Optional.of(new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"),
                "18:00", "19:00", STARTS, STARTS.plusSeconds(3600), "ring-1", List.of("level-1"), List.of("instructor-neus"), 1, 1, 1, false, null, 7L)));
    }

    // --- ClubModulesChanged (line 49; lines 43 and 48 are triage reasons) ----------------------------------------------------

    /**
     * The only producer, `ClubSettingsService#updateModule`, toggles one self-service module (FAQ, PUSH, LEARN_LINK) and
     * sends both lists sorted by name; WAITLIST is not self-service, so a real event never switches it on.
     */
    @Test void E11_T06_aSelfServiceModuleToggleNeverSwitchesTheWaitlistOn() {
        assertThat(BookingConsumers.switchedOn(modules(List.of("FAQ", "WAITLIST"), List.of("FAQ", "PUSH", "WAITLIST")), "WAITLIST")).isFalse();
        assertThat(BookingConsumers.switchedOn(modules(List.of("FAQ", "PUSH", "WAITLIST"), List.of("PUSH", "WAITLIST")), "WAITLIST")).isFalse();
        assertThat(BookingConsumers.switchedOn(modules(List.of("FAQ"), List.of("FAQ", "LEARN_LINK")), "WAITLIST")).isFalse();
    }

    // --- ClassSessionUpdated (lines 77-92) -----------------------------------------------------------------------------------

    @Test void E11_T06_upToDateCopiesWriteNothing() {
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(booking(STARTS, 4L)));
        when(waitlist.live("class-a")).thenReturn(List.of(entry(STARTS, 3L)));

        consumers.refreshTimes("class-a");

        verify(transactions, never()).write(any(), any());
        verify(waitlist, never()).update(any(), anyLong());
        verify(bookings, never()).update(any(), anyLong());
    }

    @Test void E11_T06_aStaleWaitlistEntryAloneIsRewrittenUnderTheSeatLockWithItsVersionBumped() {
        // The entry (2nd in the queue) joined before the class moved; after the move the seat was released, offered (FIFO) to
        // entry-0 and claimed, so that booking already copies the new times. Its version stays 0 (insert, no later write).
        var stale = entry("entry-2", 2, STARTS.minusSeconds(3600), 3L);
        var claimed = booking("booking-2", "member-pau", "dog-nala", "Pau", STARTS, 0L, NOW.minusSeconds(600), "entry-0");
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(claimed));
        when(waitlist.live("class-a")).thenReturn(List.of(stale));
        when(waitlist.findById(stale.id())).thenReturn(Optional.of(stale));

        consumers.refreshTimes("class-a");

        verify(locks).lock("class-a");
        var updated = ArgumentCaptor.forClass(WaitlistEntry.class);
        verify(waitlist).update(updated.capture(), eq(3L));
        assertThat(updated.getValue().version()).isEqualTo(4L);
        assertThat(updated.getValue().classStartsAt()).isEqualTo(STARTS);
        assertThat(updated.getValue().bookingWeekKey()).isEqualTo(KEY);
    }

    @Test void E11_T06_aStaleBookingIsRewrittenWithItsVersionBumped() {
        // The booking was made before the class moved; the entry joined after the move, so it already copies the new start.
        var stale = booking(STARTS.minusSeconds(3600), 4L);
        when(bookings.forClass("class-a", BookingRepository.LIVE)).thenReturn(List.of(stale));
        when(bookings.require(stale.id())).thenReturn(stale);
        when(waitlist.live("class-a")).thenReturn(List.of(entry(STARTS, 0L)));

        consumers.refreshTimes("class-a");

        verify(waitlist, never()).update(any(), anyLong());
        verify(locks).lock("class-a");
        var updated = ArgumentCaptor.forClass(Booking.class);
        verify(bookings).update(updated.capture(), eq(4L));
        assertThat(updated.getValue().version()).isEqualTo(5L);
        assertThat(updated.getValue().classStartsAt()).isEqualTo(STARTS);
        assertThat(updated.getValue().classEndsAt()).isEqualTo(STARTS.plusSeconds(3600));
    }

    private static Map<String, Object> modules(List<String> before, List<String> after) {
        return Map.of("diff", Map.of("modules", Map.of("before", before, "after", after)));
    }

    private static Booking booking(Instant classStartsAt, long version) {
        return booking("booking-1", "member-laura", "dog-duna", "Laura", classStartsAt, version, NOW.minusSeconds(86_400), null);
    }

    private static Booking booking(String id, String memberId, String dogId, String bookerName, Instant classStartsAt, long version, Instant booked,
            String waitlistEntryId) {
        var account = memberId.replace("member-", "account-");
        return new Booking(id, "club-a", "class-a", dogId, memberId, BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor(account, null, bookerName), classStartsAt, classStartsAt.plusSeconds(3600), KEY,
                null, null, null, null, null, null,
                null, null, waitlistEntryId, null, null,
                null, null,
                version, booked, account, booked, account);
    }

    private static WaitlistEntry entry(Instant classStartsAt, long version) { return entry("entry-1", 1, classStartsAt, version); }

    /** An entry that joined a full class and was never notified (WaitlistService#join). */
    private static WaitlistEntry entry(String id, int position, Instant classStartsAt, long version) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", "dog-kira", "member-eva", "account-eva", joined, WaitlistState.ACTIVE, position,
                null, null, null, null, null, null, classStartsAt, KEY, version, joined, "account-eva", joined, "account-eva");
    }
}
