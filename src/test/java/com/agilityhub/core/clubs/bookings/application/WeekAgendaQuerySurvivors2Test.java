package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.ports.TrainingOccupancyPort;
import com.agilityhub.core.platform.application.Module;
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
 * E11-T06 PIT survivors of {@link WeekAgendaQuery} (S10 R-10-15; T-10-20), line 71: a ring block created by an activity
 * (reason ACTIVITY) is a cell of the week only with the ACTIVITIES module on; every other block is always listed. An
 * activity's blocks stay in S06 when the module is switched off later, so both states happen with real data.
 */
class WeekAgendaQuerySurvivors2Test {
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
        when(catalogs.rings()).thenReturn(List.of(
                new PlanningCatalogAccess.RingView("ring-1", "Pista 1", "P1", "#1E6091", 1, true, null),
                new PlanningCatalogAccess.RingView("ring-2", "Pista 2", "P2", "#C0392B", 2, true, null)));
        // Monday 5 October, no class: an activity's block on ring 1 (10:00-12:00) and a maintenance block on ring 2 (17:00-18:00),
        // as RingScheduleAccess#blocks gives them to staff (S06 writes an activity's block with no note).
        when(rings.blocks(any(), any(), eq(true))).thenReturn(List.of(
                new RingScheduleAccess.BlockInterval("rb-activity", "ring-1", Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T10:00:00Z"),
                        "BLOCK", "ACTIVITY", null, "Admin", "activity-1"),
                new RingScheduleAccess.BlockInterval("rb-maintenance", "ring-2", Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-05T16:00:00Z"),
                        "BLOCK", "MAINTENANCE", "Sorra nova", "Admin", null)));
    }

    @Test void T_10_20_activityBlocksAreCellsOnlyWithActivitiesAndEveryOtherBlockAlways() {
        when(context.enabled(Module.ACTIVITIES)).thenReturn(true);
        assertThat(blockIds(query.week(MONDAY, null, null, neus))).containsExactly("rb-activity", "rb-maintenance");

        when(context.enabled(Module.ACTIVITIES)).thenReturn(false);
        assertThat(blockIds(query.week(MONDAY, null, null, neus))).containsExactly("rb-maintenance");
    }

    @SuppressWarnings("unchecked")
    static List<Object> blockIds(Map<String, Object> week) {
        return ((List<Map<String, Object>>) week.get("cells")).stream().filter(c -> "BLOCK".equals(c.get("kind"))).map(c -> c.get("blockId")).toList();
    }
}
