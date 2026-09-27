package com.agilityhub.core.clubs.bookings.application.ports;

import java.time.Instant;
import java.util.List;

/**
 * S07 → S15 P8 step (c) (E6-T04, assumption in force: no S15 row claims E4-T04's `finishEnded`, so the per-minute
 * `class-finishing` job runs it with the ACTIVITIES module on): the PUBLISHED activities that have ended, and finishing
 * one of them. Adapter in `clubs.activities.application` (activities depends on bookings, never the reverse); nothing
 * without that context.
 */
public interface ActivityFinishingPort {
    /** Ids of the PUBLISHED activities whose end is before {@code now}, in a stable order; nothing written. */
    List<String> endedBy(Instant now);
    /** PUBLISHED → FINISHED with `finishedAt` and S07's `ActivityFinished`, in the caller's transaction; false when out of scope. */
    boolean finish(String activityId, Instant now);
}
