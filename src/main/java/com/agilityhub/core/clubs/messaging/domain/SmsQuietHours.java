package com.agilityhub.core.clubs.messaging.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * COMM-06 (owner decision of 13 Nov 2026, S11 R-11-06): the SMS quiet hours of a club, `messaging.sms.quietHoursStart` to
 * `messaging.sms.quietHoursEnd` in the club's time zone (22:00–08:00 by default). A non-urgent SMS due inside the window waits
 * until its end; a seat offer with a deadline (N-15 with `confirm_by`, FIFO) never waits. The window may wrap midnight; equal
 * bounds mean no quiet hours. Instant arithmetic in the club's zone, so a DST change moves the end with the wall clock.
 */
public record SmsQuietHours(LocalTime start, LocalTime end) {
    /** The `lastError` of an SMS delivery that waits for the end of the quiet hours (still `QUEUED`). */
    public static final String QUIET = "SMS quiet hours";
    /** No quiet hours at all. */
    public static final SmsQuietHours NONE = new SmsQuietHours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

    public SmsQuietHours {
        start = start == null ? LocalTime.MIDNIGHT : start;
        end = end == null ? start : end;
    }

    /** The window the two `time` parameters give ("HH:mm"); a missing value means no quiet hours. */
    public static SmsQuietHours of(String start, String end) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) { return NONE; }
        return new SmsQuietHours(LocalTime.parse(start.strip()), LocalTime.parse(end.strip()));
    }

    /** Whether the club-local time falls inside the window (`start` included, `end` excluded). */
    public boolean contains(LocalTime time) {
        if (start.equals(end)) { return false; }
        return start.isBefore(end) ? !time.isBefore(start) && time.isBefore(end) : !time.isBefore(start) || time.isBefore(end);
    }

    /**
     * When a non-urgent SMS due at `now` may go: empty outside the window; inside it, the window's next end in the club's
     * zone (tomorrow's when `now` is already past today's end, as at 23:00 in a 22:00–08:00 window).
     */
    public Optional<Instant> deferUntil(Instant now, ZoneId zone) {
        var local = now.atZone(zone);
        if (!contains(local.toLocalTime())) { return Optional.empty(); }
        var day = local.toLocalTime().isBefore(end) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
        return Optional.of(ZonedDateTime.of(day, end, zone).toInstant());
    }

    /**
     * The SMS that never waits: a seat offer with a deadline, N-15 whose stored variables carry `confirm_by` (FIFO, S08
     * R-08-14). Every other SMS code waits, an N-15 without a deadline (ALL_AT_ONCE) included.
     */
    public static boolean urgent(String code, Map<String, ?> variables) {
        if (!"N-15".equals(code) || variables == null) { return false; }
        var deadline = variables.get("confirm_by");
        return deadline != null && !deadline.toString().isBlank();
    }
}
