package com.agilityhub.core.clubs.messaging.application.engine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * COMM-07 (owner decision of 13 Nov 2026, S11 R-11-13): the pace of announcement e-mails (N-24, «Enviar comunicat», a `CUSTOM`
 * template included), set to the e-mail provider's plan with `ANNOUNCEMENT_EMAILS_PER_MINUTE`
 * (`notifications.announcements.emails-per-minute`, 60 by default). At most that many announcement e-mails go in one clock
 * minute; the next ones wait for the following minute. Platform-wide, because the plan is the platform's one provider account,
 * and kept in this process (one API instance, ADR-003): a restart begins a fresh minute. Other e-mails are never paced.
 */
public final class AnnouncementEmailPace {
    /** The `lastError` of an announcement e-mail that waits for the next minute (still `QUEUED`, no attempt counted). */
    public static final String PACED = "Announcement e-mail pace";
    private final int perMinute;
    private Instant minute;
    private int used;

    public AnnouncementEmailPace(int perMinute) {
        if (perMinute < 1) { throw new IllegalArgumentException("ANNOUNCEMENT_EMAILS_PER_MINUTE must be at least 1, was " + perMinute); }
        this.perMinute = perMinute;
    }
    /** No pace at all (the dispatchers that tests build without one). */
    public static AnnouncementEmailPace unlimited() { return new AnnouncementEmailPace(Integer.MAX_VALUE); }

    public int perMinute() { return perMinute; }

    /**
     * One announcement e-mail about to go at `now`: empty when it may go (it takes one place of the minute), otherwise the start
     * of the next minute, when it may try again.
     */
    public synchronized Optional<Instant> acquire(Instant now) {
        var current = now.truncatedTo(ChronoUnit.MINUTES);
        if (!current.equals(minute)) { minute = current; used = 0; }
        if (used < perMinute) { used++; return Optional.empty(); }
        return Optional.of(current.plus(1, ChronoUnit.MINUTES));
    }
}
