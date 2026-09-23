package com.agilityhub.core.clubs.bookings.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * S08 R-08-10/R-08-11. `late = now > classStartsAt − bookings.lateCancelThresholdMinutes` (the threshold itself is
 * still in time); `minutesBefore` is the whole minutes left, negative once the class has started. The waiting list
 * is told only when WAITLIST is on, strictly more than `waitlist.notifyThresholdMinutes` remain and live entries exist.
 */
public final class CancellationPolicy {
    public record Outcome(boolean late, int minutesBefore) { }
    private CancellationPolicy() { }
    public static Outcome evaluate(Instant classStartsAt, Instant now, int lateThresholdMinutes) {
        boolean late = now.isAfter(classStartsAt.minus(Duration.ofMinutes(lateThresholdMinutes)));
        return new Outcome(late, minutesBefore(classStartsAt, now));
    }
    public static int minutesBefore(Instant classStartsAt, Instant now) {
        return (int) Math.floorDiv(Duration.between(now, classStartsAt).getSeconds(), 60);
    }
    public static boolean notifyWaitlist(boolean waitlistModule, Instant classStartsAt, Instant now, int notifyThresholdMinutes, boolean liveEntries) {
        return waitlistModule && liveEntries && Duration.between(now, classStartsAt).compareTo(Duration.ofMinutes(notifyThresholdMinutes)) > 0;
    }
}
