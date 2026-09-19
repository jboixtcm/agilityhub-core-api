package com.agilityhub.core.clubs.activities.domain;

import java.time.*;

public record ActivityTimes(Instant startsAt, Instant endsAt, Instant registrationOpensAt, Instant registrationClosesAt) {
    public static ActivityTimes of(LocalDate date, String start, String end, LocalDate from, LocalDate to, ZoneId zone) {
        return new ActivityTimes(date == null ? null : date.atTime(start == null ? LocalTime.MIDNIGHT : LocalTime.parse(start)).atZone(zone).toInstant(),
                date == null ? null : end == null ? date.plusDays(1).atStartOfDay(zone).toInstant() : date.atTime(LocalTime.parse(end)).atZone(zone).toInstant(),
                from == null ? null : from.atStartOfDay(zone).toInstant(), to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant());
    }
    public boolean registrationOpen(Instant now) { return registrationOpensAt != null && registrationClosesAt != null && !now.isBefore(registrationOpensAt) && now.isBefore(registrationClosesAt); }
}
