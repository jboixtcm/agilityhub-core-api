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
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link DemoAttendanceSeeder} (E6-T04, S10 WP-10-G attendance half): the step runs
 * right after the census step that names its cast; the waiting cast of the week-0 sheet joins one second apart after the
 * booked cast, the class held at its booked count only while they join (R-08-12 needs it full by bookings) and put back
 * afterwards, and a sheet already full by its bookings is never edited.
 */
class DemoAttendanceSeederSurvivors3Test {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate WEEK_START = LocalDate.parse("2026-10-12");
    static final LocalDate TODAY = LocalDate.parse("2026-10-09");
    /** Monday 2026-10-12 18:00 Madrid; its booking week opens on Sunday 2026-10-11 20:00 Madrid (18:00Z). */
    static final Instant SHEET_STARTS = Instant.parse("2026-10-12T16:00:00Z");
    static final DemoMembers.Candidate LIA = new DemoMembers.Candidate("member-lia", "account-lia", "Lia", "dog-nala", "level-ini");
    static final DemoMembers.Candidate MAX = new DemoMembers.Candidate("member-max", "account-max", "Max", "dog-rock", "level-ini");
    static final DemoMembers.Candidate ONA = new DemoMembers.Candidate("member-ona", "account-ona", "Ona", "dog-kira", "level-ini");

    final BookingContext context = mock(BookingContext.class);
    final DemoMembers members = mock(DemoMembers.class);
    final ClassSessionService sessions = mock(ClassSessionService.class);
    final ClassSessionBookingAccess classes = mock(ClassSessionBookingAccess.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final BookingMemberAccess census = mock(BookingMemberAccess.class);
    final DemoAttendanceSeeder seeder = new DemoAttendanceSeeder(context, members, sessions, classes, mock(ClassCancellationUseCase.class),
            mock(InstructorScheduleAccess.class), mock(AttendanceSheetService.class), mock(AttendanceCallers.class), mock(NoShowNoticeClaims.class),
            catalogs, census, new ObjectMapper().findAndRegisterModules());

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.weeks()).thenReturn(new BookingWeeks(new BookingWeeks.Opening(DayOfWeek.SUNDAY, LocalTime.of(20, 0)), MADRID));
        var config = mock(ClubConfig.class);
        when(config.get("messaging.noShowNoticeTime", String.class)).thenReturn("08:00");
        when(context.config()).thenReturn(config);
        when(catalogs.ringIdsByShortName()).thenReturn(Map.of("R1", "ring-1"));
        when(catalogs.levelIdsByCode()).thenReturn(Map.of("INI", "level-ini"));
        when(classes.require("class-sheet")).thenReturn(new ClassSessionBookingAccess.Session("class-sheet", "ACTIVE", WEEK_START, "18:00", "19:00",
                SHEET_STARTS, SHEET_STARTS.plusSeconds(3600), "ring-1", List.of("level-ini"), List.of("instructor-1"), 6, 0, 0, false, null, 2L));
        when(catalogs.instructorMembers(List.of("instructor-1"))).thenReturn(List.of("member-instructor"));
        when(census.member("member-instructor")).thenReturn(Optional.of(new BookingMemberAccess.Member("member-instructor", "account-instructor", "Pol",
                "Pol Serra", "ACTIVE", null, false, null, null, null, "ca", "pol@example.test", List.of())));
        when(members.member("member-lia", "level-ini")).thenReturn(LIA);
        when(members.member("member-max", "level-ini")).thenReturn(MAX);
        when(members.member("member-ona", "level-ini")).thenReturn(ONA);
    }

    @Test void E11_T06_theAttendanceStepRunsRightAfterTheCensusStepThatNamesItsCast() {
        // DemoPlanningService runs the steps by order: DemoCastSeeder (36) names the cast, DemoFollowupSeeder (38) adds the tasks of the sheet.
        assertThat(seeder.order()).isEqualTo(37);
    }

    @Test void E11_T06_theWaitingCastJoinsOneSecondApartWhileTheSheetIsHeldAtItsBookedCount() {
        sheetSlot(6, false);

        seeder.apply(input(List.of("Max", "Ona")));

        // opening() takes second 1 of the week opened 2026-10-11 18:00Z, Lia's booking second 2, then one second per waiting member.
        var order = inOrder(members, sessions);
        order.verify(members).book("class-sheet", LIA, Instant.parse("2026-10-11T18:00:02Z"));
        order.verify(sessions).patch(eq("class-sheet"), eq(2L), eq(Map.<String, Object>of("capacity", 1)), eq(false));
        order.verify(members).join("class-sheet", MAX, Instant.parse("2026-10-11T18:00:03Z"));
        order.verify(members).join("class-sheet", ONA, Instant.parse("2026-10-11T18:00:04Z"));
        // Put back afterwards: the class's capacity is not manual, so it goes back to the level default (null).
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> patches = ArgumentCaptor.forClass(Map.class);
        verify(sessions, times(2)).patch(eq("class-sheet"), eq(2L), patches.capture(), eq(false));
        assertThat(patches.getAllValues().get(1)).containsEntry("capacity", null);
    }

    @Test void E11_T06_aSheetAlreadyFullByItsBookingsTakesItsWaitingCastWithoutACapacityEdit() {
        sheetSlot(1, false);

        var counts = seeder.apply(input(List.of("Max")));

        verify(members).join("class-sheet", MAX, Instant.parse("2026-10-11T18:00:03Z"));
        verify(sessions, never()).patch(any(), anyLong(), any(), anyBoolean());
        assertThat(counts).containsEntry("attendanceBookings", 1).containsEntry("attendanceWaitlist", 1);
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    private void sheetSlot(int capacity, boolean manualCapacity) {
        when(sessions.slot(WEEK_START, "18:00", "ring-1"))
                .thenReturn(Optional.of(new ClassSessionService.Slot("class-sheet", WEEK_START, "18:00", List.of("level-ini"), capacity, "ACTIVE", manualCapacity)));
    }

    /** The `scenario.attendance` section: Lia booked on the week-0 sheet, `waiting` on its waiting list, no history class. */
    private static DemoSeedStep.Input input(List<String> waiting) {
        var sheet = new LinkedHashMap<String, Object>();
        sheet.put("day", "MONDAY"); sheet.put("start", "18:00"); sheet.put("ring", "R1"); sheet.put("booked", List.of("Lia")); sheet.put("waiting", waiting);
        var history = new LinkedHashMap<String, Object>();
        history.put("end", "2026-10-11"); history.put("markedAt", "19:30"); history.put("noticeAt", "16:00"); history.put("classes", List.of());
        var section = new LinkedHashMap<String, Object>();
        section.put("cast", List.of(Map.of("name", "Lia", "member", 1, "dog", "Nala", "level", "INI"), Map.of("name", "Max", "member", 2, "dog", "Rock", "level", "INI"),
                Map.of("name", "Ona", "member", 3, "dog", "Kira", "level", "INI")));
        section.put("sheet", sheet); section.put("history", history);
        return new DemoSeedStep.Input(Map.of("scenario", Map.of("attendance", section)), 42, WEEK_START, "account-admin", Set.of("member-login"),
                List.of("member-login", "member-lia", "member-max", "member-ona"), TODAY, false);
    }
}
