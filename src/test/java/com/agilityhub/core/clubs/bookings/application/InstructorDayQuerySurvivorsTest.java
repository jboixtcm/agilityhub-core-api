package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.scheduling.application.InstructorScheduleAccess;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
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
 * E11-T06 PIT survivors of {@link InstructorDayQuery} (S10 R-10-01; T-10-09): every ring block of the day is listed,
 * except the ACTIVITY ones while the ACTIVITIES module is off.
 */
class InstructorDayQuerySurvivorsTest {
    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final LocalDate DAY = LocalDate.parse("2026-10-05");

    final BookingContext context = mock(BookingContext.class);
    final PlanningCatalogAccess catalogs = mock(PlanningCatalogAccess.class);
    final InstructorScheduleAccess schedule = mock(InstructorScheduleAccess.class);
    final RingScheduleAccess rings = mock(RingScheduleAccess.class);
    final AttendanceStatusCalculator calculator = mock(AttendanceStatusCalculator.class);
    final InstructorDayQuery query = new InstructorDayQuery(context, catalogs, schedule, rings, calculator);
    final AttendanceCaller neus = new AttendanceCaller("account-neus", "Neus", false, "instructor-neus");

    @BeforeEach void setUp() {
        when(context.zone()).thenReturn(MADRID);
        when(context.today()).thenReturn(DAY);
        // Monday 5 October: an activity's block on ring 1 (10:00-12:00) and a maintenance block on ring 2 (17:00-18:00).
        var activity = new RingScheduleAccess.BlockInterval("rb-activity", "ring-1", Instant.parse("2026-10-05T08:00:00Z"), Instant.parse("2026-10-05T10:00:00Z"),
                "BLOCK", "ACTIVITY", null, "Admin", "activity-1");
        var maintenance = new RingScheduleAccess.BlockInterval("rb-maintenance", "ring-2", Instant.parse("2026-10-05T15:00:00Z"),
                Instant.parse("2026-10-05T16:00:00Z"), "BLOCK", "MAINTENANCE", "Sorra nova", "Admin", null);
        when(rings.blocks(any(), any(), eq(true))).thenReturn(List.of(activity, maintenance));
    }

    @Test void T_10_09_activityBlocksAreListedOnlyWithActivitiesAndEveryOtherBlockAlways() {
        when(context.enabled(Module.ACTIVITIES)).thenReturn(false);
        assertThat(blockIds(query.day(DAY, null, neus))).containsExactly("rb-maintenance");

        when(context.enabled(Module.ACTIVITIES)).thenReturn(true);
        assertThat(blockIds(query.day(DAY, null, neus))).containsExactly("rb-activity", "rb-maintenance");
    }

    @SuppressWarnings("unchecked")
    static List<Object> blockIds(Map<String, Object> day) {
        return ((List<Map<String, Object>>) day.get("ringBlocks")).stream().map(b -> b.get("id")).toList();
    }
}
