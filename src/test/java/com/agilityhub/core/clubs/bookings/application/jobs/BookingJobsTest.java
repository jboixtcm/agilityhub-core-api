package com.agilityhub.core.clubs.bookings.application.jobs;

import com.agilityhub.core.clubs.bookings.application.NoShowNoticeClaims;
import com.agilityhub.core.clubs.bookings.application.WaitlistService;
import com.agilityhub.core.clubs.bookings.application.ports.ActivityFinishingPort;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E6-T04 unit cases of P3 and P8 that the ITs do not reach: every «not in scope» answer of `apply` (an item that changed
 * between the plan and its transaction is left alone, R-15-04), P8's step (c) module guard and an unknown action.
 */
class BookingJobsTest {
    static final LocalDate RUN = LocalDate.of(2026, 10, 9);
    static final Instant AT = Instant.parse("2026-10-09T06:00:00Z");
    private final JobRunRecorder recorder = mock(JobRunRecorder.class);

    private JobContext context(Set<Module> modules) {
        var config = mock(ClubConfig.class);
        when(config.modules()).thenReturn(modules);
        when(config.get("classes.finishGraceMinutes", Integer.class)).thenReturn(15);
        return new JobContext("club", ZoneId.of("Europe/Madrid"), AT, RUN, false, config, recorder, "run");
    }
    private static Attendance attendance(String id, AttendanceState state, LocalDate date, Attendance.NoShowNotice notice) {
        return new Attendance(id, "club", "b-" + id, "c", date, null, null, "d", "m", state, null, null, null, notice, List.of(), 0L, null, null);
    }

    @Test void T_15_16_p3LeavesAnItemThatLeftItsScopeBeforeItsTransaction() {
        var repository = mock(AttendanceRepository.class); var claims = mock(NoShowNoticeClaims.class);
        var job = new NoShowNoticesJob(repository, claims, mock(BookingMemberAccess.class));
        var context = context(Set.of());
        var item = new JobItem("Attendance", "a", "NOTIFY", Map.of("attendanceId", "a"));
        // Gone, or changed to PRESENT before the claim: never notified.
        when(repository.findById("a")).thenReturn(Optional.empty());
        assertThat(job.apply(context, item).action()).isEqualTo("NOT_IN_SCOPE");
        when(repository.findById("a")).thenReturn(Optional.of(attendance("a", AttendanceState.PRESENT, RUN.minusDays(1), null)));
        assertThat(job.apply(context, item).action()).isEqualTo("NOT_IN_SCOPE");
        // Queued by the batch an earlier item of the run claimed: traced, not counted again.
        when(repository.findById("a")).thenReturn(Optional.of(attendance("a", AttendanceState.NO_SHOW, RUN.minusDays(1),
                new Attendance.NoShowNotice(AT, "event", null))));
        var queued = job.apply(context, item);
        assertThat(queued.action()).isEqualTo("NOTIFY"); assertThat(queued.counters()).isEmpty();
        // A class of today (the plan ran on another day) and a claim that did not take it (claimed elsewhere).
        when(repository.findById("a")).thenReturn(Optional.of(attendance("a", AttendanceState.NO_SHOW, RUN, new Attendance.NoShowNotice(null, null, null))));
        assertThat(job.apply(context, item).action()).isEqualTo("NOT_IN_SCOPE");
        when(repository.findById("a")).thenReturn(Optional.of(attendance("a", AttendanceState.NO_SHOW, RUN.minusDays(2), null)));
        when(claims.claim(RUN)).thenReturn(new NoShowNoticeClaims.Claim(null, List.of(), List.of()));
        assertThat(job.apply(context, item).action()).isEqualTo("NOT_IN_SCOPE");
        verify(claims, times(1)).claim(RUN);
    }

    @Test void T_15_26_p8GuardsStepCAndItsItemsByStateAndRefusesAnUnknownAction() {
        var waitlist = mock(WaitlistService.class); var sessions = mock(ClassSessionService.class); var activities = mock(ActivityFinishingPort.class);
        var job = new ClassFinishingJob(waitlist, sessions, activities);
        when(activities.endedBy(AT)).thenReturn(List.of("act"));
        when(sessions.endedBy(any())).thenReturn(List.of());
        when(waitlist.startedBy(AT)).thenReturn(List.of());
        // ACTIVITIES off: step (c) is not planned, and an item of it (planned before the module went off) does nothing.
        var off = context(Set.of());
        assertThat(job.plan(off)).isEmpty();
        assertThat(job.apply(off, new JobItem("Activity", "act", "FINISH_ACTIVITY", Map.of("activityId", "act"))).action()).isEqualTo("NOT_IN_SCOPE");
        verify(activities, never()).finish(any(), any());
        var on = context(Set.of(Module.ACTIVITIES));
        assertThat(job.plan(on)).extracting(JobItem::entityId, JobItem::action).containsExactly(tuple("act", "FINISH_ACTIVITY"));
        when(activities.finish("act", AT)).thenReturn(true);
        assertThat(job.apply(on, new JobItem("Activity", "act", "FINISH_ACTIVITY", Map.of("activityId", "act"))).counters())
                .containsExactly(Map.entry("activitiesFinished", 1L));
        // Already swept or finished by an earlier run: nothing counted (idempotent by state).
        when(waitlist.sweepStarted("e", AT)).thenReturn(false);
        when(sessions.finish(any(), any(), any())).thenReturn(false);
        assertThat(job.apply(on, new JobItem("WaitlistEntry", "e", "SWEEP", Map.of("entryId", "e"))).action()).isEqualTo("NOT_IN_SCOPE");
        assertThat(job.apply(on, new JobItem("ClassSession", "c", "FINISH", Map.of("classId", "c"))).action()).isEqualTo("NOT_IN_SCOPE");
        assertThatThrownBy(() -> job.apply(on, new JobItem("ClassSession", "c", "OTHER", Map.of()))).isInstanceOf(IllegalArgumentException.class);
    }
}
