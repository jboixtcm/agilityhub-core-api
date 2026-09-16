package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

public final class WeekTemplateRules {
    private WeekTemplateRules() { }
    public record Band(String id, LocalTime start, LocalTime end) { }
    public record Opening(LocalTime open, LocalTime close) { }
    public static List<DayOfWeek> days(TemplateKind kind) {
        return kind == TemplateKind.SATURDAY ? List.of(DayOfWeek.SATURDAY)
                : List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
    }
    public static LocalTime time(String value) {
        try { return LocalTime.parse(value); }
        catch (DateTimeException | NullPointerException invalid) { throw new ApiException(ErrorCode.INVALID_TIME_RANGE); }
    }
    public static void band(Band next, List<Band> existing, TemplateKind kind, int slotMinutes, Map<DayOfWeek, Opening> opening) {
        if (!next.start().isBefore(next.end())) { throw new ApiException(ErrorCode.INVALID_TIME_RANGE); }
        if (next.start().toSecondOfDay() % (slotMinutes * 60) != 0 || next.end().toSecondOfDay() % (slotMinutes * 60) != 0) {
            throw new ApiException(ErrorCode.INVALID_SLOT_GRANULARITY);
        }
        for (var band : existing) {
            if (!band.id().equals(next.id()) && next.start().isBefore(band.end()) && band.start().isBefore(next.end())) {
                throw new ApiException(ErrorCode.BAND_OVERLAP);
            }
        }
        for (var day : days(kind)) {
            var hours = opening.get(day);
            if (hours == null || next.start().isBefore(hours.open()) || next.end().isAfter(hours.close())) {
                throw new ApiException(ErrorCode.OUTSIDE_OPENING_HOURS);
            }
        }
    }
    public static void removable(boolean hasClasses) { if (hasClasses) { throw new ApiException(ErrorCode.BAND_NOT_EMPTY); } }
}
