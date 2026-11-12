package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.bookings.application.ports.MemberActivityRowsPort;
import com.agilityhub.core.clubs.bookings.application.ports.PackBalancePort;
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.clubs.bookings.domain.BookableRow;
import com.agilityhub.core.clubs.bookings.domain.BookingLimits;
import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.bookings.domain.LimitUnit;
import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntryRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.support.MockClock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit PERF-01 (S08 screen 04, R-08-03, R-08-17): `GET /me/bookable-classes` reads the owner's inactivity and the dog's pack
 * once per class date instead of once per class, and every class of a date gets the same answer as before.
 */
class BookableClassesQueryReadsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");
    static final LocalDate TUESDAY = LocalDate.parse("2026-10-06"), WEDNESDAY = LocalDate.parse("2026-10-07");

    final BookingContext context = mock(BookingContext.class);
    final BookableClassesCache base = mock(BookableClassesCache.class);
    final BookingChecks checks = mock(BookingChecks.class);
    final BookingViews views = mock(BookingViews.class);
    final PackBalancePort packs = mock(PackBalancePort.class);
    final InactivityPort inactivity = mock(InactivityPort.class);
    final BookableClassesQuery query = new BookableClassesQuery(context, mock(BookingMemberAccess.class), mock(MemberDogs.class), base, checks,
            mock(BookingRepository.class), mock(WaitlistEntryRepository.class), views, packs, inactivity, mock(SingleClassChargePort.class),
            mock(MemberActivityRowsPort.class), new MockClock(NOW));

    private static ClassSessionBookingAccess.Session session(String id, LocalDate date, String start, String end) {
        var startsAt = date.atTime(LocalTime.parse(start)).atZone(MADRID).toInstant();
        var endsAt = date.atTime(LocalTime.parse(end)).atZone(MADRID).toInstant();
        return new ClassSessionBookingAccess.Session(id, "ACTIVE", date, start, end, startsAt, endsAt, "ring-a", List.of(), List.of("instructor-a"),
                10, 0, 0, false, null, 1L);
    }

    @Test void PERF_01_inactivityAndPackAreReadOncePerClassDate() {
        when(context.zone()).thenReturn(MADRID);
        when(context.clubId()).thenReturn("club-a");
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.parse("20:00")), MADRID));
        when(context.enabled(Module.PACKS)).thenReturn(true);
        when(context.enabled(Module.INACTIVITY)).thenReturn(true);
        var sessions = List.of(session("class-1", TUESDAY, "18:00", "19:00"), session("class-2", TUESDAY, "19:00", "20:00"),
                session("class-3", WEDNESDAY, "18:00", "19:00"), session("class-4", WEDNESDAY, "19:00", "20:00"),
                session("class-5", WEDNESDAY, "20:00", "21:00"));
        when(base.get()).thenReturn(new BookableClassesCache.Base("2026-10-05", NOW, Instant.parse("2026-10-26T00:00:00Z"), NOW, sessions));
        when(checks.limit(anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(new BookingLimits.Result(false, LimitUnit.DOG, 0, 2, List.of(), List.of()));
        when(views.labels(any())).thenReturn(new ClassSessionBookingAccess.Labels("Agility", "Pista 1", "#2E7D32", List.of(), "Marc"));
        when(inactivity.covering("member-a", TUESDAY)).thenReturn(Optional.of(new InactivityPort.Period(TUESDAY, TUESDAY)));
        when(inactivity.covering("member-a", WEDNESDAY)).thenReturn(Optional.empty());
        when(packs.balance(eq("member-a"), eq("dog-a"), any(LocalDate.class)))
                .thenReturn(Optional.of(new PackBalancePort.Balance("pack-a", 10, 9, 1, LocalDate.parse("2026-12-31"))));
        var dog = new BookingMemberAccess.Dog("dog-a", "Duna", "F", "member-a", null, "ACTIVE");
        var owner = new BookingMemberAccess.Member("member-a", "account-a", "Laura", "Laura Example", "ACTIVE", null, false, null, "plan-a",
                null, "ca", "laura@example.test", List.of());

        List<Map<String, Object>> rows = ReflectionTestUtils.invokeMethod(query, "classes", dog, owner, false,
                Optional.<PackBalancePort.Balance>empty(), Optional.<SingleClassChargePort.Terms>empty(), NOW);

        assertThat(rows).extracting(row -> row.get("id"), row -> row.get("notBookableReason")).containsExactly(
                tuple("class-1", BookableRow.NotBookable.INACTIVITY), tuple("class-2", BookableRow.NotBookable.INACTIVITY),
                tuple("class-3", null), tuple("class-4", null), tuple("class-5", null));
        verify(inactivity).covering("member-a", TUESDAY);
        verify(inactivity).covering("member-a", WEDNESDAY);
        verifyNoMoreInteractions(inactivity);
        verify(packs).balance("member-a", "dog-a", TUESDAY);
        verify(packs).balance("member-a", "dog-a", WEDNESDAY);
        verifyNoMoreInteractions(packs);
    }
}
