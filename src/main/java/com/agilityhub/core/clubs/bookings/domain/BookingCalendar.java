package com.agilityhub.core.clubs.bookings.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * S08 R-08-08 calendar links and the `.ics` body. Only class data and the dog's name travel: no member, e-mail
 * or phone. Times are UTC (`Z`) so every calendar client converts them to its own zone. INC-40 (E11-T10), RFC 5545: the
 * `UID` never changes, `STATUS` (§3.8.1.11) says whether the booking still stands and `SEQUENCE` (§3.8.7.4) only grows, so a
 * calendar that imported the file replaces the event when it is fetched again (a cancelled booking or class →
 * `STATUS:CANCELLED`); `DTSTAMP` is when the file was produced.
 */
public final class BookingCalendar {
    public enum Status { TENTATIVE, CONFIRMED, CANCELLED }
    public record Event(String uid, String title, String location, Instant startsAt, Instant endsAt, Instant stamp, long sequence, Status status) {
        /** The links' event: only the times, the title and the place matter there. */
        public Event(String uid, String title, String location, Instant startsAt, Instant endsAt, Instant stamp) {
            this(uid, title, location, startsAt, endsAt, stamp, 0, Status.CONFIRMED);
        }
    }
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private BookingCalendar() { }

    public static String google(Event e) {
        return "https://calendar.google.com/calendar/render?action=TEMPLATE&text=" + q(e.title()) + "&dates=" + UTC.format(e.startsAt()) + "/"
                + UTC.format(e.endsAt()) + "&location=" + q(e.location());
    }
    public static String outlook(Event e) {
        return "https://outlook.live.com/calendar/0/deeplink/compose?path=%2Fcalendar%2Faction%2Fcompose&rru=addevent&subject=" + q(e.title())
                + "&startdt=" + q(e.startsAt().toString()) + "&enddt=" + q(e.endsAt().toString()) + "&location=" + q(e.location());
    }
    public static String ics(Event e) {
        return String.join("\r\n", "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//AgilityHub//Bookings//EN", "CALSCALE:GREGORIAN", "METHOD:PUBLISH",
                "BEGIN:VEVENT", "UID:" + e.uid(), "DTSTAMP:" + UTC.format(e.stamp()), "SEQUENCE:" + e.sequence(), "STATUS:" + e.status(),
                "DTSTART:" + UTC.format(e.startsAt()), "DTEND:" + UTC.format(e.endsAt()), "SUMMARY:" + text(e.title()), "LOCATION:" + text(e.location()),
                "END:VEVENT", "END:VCALENDAR") + "\r\n";
    }
    /**
     * The `STATUS` of a booking: CANCELLED for every cancelled state (CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB) and when the
     * class itself is cancelled; TENTATIVE while a PAY_TO_BOOK booking waits for its payment (R-08-18); CONFIRMED otherwise.
     */
    public static Status status(BookingState state, boolean classCancelled) {
        if (classCancelled) { return Status.CANCELLED; }
        return switch (state) {
            case CANCELLED, CANCELLED_LATE, CANCELLED_BY_CLUB -> Status.CANCELLED;
            case PAYMENT_PENDING -> Status.TENTATIVE;
            case ACTIVE -> Status.CONFIRMED;
        };
    }
    private static String q(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    /** RFC 5545 §3.3.11 TEXT escaping. */
    static String text(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }
}
