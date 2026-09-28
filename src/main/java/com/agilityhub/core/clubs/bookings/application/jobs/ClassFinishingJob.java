package com.agilityhub.core.clubs.bookings.application.jobs;

import com.agilityhub.core.clubs.bookings.application.WaitlistService;
import com.agilityhub.core.clubs.bookings.application.ports.ActivityFinishingPort;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-18 P8 `class-finishing` (every minute, no module of its own, catch-up continuous; `now` = the tick's minute),
 * in this order: <b>(a)</b> with the WAITLIST module only (R-15-03: the step of a module that is off is left out), every
 * `WaitlistEntry` ACTIVE or NOTIFIED whose class has `startsAt ≤ now` — S06's own start, not the entry's copy — →
 * `CANCELLED{CLASS_STARTED}` silently (S08 R-08-16: no event, no notification; `WOULD_SWEEP {entryId}`); <b>(b)</b> every
 * `ClassSession` ACTIVE with `endsAt + classes.finishGraceMinutes ≤ now` → FINISHED with `finishedAt` (S06;
 * `WOULD_FINISH {classId}`), over the index `{clubId, state, endsAt}` — a class 18:50–19:50 finishes on the 20:05 tick;
 * <b>(c)</b> with ACTIVITIES on, every PUBLISHED activity that has ended → FINISHED (`WOULD_FINISH_ACTIVITY {activityId}`,
 * counter `activitiesFinished`; E65). No business event of its own (only the framework's `SchedulerRun`; step (c) keeps
 * S07's `ActivityFinished`). Each item is one runner transaction; idempotent by state; no rollback (FINISHED is terminal).
 * Counters `{swept, finished, activitiesFinished}`.
 */
@Component
public class ClassFinishingJob implements Job {
    static final String SWEEP = "SWEEP", FINISH = "FINISH", FINISH_ACTIVITY = "FINISH_ACTIVITY";
    private final WaitlistService waitlist; private final ClassSessionService sessions; private final ActivityFinishingPort activities;
    public ClassFinishingJob(WaitlistService waitlist, ClassSessionService sessions, ActivityFinishingPort activities) {
        this.waitlist = waitlist; this.sessions = sessions; this.activities = activities;
    }
    @Override public JobName name() { return JobName.CLASS_FINISHING; }

    @Override public List<JobItem> plan(JobContext context) {
        Instant now = context.scheduledFor();
        var items = new ArrayList<JobItem>();
        if (on(context, Module.WAITLIST)) {
            for (var entry : waitlist.startedBy(now)) {
                items.add(new JobItem("WaitlistEntry", entry.id(), SWEEP, Map.of("entryId", entry.id(), "classId", entry.classSessionId())));
            }
        }
        for (String classId : sessions.endedBy(cutoff(context))) { items.add(new JobItem("ClassSession", classId, FINISH, Map.of("classId", classId))); }
        if (on(context, Module.ACTIVITIES)) {
            for (String activityId : activities.endedBy(now)) { items.add(new JobItem("Activity", activityId, FINISH_ACTIVITY, Map.of("activityId", activityId))); }
        }
        return items;
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        Instant now = context.scheduledFor();
        // The item of a step whose module went off after the plan does nothing (R-15-03).
        boolean done = switch (item.action()) {
            case SWEEP -> on(context, Module.WAITLIST) && waitlist.sweepStarted(item.entityId(), now);
            case FINISH -> sessions.finish(item.entityId(), now, cutoff(context));
            case FINISH_ACTIVITY -> on(context, Module.ACTIVITIES) && activities.finish(item.entityId(), now);
            default -> throw new IllegalArgumentException("Unknown class-finishing action " + item.action());
        };
        if (!done) { return new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of()); }
        String counter = switch (item.action()) { case SWEEP -> "swept"; case FINISH -> "finished"; default -> "activitiesFinished"; };
        return new JobEffect(item.action(), item.detail(), Map.of(counter, 1L));
    }

    /** `now − classes.finishGraceMinutes`, recorded in the run's `parametersSnapshot`. */
    private static Instant cutoff(JobContext context) {
        return context.scheduledFor().minusSeconds(context.parameter("classes.finishGraceMinutes", Integer.class) * 60L);
    }
    private static boolean on(JobContext context, Module module) { return context.config().modules().contains(module); }
}
