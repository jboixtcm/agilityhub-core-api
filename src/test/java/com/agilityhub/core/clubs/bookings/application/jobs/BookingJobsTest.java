package com.agilityhub.core.clubs.bookings.application.jobs;

import com.agilityhub.core.clubs.bookings.application.NoShowNoticeClaims;
import com.agilityhub.core.clubs.bookings.application.WaitlistService;
import com.agilityhub.core.clubs.bookings.application.ports.ActivityFinishingPort;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.bookings.persistence.WaitlistEntry;
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
 * E6-T04 unit cases of P3 and P8 that the ITs do not reach: P3's single item per club and day (round 2, review #4) and a
 * claim that finds nothing left; P8's module guards of steps (a) and (c) (round 2, review #2), items already done
 * (idempotent by state, R-15-04) and an unknown action.
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
        return new Attendance(id, "club", "b-" + id, "c", date, null, null, "d-" + id, "m-" + id, state, null, null, null, notice, List.of(), 0L, null, null);
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> detail) { return (List<Map<String, Object>>) detail.get("attendances"); }

    @Test void T_15_16_p3PlansOneItemPerClubAndDayAndTracesWhatItsSingleClaimTook() {
        var repository = mock(AttendanceRepository.class); var claims = mock(NoShowNoticeClaims.class); var census = mock(BookingMemberAccess.class);
        var job = new NoShowNoticesJob(repository, claims, census);
        var context = context(Set.of());
        // Nothing due: no item at all (a second run of the day plans nothing).
        when(repository.dueForNoShowNotice("club", RUN)).thenReturn(List.of());
        assertThat(job.plan(context)).isEmpty();
        // Three due: one item, keyed by the run's local date, with the attendances (and their dogs' names) in its detail.
        var late = attendance("a", AttendanceState.NO_SHOW, RUN.minusDays(3), null);
        var b = attendance("b", AttendanceState.NO_SHOW, RUN.minusDays(1), null);
        var c = attendance("c", AttendanceState.NO_SHOW, RUN.minusDays(1), null);
        when(repository.dueForNoShowNotice("club", RUN)).thenReturn(List.of(late, b, c));
        when(census.dogs(any())).thenReturn(List.of(new BookingMemberAccess.Dog("d-a", "Duna", "FEMALE", "m-a", null, "ACTIVE")));
        var plan = job.plan(context);
        assertThat(plan).singleElement().satisfies(item -> {
            assertThat(item.entityType()).isEqualTo("NoShowNoticeBatch"); assertThat(item.entityId()).isEqualTo("2026-10-09"); assertThat(item.action()).isEqualTo("NOTIFY");
            assertThat(item.detail()).containsEntry("date", "2026-10-09");
            assertThat(rows(item.detail())).extracting(r -> r.get("attendanceId")).containsExactly("a", "b", "c");
            assertThat(rows(item.detail()).getFirst()).containsEntry("bookingId", "b-a").containsEntry("memberId", "m-a").containsEntry("dogName", "Duna")
                    .containsEntry("classDate", "2026-10-06");
            assertThat(rows(item.detail()).get(1)).as("a dog the census no longer has").containsEntry("dogName", "");
        });
        // The claim took only b and a (c was changed before it): the trace and the counters are the claim's, in its order.
        when(claims.claim(RUN)).thenReturn(new NoShowNoticeClaims.Claim("event", List.of("a", "b"), List.of("b-a", "b-b")));
        when(repository.byIds(List.of("a", "b"))).thenReturn(List.of(b, late));
        var effect = job.apply(context, plan.getFirst());
        assertThat(effect.action()).isEqualTo("NOTIFY");
        assertThat(rows(effect.detail())).extracting(r -> r.get("attendanceId")).containsExactly("a", "b");
        assertThat(effect.counters()).isEqualTo(Map.of("notices", 2L, "late", 1L));
        // Everything claimed elsewhere meanwhile (the CLI claim takes no lease): nothing to notify, nothing counted.
        when(claims.claim(RUN)).thenReturn(new NoShowNoticeClaims.Claim(null, List.of(), List.of()));
        var none = job.apply(context, plan.getFirst());
        assertThat(none.action()).isEqualTo("NOT_IN_SCOPE"); assertThat(none.counters()).isEmpty();
        verify(claims, times(2)).claim(RUN);
    }

    @Test void T_15_26_p8GuardsStepsAAndCByTheirModulesItemsByStateAndRefusesAnUnknownAction() {
        var waitlist = mock(WaitlistService.class); var sessions = mock(ClassSessionService.class); var activities = mock(ActivityFinishingPort.class);
        var job = new ClassFinishingJob(waitlist, sessions, activities);
        var entry = new WaitlistEntry("e", "club", "c", "d", "m", null, AT, com.agilityhub.core.clubs.bookings.domain.WaitlistState.ACTIVE, 1, null, null, null, null, null,
                null, AT, null, null, AT, null, AT, null);
        when(activities.endedBy(AT)).thenReturn(List.of("act"));
        when(sessions.endedBy(any())).thenReturn(List.of());
        when(waitlist.startedBy(AT)).thenReturn(List.of(entry));
        // WAITLIST and ACTIVITIES off: steps (a) and (c) are not planned, and their items (planned before a module went off) do nothing.
        var off = context(Set.of());
        assertThat(job.plan(off)).isEmpty();
        verify(waitlist, never()).startedBy(any());
        assertThat(job.apply(off, new JobItem("WaitlistEntry", "e", "SWEEP", Map.of("entryId", "e"))).action()).isEqualTo("NOT_IN_SCOPE");
        verify(waitlist, never()).sweepStarted(any(String.class), any());
        assertThat(job.apply(off, new JobItem("Activity", "act", "FINISH_ACTIVITY", Map.of("activityId", "act"))).action()).isEqualTo("NOT_IN_SCOPE");
        verify(activities, never()).finish(any(), any());
        var on = context(Set.of(Module.WAITLIST, Module.ACTIVITIES));
        assertThat(job.plan(on)).extracting(JobItem::entityId, JobItem::action).containsExactly(tuple("e", "SWEEP"), tuple("act", "FINISH_ACTIVITY"));
        when(waitlist.sweepStarted("e", AT)).thenReturn(true);
        when(activities.finish("act", AT)).thenReturn(true);
        assertThat(job.apply(on, new JobItem("WaitlistEntry", "e", "SWEEP", Map.of("entryId", "e"))).counters()).containsExactly(Map.entry("swept", 1L));
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
