package com.agilityhub.core.clubs.bookings.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * S08 R-08-08 calendar links and the `.ics` body. Only class data and the dog's name travel: no member, e-mail
 * or phone. Times are UTC (`Z`) so every calendar client converts them to its own zone.
 */
public final class BookingCalendar {
    public record Event(String uid, String title, String location, Instant startsAt, Instant endsAt, Instant stamp) { }
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
                "BEGIN:VEVENT", "UID:" + e.uid(), "DTSTAMP:" + UTC.format(e.stamp()), "DTSTART:" + UTC.format(e.startsAt()),
                "DTEND:" + UTC.format(e.endsAt()), "SUMMARY:" + text(e.title()), "LOCATION:" + text(e.location()), "END:VEVENT", "END:VCALENDAR") + "\r\n";
    }
    private static String q(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    /** RFC 5545 §3.3.11 TEXT escaping. */
    static String text(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
    }
}
