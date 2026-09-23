package com.agilityhub.core.platform.application.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * S15 R-15-01 catch-up column. Two windows end at a club-local boundary (end of day, end of month),
 * which a fixed Duration cannot express, hence an enum rather than `Duration catchUpWindow`.
 */
public enum CatchUpWindow {
    HOURS_24, END_OF_LOCAL_DAY, END_OF_LOCAL_MONTH, UNLIMITED, CONTINUOUS;

    public boolean covers(Instant scheduledFor, Instant now, ZoneId zone) {
        return switch (this) {
            case HOURS_24 -> Duration.between(scheduledFor, now).compareTo(Duration.ofHours(24)) <= 0;
            case END_OF_LOCAL_DAY -> scheduledFor.atZone(zone).toLocalDate().equals(now.atZone(zone).toLocalDate());
            case END_OF_LOCAL_MONTH -> YearMonth.from(scheduledFor.atZone(zone)).equals(YearMonth.from(now.atZone(zone)));
            case UNLIMITED, CONTINUOUS -> true;
        };
    }
}
