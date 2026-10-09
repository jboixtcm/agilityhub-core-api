package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingWeeks;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassCancellationUseCase;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.DemoSeedStep;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link DemoAttendanceSeeder} (E6-T04, S10 WP-10-G attendance half): every count key is
 * answered even without an `attendance` section, a missing sheet class, instructor member or history class fails with
 * NOT_FOUND (never a DRAFT class), and the week-0 cast books one second after the opening of its booking week.
 */
class DemoAttendanceSeederSurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate WEEK_START = LocalDate.parse("2026-10-12");
    static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    /** Monday 2026-10-12 18:00 Madrid; its booking week opens on Sunday 2026-10-11 20:00 Madrid (18:00Z). */
    static final Instant SHEET_STARTS = Instant.parse("2026-10-12T16:00:00Z");
    static final LocalDate PAST_DATE = LocalDate.parse("2026-10-05");
    static final DemoMembers.Candidate LIA = new DemoMembers.Candidate("member-lia", "account-lia", "Lia", "dog-nala", "level-ini");

    final BookingContext context = mock(BookingContext.class);
    final DemoMembers members = mock(DemoMembers.class);
    final ClassSessionService sessions = mock(ClassSessionService.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final DemoAttendanceSeeder seeder = new DemoAttendanceSeeder(context, members, sessions, classes, mock(ClassCancellationUseCase.class),
            mock(InstructorScheduleAccess.class), mock(AttendanceSheetService.class), mock(AttendanceCallers.class), mock(NoShowNoticeClaims.class),
            catalogs, census, new ObjectMapper().findAndRegisterModules());

    static Map<String, Object> section(List<Map<String, Object>> pastClasses) {
        var sheet = new LinkedHashMap<String, Object>();
        sheet.put("day", "MONDAY"); sheet.put("start", "18:00"); sheet.put("ring", "R1"); sheet.put("booked", List.of("Lia")); sheet.put("waiting", List.of());
        var history = new LinkedHashMap<String, Object>();
        history.put("end", "2026-10-11"); history.put("markedAt", "19:30"); history.put("noticeAt", "16:00"); history.put("classes", pastClasses);
        var section = new LinkedHashMap<String, Object>();
        section.put("cast", List.of(Map.of("name", "Lia", "member", 1, "dog", "Nala", "level", "INI")));
        section.put("sheet", sheet); section.put("history", history);
        return section;
    }
    static Map<String, Object> pastClass() {
        return Map.of("week", -1, "day", "MONDAY", "booked", List.of("Lia"), "marks", Map.of("Lia", "PRESENT"));
    }
    static DemoSeedStep.Input input(Map<String, Object> scenario) {
        return new DemoSeedStep.Input(Map.of("scenario", scenario), 42, WEEK_START, "account-admin", Set.of("member-login"),
                List.of("member-login", "member-lia"), TODAY, false);
    }
    static ClassSessionBookingAccess.Session session(String id, LocalDate date, Instant startsAt) {
        return new ClassSessionBookingAccess.Session(id, "ACTIVE", date, "18:00", "19:00", startsAt, startsAt.plusSeconds(3600), "ring-1",
                List.of("level-ini"), List.of("instructor-1"), 6, 0, 0, false, null, 2L);
    }

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        var config = mock(ClubConfig.class);
        when(config.get("messaging.noShowNoticeTime", String.class)).thenReturn("08:00");
        when(context.config()).thenReturn(config);
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("R1", "ring-1"));
        when(catalogs.levelIdsByCode()).thenReturn(Map.of("INI", "level-ini"));
        when(sessions.slot(WEEK_START, "18:00", "ring-1"))
                .thenReturn(Optional.of(new ClassSessionService.Slot("class-sheet", WEEK_START, "18:00", List.of("level-ini"), 6, "ACTIVE", false)));
        when(classes.require("class-sheet")).thenReturn(session("class-sheet", WEEK_START, SHEET_STARTS));
        when(catalogs.instructorMembers(List.of("instructor-1"))).thenReturn(List.of("member-instructor"));
        when(census.member("member-instructor")).thenReturn(Optional.of(new BookingMemberAccess.Member("member-instructor", "account-instructor", "Pol",
                "Pol Serra", "ACTIVE", null, false, null, null, null, "ca", "pol@example.test", List.of())));
        when(members.member("member-lia", "level-ini")).thenReturn(LIA);
    }

    @Test void E11_T06_aScenarioWithoutAnAttendanceSectionAnswersEveryCountAtZero() {
        var counts = seeder.apply(input(Map.of("classBookings", List.of())));
        var expected = new LinkedHashMap<String, Integer>();
        DemoAttendanceSeeder.COUNTS.forEach(key -> expected.put(key, 0));
        assertThat(counts).containsExactlyEntriesOf(expected);
        verifyNoInteractions(sessions, members, census);
    }

    @Test void E11_T06_aMissingSheetClassFailsWithNotFoundNamingTheSlot() {
        when(sessions.slot(WEEK_START, "18:00", "ring-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(Map.of("attendance", section(List.of())))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsEntry("slot", "MONDAY 18:00");
                });
    }

    @Test void E11_T06_anInstructorWithoutACensusMemberFailsWithNotFound() {
        when(census.member("member-instructor")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(Map.of("attendance", section(List.of())))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsEntry("instructor", "instructor-1");
                });
        verifyNoInteractions(members);
    }

    @Test void E11_T06_aHistoryClassLeftDraftIsNotFoundAndNobodyBooksIt() {
        when(sessions.slot(PAST_DATE, "18:00", "ring-1"))
                .thenReturn(Optional.of(new ClassSessionService.Slot("class-past", PAST_DATE, "18:00", List.of("level-ini"), 6, "DRAFT", false)));
        when(classes.require("class-past")).thenReturn(session("class-past", PAST_DATE, Instant.parse("2026-10-05T16:00:00Z")));
        assertThatThrownBy(() -> seeder.apply(input(Map.of("attendance", section(List.of(pastClass()))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsEntry("slot", "2026-10-05 18:00");
                });
        verify(members, never()).book(any(), any(), any());
    }

    @Test void E11_T06_aMissingHistoryClassFailsWithNotFoundNamingTheDate() {
        when(sessions.slot(PAST_DATE, "18:00", "ring-1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> seeder.apply(input(Map.of("attendance", section(List.of(pastClass()))))))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND); assertThat(e.details()).containsEntry("slot", "2026-10-05 18:00");
                });
    }

    @Test void E11_T06_theSheetCastBooksOneSecondAfterTheOpeningOfItsBookingWeek() {
        var counts = seeder.apply(input(Map.of("attendance", section(List.of()))));
        // opening() takes second 1 of the week opened 2026-10-11 18:00Z, the booking the next one.
        verify(members).book("class-sheet", LIA, Instant.parse("2026-10-11T18:00:02Z"));
        verify(members, times(1)).book(any(), any(), any());
        assertThat(counts).containsEntry("attendanceBookings", 1).containsEntry("attendanceWaitlist", 0);
    }
}
