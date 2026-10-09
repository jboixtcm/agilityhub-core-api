package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.ActivityHistoryQuery;
import com.agilityhub.core.clubs.bookings.application.ports.TrainingHistoryQuery;
import com.agilityhub.core.clubs.bookings.domain.ActorRole;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.BookingCancelReason;
import com.agilityhub.core.clubs.bookings.domain.BookingOrigin;
import com.agilityhub.core.clubs.bookings.domain.BookingState;
import com.agilityhub.core.clubs.bookings.domain.HistoryRules;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.Booking;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link HistoryQuery} (S10 R-10-14 `GET /me/history`, screen 25): PENDING dogs left out, own
 * dogs first, the family owners read once, `levelCode` with `levels.enabled`, the dog name on each row, the id tie-break
 * and no `startsAt` on the wire, a member-cancelled training's detail, and the after-the-end «ha avisat» (R-10-05).
 */
class HistoryQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    /** Monday 2026-10-05 18:00–19:00 Madrid, already over. */
    static final Instant STARTED = Instant.parse("2026-10-05T16:00:00Z");

    final BookingContext context = mock(BookingContext.class);
    final BookingMemberAccess members = mock(BookingMemberAccess.class);
    final BookingRepository bookings = mock(BookingRepository.class);
    final AttendanceRepository attendances = mock(AttendanceRepository.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final TrainingHistoryQuery trainings = mock(TrainingHistoryQuery.class);
    final HistoryQuery query = new HistoryQuery(context, members, bookings, attendances, mock(ClassSessionBookingAccess.class), catalogs, trainings,
            mock(ActivityHistoryQuery.class), mock(IcuMessageSource.class));

    static final BookingMemberAccess.Dog NALA = new BookingMemberAccess.Dog("dog-nala", "Nala", "F", "member-1", "level-ini", "ACTIVE");
    static final BookingMemberAccess.Dog BRUC = new BookingMemberAccess.Dog("dog-bruc", "Bruc", "M", "member-1", "level-ini", "ACTIVE");

    static Booking booking(String id, String dogId, BookingState state, Instant cancelledAt) {
        return new Booking(id, "club-1", "class-1", dogId, "member-1", state, BookingOrigin.APP, STARTED.minusSeconds(172_800),
                new Booking.Actor("account-1", null, "Laia"), STARTED, STARTED.plusSeconds(3600), "2026-10-04", cancelledAt,
                cancelledAt == null ? null : new Booking.Canceller("account-1", ActorRole.MEMBER, "Laia", null),
                cancelledAt == null ? null : BookingCancelReason.MEMBER, null, cancelledAt == null ? null : Boolean.FALSE,
                cancelledAt == null ? null : 1_440, null, null, null, null, null, null, null, 1L, STARTED.minusSeconds(172_800), "account-1",
                cancelledAt == null ? STARTED.minusSeconds(172_800) : cancelledAt, "account-1");
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> result, String key) { return (List<Map<String, Object>>) result.get(key); }

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.now()).thenReturn(NOW);
        when(context.today()).thenReturn(TODAY);
        when(context.integer("history.monthsVisible")).thenReturn(12);
        when(members.accessibleDogs("member-1")).thenReturn(List.of(NALA));
    }

    @Test void T_10_19_aPendingDogIsLeftOutOfTheHistory() {
        when(members.accessibleDogs("member-1")).thenReturn(List.of(NALA, new BookingMemberAccess.Dog("dog-bruc", "Bruc", "M", "member-1", null, "PENDING")));
        var result = query.history("member-1", null, null);
        assertThat(list(result, "dogs")).extracting(d -> d.get("id")).containsExactly("dog-nala");
        assertThat(result).containsEntry("showDog", false);
        verify(bookings).forDogs(eq(List.of("dog-nala")), any(), any(), any());
    }

    @Test void T_10_19_ownDogsComeFirstAndTheFamilyOwnersAreReadOnce() {
        var arlet = new BookingMemberAccess.Dog("dog-arlet", "Arlet", "F", "member-2", "level-ini", "ACTIVE");
        var zeta = new BookingMemberAccess.Dog("dog-zeta", "Zeta", "M", "member-1", "level-ini", "ACTIVE");
        when(members.accessibleDogs("member-1")).thenReturn(List.of(arlet, zeta));
        when(members.members(List.of("member-2"))).thenReturn(List.of(new BookingMemberAccess.Member("member-2", "account-2", "Joan", "Joan Prova",
                "ACTIVE", null, false, null, null, null, "ca", "joan@example.test", List.of())));
        var result = query.history("member-1", null, null);
        var dogs = list(result, "dogs");
        assertThat(dogs).extracting(d -> d.get("id")).containsExactly("dog-zeta", "dog-arlet");
        assertThat(dogs.get(1)).containsEntry("own", false).containsEntry("ownerFirstName", "Joan");
        assertThat(result).containsEntry("showDog", true);
        // Only the other members' first names are needed: the reader's own id is never part of the read.
        verify(members).members(List.of("member-2"));
    }

    @Test void T_10_19_aDogTheMemberCannotReachIsRefused() {
        assertThatThrownBy(() -> query.history("member-1", "dog-of-another-member", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DOG_NOT_ACCESSIBLE));
        verifyNoInteractions(bookings);
    }

    @Test void T_10_33_withLevelsEnabledEachDogCarriesItsLevelCode() {
        when(context.flag("levels.enabled")).thenReturn(true);
        when(catalogs.levelRefs()).thenReturn(Map.of("level-ini", new PlanningCatalogAccess.LevelRef("level-ini", "INI",
                new LocalizedText(Map.of("ca", "Iniciació"), "ca"), true)));
        var dogs = list(query.history("member-1", null, null), "dogs");
        assertThat(dogs.getFirst()).containsEntry("levelCode", "INI");
    }

    @Test void T_10_19_eachClassRowCarriesItsDogNameAndNoSortKey() {
        when(bookings.forDogs(any(), any(), any(), any())).thenReturn(List.of(booking("booking-1", "dog-nala", BookingState.CANCELLED, STARTED.minusSeconds(86_400))));
        var items = list(query.history("member-1", null, null), "items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("id", "booking-1").containsEntry("dogName", "Nala").containsEntry("state", "CANCELLED")
                .doesNotContainKey("startsAt");
    }

    @Test void T_10_19_rowsStartingAtTheSameInstantAreOrderedById() {
        when(members.accessibleDogs("member-1")).thenReturn(List.of(NALA, BRUC));
        // The same class booked for both dogs, both cancelled in time the day before.
        when(bookings.forDogs(any(), any(), any(), any())).thenReturn(List.of(booking("booking-b", "dog-bruc", BookingState.CANCELLED, STARTED.minusSeconds(86_400)),
                booking("booking-a", "dog-nala", BookingState.CANCELLED, STARTED.minusSeconds(86_400))));
        var items = list(query.history("member-1", null, null), "items");
        assertThat(items).extracting(i -> i.get("id")).containsExactly("booking-a", "booking-b");
    }

    @Test @SuppressWarnings("unchecked")
    void T_10_19_aTrainingTheMemberCancelledCarriesTheByMemberDetail() {
        when(context.enabled(Module.FREE_TRAINING)).thenReturn(true);
        when(trainings.itemsFor(any(), any())).thenReturn(List.of(new TrainingHistoryQuery.Item("training-1", "dog-nala", STARTED, STARTED.plusSeconds(1800), "CANCELLED")));
        var items = list(query.history("member-1", null, HistoryQuery.Type.TRAINING), "items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("type", "TRAINING").containsEntry("state", "CANCELLED");
        assertThat((Map<String, Object>) items.getFirst().get("detail")).containsEntry("kind", "BY_MEMBER");
    }

    @Test @SuppressWarnings("unchecked")
    void T_10_07_aNoticeAfterTheClassEndShowsTheBookingAsCancelledLate() {
        var booking = booking("booking-1", "dog-nala", BookingState.ACTIVE, null);
        var noticeAt = Instant.parse("2026-10-05T17:30:00Z");
        when(bookings.forDogs(any(), any(), any(), any())).thenReturn(List.of(booking));
        // R-10-05: «ha avisat» saved after the class end is a record only; the booking stays ACTIVE.
        var attendance = new Attendance("attendance-1", "club-1", "booking-1", "class-1", LocalDate.parse("2026-10-05"), STARTED, STARTED.plusSeconds(3600),
                "dog-nala", "member-1", AttendanceState.NOTIFIED, noticeAt, new Attendance.Marker("account-instructor", ActorRole.INSTRUCTOR, "Pol"),
                new Attendance.Notice(noticeAt, true, null, false, false, true, BookingState.ACTIVE), null, List.of(), 1L, noticeAt, noticeAt);
        when(attendances.byBookings(List.of("booking-1"))).thenReturn(Map.of("booking-1", attendance));
        var items = list(query.history("member-1", null, HistoryQuery.Type.CLASS), "items");
        assertThat(items.getFirst()).containsEntry("state", "CANCELLED_LATE").containsEntry("counts", true);
        assertThat((Map<String, Object>) items.getFirst().get("detail")).containsEntry("kind", HistoryRules.DetailKind.INSTRUCTOR_NOTICE)
                .containsEntry("at", noticeAt).containsEntry("atLocal", "19:30");
    }
}
