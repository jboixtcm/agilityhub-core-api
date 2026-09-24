package com.agilityhub.core.clubs.training.domain;

import java.time.*;

/**
 * S09 pure rules: the right to train alone (R-09-01), the booking window (R-09-04) and the cancellation threshold
 * (R-09-10, instant arithmetic, immune to DST). Nothing here names a weekday or a club value: every figure comes
 * from a parameter.
 */
public final class TrainingRules {
    public enum RightSource { LEVEL, MANUAL }
    private TrainingRules() { }

    /**
     * `canFreeTrain = dog.status == ACTIVE ∧ (dog.freeTrainingOverride ?? level.grantsFreeTraining)`; with
     * `levels.enabled = false` only an explicit override `true` grants it (null = no).
     */
    public static boolean canFreeTrain(boolean dogActive, Boolean override, boolean levelsEnabled, boolean levelGrants) {
        if (!dogActive) { return false; }
        return override != null ? override : levelsEnabled && levelGrants;
    }
    public static RightSource rightSource(Boolean override) { return override == null ? RightSource.LEVEL : RightSource.MANUAL; }

    /** R-09-04: `date ∈ [today, today + training.bookingWindowDays]` (club-local natural days). */
    public static boolean inWindow(LocalDate date, LocalDate today, int windowDays) {
        return !date.isBefore(today) && !date.isAfter(today.plusDays(windowDays));
    }
    /** R-09-04 + §13-11: a slot that has already started is never bookable, for anyone. */
    public static boolean bookable(boolean free, Instant startsAt, Instant now, LocalDate date, LocalDate today, int windowDays) {
        return free && startsAt.isAfter(now) && inWindow(date, today, windowDays);
    }

    /** R-09-10: the last instant a member may cancel, `startsAt − training.cancelThresholdMinutes`. */
    public static Instant cancellableUntil(Instant startsAt, int thresholdMinutes) { return startsAt.minus(Duration.ofMinutes(thresholdMinutes)); }
    public static boolean inTime(Instant startsAt, Instant now, int thresholdMinutes) { return !now.isAfter(cancellableUntil(startsAt, thresholdMinutes)); }
    /** Whole minutes left before the slot (negative once started). */
    public static int minutesBefore(Instant startsAt, Instant now) { return (int) Math.floorDiv(Duration.between(now, startsAt).getSeconds(), 60); }
}
