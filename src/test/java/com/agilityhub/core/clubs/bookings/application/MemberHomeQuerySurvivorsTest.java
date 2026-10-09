package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.MemberActivityRowsPort;
import com.agilityhub.core.clubs.bookings.application.ports.MemberTrainingRowsPort;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.LimitUnit;
import com.agilityhub.core.clubs.bookings.domain.WaitlistState;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.NotificationFeedCounts;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MemberHomeQuery} (S08 R-08-22, R-08-23; T-08-12): a class that has already ended is not
 * a future row, a waiting-list row is titled with its class's description, and rows starting together are ordered by
 * type, then by id.
 */
class MemberHomeQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    /** Wednesday 7 October, 18:00 Madrid. */
    static final Instant STARTS = Instant.parse("2026-10-07T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final MemberDogs dogs = mock(MemberDogs.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final WaitlistEntryRepository waitlist = mock(WaitlistEntryRepository.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final BookingViews views = mock(BookingViews.class);
    final MemberTrainingRowsPort training = mock(MemberTrainingRowsPort.class);
    final MemberActivityRowsPort activities = mock(MemberActivityRowsPort.class);
    final NotificationFeedCounts feed = mock(NotificationFeedCounts.class);
    final IcuMessageSource messages = mock(IcuMessageSource.class);
    final MemberHomeQuery query = new MemberHomeQuery(context, census, dogs, bookings, waitlist, classes, views, training, activities, feed, messages);

    @BeforeEach void setUp() {
        when(context.now()).thenReturn(NOW);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        when(context.unit()).thenReturn(LimitUnit.DOG);
        when(context.enabled(Module.WAITLIST)).thenReturn(true);
        when(census.member("member-laura")).thenReturn(Optional.of(new BookingMemberAccess.Member("member-laura", "account-laura", "Laura", "Laura Serra",
                "ACTIVE", null, false, null, null, null, "ca", "laura@example.test", List.of())));
        when(classes.labels(anyCollection(), any(Locale.class)))
                .thenReturn(Map.of("class-a", new ClassSessionBookingAccess.Labels("Grup A", "Pista 1", "#1E6091", List.of(), "Neus", "ring-1")));
        when(messages.format(eq("bookings.home.classTitle"), anyMap(), any(Locale.class)))
                .thenAnswer(inv -> "Classe " + ((Map<?, ?>) inv.getArgument(1)).get("description"));
    }

    @Test void T_08_12_aMemberTokenWithoutACensusMemberIsNotFound() {
        // A MEMBER-role account provisioned by club-as-code without a census member (ClubAccountService#provision →
        // MembershipService#setRoles keeps memberId null; e.g. seeds/club-canic.yaml `member@example.test` until seed:demo links
        // it): the token has no `memberId` claim (TokenService#access), so /me/home asks for null and BookingMemberAccess#member(null) is empty.
        when(census.member(null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> query.home(null, null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_08_12_anEndedClassIsNotAFutureRowAndAWaitingRowIsTitledWithItsClass() {
        chips("dog-duna", "Duna", "dog-bruc", "Bruc");
        // Duna's class this morning ended two hours ago (still ACTIVE until P2 finishes it); Duna is booked on Wednesday and
        // Bruc waits for the same class.
        var ended = booking("booking-0", "class-z", "dog-duna", NOW.minusSeconds(3 * 3600));
        when(bookings.forDogs(any(), eq(BookingRepository.LIVE), any(), isNull()))
                .thenReturn(List.of(ended, booking("booking-1", "class-a", "dog-duna", STARTS)));
        when(waitlist.liveForDogs(any(), eq(NOW))).thenReturn(List.of(entry("entry-1", "dog-bruc")));

        var rows = rows(query.home("member-laura", null));

        assertThat(rows).extracting(r -> r.get("id")).containsExactly("booking-1", "entry-1");
        assertThat(rows.get(1)).containsEntry("type", "CLASS_WAITLIST").containsEntry("title", "Classe Grup A").containsEntry("ringName", "Pista 1");
    }

    @Test void T_08_12_rowsStartingTogetherAreOrderedByTypeThenById() {
        chips("dog-duna", "Duna", "dog-bruc", "Bruc", "dog-toby", "Toby");
        // Duna and Bruc booked Wednesday's class (ids are UUIDs), Toby waits for it.
        when(bookings.forDogs(any(), eq(BookingRepository.LIVE), any(), isNull())).thenReturn(List.of(
                booking("b2f0c1d2-0000-4000-8000-000000000002", "class-a", "dog-duna", STARTS),
                booking("a1c3e5f7-0000-4000-8000-000000000001", "class-a", "dog-bruc", STARTS)));
        when(waitlist.liveForDogs(any(), eq(NOW))).thenReturn(List.of(entry("0d4e6f80-0000-4000-8000-000000000003", "dog-toby")));

        var rows = rows(query.home("member-laura", null));

        assertThat(rows).extracting(r -> r.get("id")).containsExactly(
                "a1c3e5f7-0000-4000-8000-000000000001", "b2f0c1d2-0000-4000-8000-000000000002", "0d4e6f80-0000-4000-8000-000000000003");
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    private void chips(String... idsAndNames) {
        var chips = new java.util.ArrayList<MemberDogs.Chip>();
        for (int i = 0; i < idsAndNames.length; i += 2) {
            chips.add(new MemberDogs.Chip(new BookingMemberAccess.Dog(idsAndNames[i], idsAndNames[i + 1], "MALE", "member-laura", null, "ACTIVE"), null, true, null));
        }
        when(dogs.chips("member-laura")).thenReturn(chips);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> home) { return (List<Map<String, Object>>) home.get("reservations"); }

    static Booking booking(String id, String classId, String dogId, Instant startsAt) {
        var booked = NOW.minusSeconds(86_400);
        return new Booking(id, "club-a", classId, dogId, "member-laura", BookingState.ACTIVE, BookingOrigin.APP, booked,
                new Booking.Actor("account-laura", null, "Laura"), startsAt, startsAt.plusSeconds(3600), "2026-10-04",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null,
                0L, booked, "account-laura", booked, "account-laura");
    }

    static WaitlistEntry entry(String id, String dogId) {
        var joined = NOW.minusSeconds(7200);
        return new WaitlistEntry(id, "club-a", "class-a", dogId, "member-laura", "account-laura", joined, WaitlistState.ACTIVE, 1, null, null, null,
                null, null, null, STARTS, "2026-10-04", 0L, joined, "account-laura", joined, "account-laura");
    }
}
