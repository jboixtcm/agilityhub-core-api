package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.WaitlistMode;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.SeatLockRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort.DeliveryView;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort.StoredNotification;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationSubject;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingNotificationFacts} (S08 §8, R-08-13, R-08-19; S10 §7; S15 R-15-14): a member who
 * books their own dog gets one N-04, N-36 tells BOOKED from CANCELLED, N-46 demotes under the seat lock only while an
 * offer is still NOTIFIED, N-19 is «sent» on an APP delivery or a provider-accepted e-mail only, N-13 names the dog and
 * R-11-16 keeps only ACTIVE future bookings.
 */
class BookingNotificationFactsSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");
    static final ClassSessionBookingAccess.Session SESSION = new ClassSessionBookingAccess.Session("class-a", "ACTIVE", LocalDate.parse("2026-10-07"),
            "18:00", "19:00", STARTS, STARTS.plusSeconds(3600), "ring-1", List.of("level-1"), List.of("instructor-neus"), 3, 3, 1, false, null, 3L);
    static final ClassSessionBookingAccess.Labels LABELS = new ClassSessionBookingAccess.Labels("Classe B+C", "Central", "#8FCE8F", List.of("C"),
            "Neus Prat", "ring-1");

    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingContext context = mock(BookingContext.class);
    final BookingViews views = mock(BookingViews.class);
    final IcuMessageSource messages = mock(IcuMessageSource.class);
    final WaitlistTransitions transitions = mock(WaitlistTransitions.class);
    final BookingTransactions transactions = mock(BookingTransactions.class);
    final SeatLockRepository locks = mock(SeatLockRepository.class);
    final BookingNotificationFacts facts = new BookingNotificationFacts(bookings, waitlist, attendances, census, classes, context, views, messages,
            transitions, transactions, locks);

    // --- N-04 (line 104) and N-36 (line 111) ------------------------------------------------------------------------------

    @Test void T_08_15_aMemberBookingTheirOwnDogGetsOneN04AndAGroupBookerASecondOne() {
        stubN04();
        var own = booking("booking-1", "account-laura", BookingOrigin.APP, BookingState.ACTIVE);
        var byPau = booking("booking-2", "account-pau", BookingOrigin.APP, BookingState.ACTIVE);
        when(bookings.findById("booking-1")).thenReturn(Optional.of(own));
        when(bookings.findById("booking-2")).thenReturn(Optional.of(byPau));
        when(census.memberByAccount("account-laura")).thenReturn(Optional.of(member("member-laura", "account-laura", "Laura")));
        when(census.memberByAccount("account-pau")).thenReturn(Optional.of(member("member-pau", "account-pau", "Pau")));

        var self = facts.facts(trigger("BookingCreated", "booking-1", created(own)), "N-04").orElseThrow();
        var group = facts.facts(trigger("BookingCreated", "booking-2", created(byPau)), "N-04").orElseThrow();

        assertThat(self.members()).extracting(NotificationFacts.MemberSubject::memberId).containsExactly("member-laura");
        assertThat(group.members()).extracting(NotificationFacts.MemberSubject::memberId).containsExactly("member-laura", "member-pau");
    }

    @Test void T_08_27_n36TellsAClubBookingFromAClubCancellation() {
        when(classes.find("class-a")).thenReturn(Optional.of(SESSION));
        var booked = booking("booking-1", "account-admin", BookingOrigin.BACKOFFICE, BookingState.ACTIVE);
        // An admin impersonating Laura cancels her app booking (BookingActor#member while impersonating; BookingCancellationService
        // #cancel: reason MEMBER, by ADMIN, origin BACKOFFICE, the canceller keeps the impersonated member).
        var cancelled = booking("booking-2", "account-laura", BookingOrigin.APP, BookingState.CANCELLED,
                new Booking.Canceller("account-admin", ActorRole.ADMIN, "Admin", "member-laura"));
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booked));
        when(bookings.findById("booking-2")).thenReturn(Optional.of(cancelled));

        var created = facts.facts(trigger("BookingCreated", "booking-1", created(booked)), "N-36").orElseThrow();
        // BookingCancellationService#cancel's payload (BookingCancellationService.java:140-141).
        var byClub = facts.facts(trigger("BookingCancelled", "booking-2", Map.of("bookingId", "booking-2", "by", "ADMIN", "late", false,
                "minutesBefore", 3360, "origin", "BACKOFFICE", "reason", "MEMBER")), "N-36").orElseThrow();

        assertThat(created.values()).containsEntry("change", "BOOKED");
        assertThat(byClub.values()).containsEntry("change", "CANCELLED");
    }

    // --- N-46 (lines 184-186) ------------------------------------------------------------------------------------------------

    @Test void T_08_20_aLostOfferAlreadyDemotedIsToldWithoutAnyWrite() {
        stubAllAtOnce();
        // WaitlistConsolidated already demoted the offer (WaitlistTransitions#demoteIfFull keeps notifiedAt and offerNotifiedAt).
        when(waitlist.live("class-a")).thenReturn(List.of(entry(WaitlistState.ACTIVE)));

        var result = facts.facts(trigger("BookingCreated", "booking-9", booked("booking-9")), "N-46");

        assertThat(result).hasValueSatisfying(f -> assertThat(f.members()).extracting(NotificationFacts.MemberSubject::occurrence)
                .containsExactly("N-46:entry-1:" + NOTIFIED_AT.toEpochMilli()));
        verify(transactions, never()).write(any(), any());
        verify(locks, never()).lock(any());
    }

    @Test void T_08_20_anOfferStillNotifiedIsDemotedUnderTheSeatLockFirst() {
        stubAllAtOnce();
        when(waitlist.live("class-a")).thenReturn(List.of(entry(WaitlistState.NOTIFIED)), List.of(entry(WaitlistState.ACTIVE)));
        doAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get()).when(transactions).write(any(), any());
        when(transitions.demoteIfFull("class-a", BookingActor.system())).thenReturn(List.of("entry-1"));

        var result = facts.facts(trigger("BookingCreated", "booking-9", booked("booking-9")), "N-46");

        assertThat(result).hasValueSatisfying(f -> assertThat(f.members()).extracting(NotificationFacts.MemberSubject::memberId).containsExactly("member-eva"));
        verify(transactions).write(eq(List.of("class-a")), any());
        var order = inOrder(locks, transitions);
        order.verify(locks).lock("class-a");
        order.verify(transitions).demoteIfFull("class-a", BookingActor.system());
    }

    // --- N-19 «avís ja enviat» (lines 158, 164) ------------------------------------------------------------------------------

    @Test void T_10_26_storingAnN19MarksTheNoticeSentOnlyWhenTheAppDeliveredIt() {
        when(context.now()).thenReturn(NOW);
        when(attendances.byBookings(List.of("booking-1"))).thenReturn(Map.of("booking-1", noShow()));
        // NoShowNoticeClaims#claim: the batch event whose id the claimed attendance keeps in noShowNotice.eventId.
        var noShowDue = new NotificationTrigger("event-19", "NoShowNoticeDue", "club-a", "Attendance", "club-a", NOW,
                Map.of("attendanceIds", List.of("attendance-1"), "bookingIds", List.of("booking-1")), null, null, DomainEvent.Origin.SYSTEM);

        // A member without the app: only the e-mail, still queued.
        facts.stored(noShowDue, "N-19", List.of(stored("N-19", new DeliveryView("EMAIL", "QUEUED"))));
        verify(attendances, never()).noticeSent(any(), any(), any());

        facts.stored(noShowDue, "N-19", List.of(stored("N-19", new DeliveryView("APP", "DELIVERED"), new DeliveryView("EMAIL", "QUEUED"))));
        verify(attendances).noticeSent("attendance-1", "event-19", NOW);
    }

    @Test void T_10_26_anN19EmailAcceptedByTheProviderIsANoticeSent() {
        when(context.now()).thenReturn(NOW);
        when(attendances.byBookings(List.of("booking-1"))).thenReturn(Map.of("booking-1", noShow()));

        facts.sent(stored("N-13", new DeliveryView("EMAIL", "SENT")), "EMAIL");
        verify(attendances, never()).noticeSent(any(), any(), any());

        facts.sent(stored("N-19", new DeliveryView("EMAIL", "SENT")), "EMAIL");
        verify(attendances).noticeSent("attendance-1", "event-19", NOW);
    }

    // --- N-13 (line 235) and R-11-16 (line 252) ---------------------------------------------------------------------------

    @Test void T_15_17_theClassReminderIsAboutTheBookedDog() {
        var booking = booking("booking-1", "account-laura", BookingOrigin.APP, BookingState.ACTIVE);
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking));
        when(classes.find("class-a")).thenReturn(Optional.of(SESSION));
        // RemindersJob#apply: ReminderDue{bookingId, memberId, dogId, startsAt}.
        var due = trigger("ReminderDue", "booking-1", Map.of("bookingId", "booking-1", "memberId", "member-laura", "dogId", "dog-duna",
                "startsAt", STARTS.toString()));

        var result = facts.facts(due, "N-13").orElseThrow();

        assertThat(result.members()).singleElement().satisfies(m -> {
            assertThat(m.memberId()).isEqualTo("member-laura");
            assertThat(m.dogId()).isEqualTo("dog-duna");
        });
    }

    @Test void T_15_17_onlyAnActiveBookingNotStartedYetIsStillRelevant() {
        when(bookings.findById("booking-1")).thenReturn(Optional.of(booking("booking-1", "account-laura", BookingOrigin.APP, BookingState.ACTIVE)));
        when(bookings.findById("booking-2")).thenReturn(Optional.of(booking("booking-2", "account-laura", BookingOrigin.APP, BookingState.CANCELLED)));

        assertThat(facts.activeAndFuture("booking-1", NOW)).isTrue();
        assertThat(facts.activeAndFuture("booking-2", NOW)).isFalse();
        assertThat(facts.activeAndFuture("booking-1", STARTS)).as("the class has started").isFalse();
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    static final Instant NOTIFIED_AT = NOW.minusSeconds(1800);

    private void stubN04() {
        var config = mock(ClubConfig.class);
        var club = mock(ClubConfig.ClubView.class);
        when(club.defaultLocale()).thenReturn("ca");
        when(config.club()).thenReturn(club);
        when(context.config()).thenReturn(config);
        when(classes.find("class-a")).thenReturn(Optional.of(SESSION));
        when(classes.labels(eq(SESSION), any(Locale.class))).thenReturn(LABELS);
        when(views.calendarLinks(any(), any())).thenReturn(Map.<String, Object>of("ics", "https://canic.example.test/api/v1/bookings/b/calendar.ics?token=t"));
    }

    private void stubAllAtOnce() {
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(context.waitlistMode()).thenReturn(WaitlistMode.ALL_AT_ONCE);
        when(classes.find("class-a")).thenReturn(Optional.of(SESSION));
    }

    /** BookingConfirmationService#created's payload. */
    private static Map<String, Object> created(Booking b) {
        var payload = new LinkedHashMap<String, Object>(); payload.put("bookingId", b.id()); payload.put("classId", b.classSessionId());
        payload.put("memberId", b.memberId()); payload.put("dogId", b.dogId()); payload.put("origin", b.origin().name());
        return payload;
    }

    /** Marc's own booking of Nala that took the last seat (BookingConfirmationService#created's payload). */
    private static Map<String, Object> booked(String bookingId) {
        return Map.of("bookingId", bookingId, "classId", "class-a", "memberId", "member-marc", "dogId", "dog-nala", "origin", "APP");
    }

    private static NotificationTrigger trigger(String type, String aggregateId, Map<String, Object> payload) {
        return new NotificationTrigger("event-1", type, "club-a", "Booking", aggregateId, NOW, payload, null, null, DomainEvent.Origin.APP);
    }

    private static StoredNotification stored(String code, DeliveryView... deliveries) {
        return new StoredNotification("notification-1", code, "N-19".equals(code) ? "NoShowNoticeDue" : "ReminderDue", "MEMBER", "account-laura",
                "member-laura", NotificationSubject.booking("booking-1").with(NotificationSubject.classSession("class-past")), List.of(deliveries), true);
    }

    /**
     * A NO_SHOW mark of Sunday's class (16:00-17:00Z), saved by Neus after it ended, whose N-19 batch was claimed
     * (NoShowNoticeClaims: queuedAt + eventId, not sent yet).
     */
    private static Attendance noShow() {
        var markedAt = NOW.minusSeconds(50_000);
        return new Attendance("attendance-1", "club-a", "booking-1", "class-past", LocalDate.parse("2026-10-04"), STARTS.minusSeconds(259_200),
                STARTS.minusSeconds(255_600), "dog-duna", "member-laura", AttendanceState.NO_SHOW, markedAt,
                new Attendance.Marker("account-neus", ActorRole.INSTRUCTOR, "Neus"), null, new Attendance.NoShowNotice(NOW.minusSeconds(60), "event-19", null),
                List.of(new Attendance.Change(AttendanceState.NO_SHOW, markedAt, "account-neus")), 0L, markedAt, NOW.minusSeconds(60));
    }

    private static BookingMemberAccess.Member member(String id, String accountId, String firstName) {
        return new BookingMemberAccess.Member(id, accountId, firstName, firstName + " Serra", "ACTIVE", null, false, null, "plan-1", "dog-duna", "ca",
                accountId + "@example.test", List.of());
    }

    /**
     * Laura's dog Duna in class-a, booked by `bookerAccount` (a BACKOFFICE booking is an admin impersonating Laura, so `bookedBy`
     * keeps the impersonated member: BookingConfirmationService.java:89); a CANCELLED one carries its in-time cancellation.
     */
    private static Booking booking(String id, String bookerAccount, BookingOrigin origin, BookingState state) {
        return booking(id, bookerAccount, origin, state, new Booking.Canceller("account-laura", ActorRole.MEMBER, "Laura", null));
    }

    private static Booking booking(String id, String bookerAccount, BookingOrigin origin, BookingState state, Booking.Canceller canceller) {
        var booked = NOW.minusSeconds(86_400);
        boolean cancelled = state == BookingState.CANCELLED;
        return new Booking(id, "club-a", "class-a", "dog-duna", "member-laura", state, origin, booked,
                new Booking.Actor(bookerAccount, origin == BookingOrigin.BACKOFFICE ? "member-laura" : null, "Booker"), STARTS, STARTS.plusSeconds(3600), "2026-10-04",
                cancelled ? NOW : null, cancelled ? canceller : null,
                cancelled ? BookingCancelReason.MEMBER : null, null, cancelled ? Boolean.FALSE : null, cancelled ? 3360 : null,
                null, null, null, null, null, null, null,
                cancelled ? 2L : 1L, booked, bookerAccount, cancelled ? NOW : booked, cancelled ? canceller.accountId() : bookerAccount);
    }

    /** Eva's ALL_AT_ONCE offer for Kira, whose N-15 reached her (offerNotifiedAt = notifiedAt, no confirmBy). */
    private static WaitlistEntry entry(WaitlistState state) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry("entry-1", "club-a", "class-a", "dog-kira", "member-eva", "account-eva", joined, state, 1,
                NOTIFIED_AT, null, NOTIFIED_AT, null, null, null, STARTS, "2026-10-04", state == WaitlistState.NOTIFIED ? 1L : 2L, joined, "account-eva",
                state == WaitlistState.NOTIFIED ? NOTIFIED_AT : NOW.minusSeconds(60), null);
    }
}
