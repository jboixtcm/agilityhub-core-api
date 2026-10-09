package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess.ClassView;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.platform.application.Module;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link WeekAgendaQuery} (S10 R-10-15; T-10-20): `ringId` narrows the classes and the blocks,
 * the cells are ordered by date, start time, kind and id, a class carries its ring's name, and an administrator without
 * an instructor profile asking for `me` keeps `me` in the filters.
 */
class WeekAgendaQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate MONDAY = LocalDate.parse("2026-10-05");

    final BookingContext context = mock(BookingContext.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final InstructorScheduleAccess schedule = mock(InstructorScheduleAccess.class);
    final RingScheduleAccess rings = mock(RingScheduleAccess.class);
    final TrainingOccupancyPort occupancy = mock(TrainingOccupancyPort.class);
    final AttendanceStatusCalculator calculator = mock(AttendanceStatusCalculator.class);
    final WeekAgendaQuery query = new WeekAgendaQuery(context, catalogs, schedule, rings, occupancy, calculator);
    final AttendanceCaller neus = new AttendanceCaller("account-neus", "Neus", false, "instructor-neus");

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.today()).thenReturn(MONDAY);
        when(context.enabled(Module.ACTIVITIES)).thenReturn(true);
        when(catalogs.rings()).thenReturn(List.of(
                new PlanningCatalogAccess.RingView("ring-1", "Pista 1", "P1", "#1E6091", 1, true, null),
                new PlanningCatalogAccess.RingView("ring-2", "Pista 2", "P2", "#C0392B", 2, true, null),
                new PlanningCatalogAccess.RingView("ring-3", "Pista 3", "P3", "#27AE60", 3, true, null)));
    }

    @Test void T_10_20_ringIdNarrowsTheClassesAndTheBlocksToThatRing() {
        // Monday: a class on each ring and one without a ring; a maintenance block on rings 1 and 2.
        when(schedule.between(any(), any())).thenReturn(List.of(
                view("class-1", "18:00", ring(1)), view("class-2", "18:00", ring(2)), view("class-3", "19:00", null)));
        when(rings.blocks(any(), any(), eq(true))).thenReturn(List.of(block("rb-1", "ring-1", "15:00", "16:00"), block("rb-2", "ring-2", "15:00", "16:00")));

        assertThat(ids(query.week(MONDAY, null, "ring-1", neus))).containsExactly("rb-1", "class-1");
        assertThat(ids(query.week(MONDAY, null, null, neus))).containsExactly("rb-1", "rb-2", "class-1", "class-2", "class-3");
    }

    @Test void T_10_20_cellsAreOrderedByDateStartKindAndIdAndAClassCarriesItsRingName() {
        // S06 lists the classes by start (the two at 18:00 in any order); S06's blocks come by start too.
        when(schedule.between(any(), any())).thenReturn(List.of(
                view("class-c", "16:00", ring(1)), view("class-b", "18:00", ring(1)), view("class-a", "18:00", ring(2))));
        when(rings.blocks(any(), any(), eq(true))).thenReturn(List.of(block("rb-1", "ring-3", "15:00", "16:00"), block("rb-9", "ring-3", "16:00", "17:00")));

        var week = query.week(MONDAY, null, null, neus);

        assertThat(ids(week)).containsExactly("class-c", "rb-1", "rb-9", "class-a", "class-b");
        assertThat(week.get("rows")).isEqualTo(List.of("16:00", "17:00", "18:00"));
        assertThat(cells(week).stream().filter(c -> "class-a".equals(c.get("classId"))).findFirst().orElseThrow()).containsEntry("ringName", "Pista 2");
    }

    @Test void T_10_20_anAdministratorWithoutAnInstructorProfileAskingForMeKeepsMeInTheFilters() {
        var admin = new AttendanceCaller("account-admin", "Admin", true, null);

        var week = query.week(MONDAY, "me", null, admin);

        @SuppressWarnings("unchecked") var filters = (Map<String, Object>) week.get("filters");
        assertThat(filters).containsEntry("instructorId", "me");
        assertThat(cells(week)).isEmpty();
    }

    // --- fixtures ----------------------------------------------------------------------------------------------------------

    static InstructorScheduleAccess.Ring ring(int n) {
        return new InstructorScheduleAccess.Ring("ring-" + n, "Pista " + n, n == 1 ? "#1E6091" : "#C0392B");
    }

    /**
     * A Monday class, 1 hour long, 4 of 6 seats booked: Marc's on ring 2, Neus's otherwise, so no instructor teaches two
     * classes at once (InconsistencyDetector's INSTRUCTOR_DOUBLE_BOOKED).
     */
    static ClassView view(String id, String start, InstructorScheduleAccess.Ring ring) {
        var startsAt = MONDAY.atTime(java.time.LocalTime.parse(start)).atZone(MADRID).toInstant();
        var end = java.time.LocalTime.parse(start).plusHours(1).toString();
        boolean marc = ring != null && ring.id().equals("ring-2");
        return new ClassView(id, "ACTIVE", MONDAY, start, end, startsAt, startsAt.plusSeconds(3600), ring,
                List.of(marc ? "instructor-marc" : "instructor-neus"), marc ? "Marc" : "Neus", "Grup A", List.of(), 6, 4, 0, InstructorScheduleAccess.Summary.EMPTY);
    }

    static RingScheduleAccess.BlockInterval block(String id, String ringId, String fromUtc, String toUtc) {
        return new RingScheduleAccess.BlockInterval(id, ringId, Instant.parse("2026-10-05T" + fromUtc + ":00Z"), Instant.parse("2026-10-05T" + toUtc + ":00Z"),
                "BLOCK", "MAINTENANCE", null, "Admin", null);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cells(Map<String, Object> week) { return (List<Map<String, Object>>) week.get("cells"); }

    static List<String> ids(Map<String, Object> week) {
        return cells(week).stream().map(c -> Objects.toString(c.getOrDefault("classId", c.get("blockId")))).toList();
    }
}
