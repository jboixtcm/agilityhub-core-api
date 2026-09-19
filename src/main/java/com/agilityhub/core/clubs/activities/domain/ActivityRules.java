package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

public final class ActivityRules {
    public record Input(LocalizedText title, ActivityType type, boolean atClub, String locationName, List<String> ringIds,
                        LocalDate date, String startTime, String endTime, LocalDate registrationFrom, LocalDate registrationTo,
                        Integer minPlaces, Integer maxPlaces, List<String> levelIds) { }
    private ActivityRules() { }
    public static LocalizedText text(Map<String,String> values, String field, String defaultLocale, List<String> locales, int limit, boolean required) {
        if (values == null || values.isEmpty()) {
            if (required) invalid(field);
            return null;
        }
        if (!locales.containsAll(values.keySet())) throw new ApiException(ErrorCode.LOCALE_NOT_ENABLED, Map.of("field", field));
        if (!values.containsKey(defaultLocale) || values.values().stream().anyMatch(v -> v == null || v.isBlank() || v.length() > limit)) invalid(field);
        return new LocalizedText(values, defaultLocale);
    }
    public static void validate(Input a, int slotMinutes, Set<String> activeRings, Set<String> activeLevels) {
        if (a.title() == null || a.type() == null) invalid("title");
        if (!a.atClub() && !a.ringIds().isEmpty()) invalid("ringIds");
        if (!a.atClub() && (a.locationName() == null || a.locationName().isBlank())) invalid("location.name");
        if (a.ringIds().size() != new HashSet<>(a.ringIds()).size() || !activeRings.containsAll(a.ringIds())) invalid("ringIds");
        if (!activeLevels.containsAll(a.levelIds())) invalid("levelIds");
        LocalTime start = time(a.startTime()), end = time(a.endTime());
        if (start != null && end != null && !start.isBefore(end)) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        if (!a.ringIds().isEmpty()) for (LocalTime value : new LocalTime[]{start,end})
            if (value != null && (value.toSecondOfDay() % (slotMinutes * 60) != 0)) throw new ApiException(ErrorCode.INVALID_SLOT_GRANULARITY);
        if (a.registrationFrom() != null && a.registrationTo() != null && a.registrationFrom().isAfter(a.registrationTo())
                || a.registrationTo() != null && a.date() != null && a.registrationTo().isAfter(a.date())) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        if (a.minPlaces() != null && a.minPlaces() < 1 || a.maxPlaces() != null && a.maxPlaces() < 1
                || a.minPlaces() != null && a.maxPlaces() != null && a.minPlaces() > a.maxPlaces()) invalid("minPlaces");
    }
    public static void publish(Input a, LocalDate today) {
        var missing = new ArrayList<Map<String,String>>();
        if (a.date() == null) missing.add(Map.of("field", "date", "code", "REQUIRED"));
        if (a.registrationFrom() == null) missing.add(Map.of("field", "registrationFrom", "code", "REQUIRED"));
        if (a.registrationTo() == null) missing.add(Map.of("field", "registrationTo", "code", "REQUIRED"));
        if (!a.ringIds().isEmpty()) {
            if (a.startTime() == null) missing.add(Map.of("field", "startTime", "code", "REQUIRED"));
            if (a.endTime() == null) missing.add(Map.of("field", "endTime", "code", "REQUIRED"));
        }
        if (!missing.isEmpty()) throw new ApiException(ErrorCode.ACTIVITY_INCOMPLETE, Map.of("fieldErrors", missing));
        if (a.date().isBefore(today)) throw new ApiException(ErrorCode.ACTIVITY_IN_PAST);
    }
    public static LocalTime time(String value) {
        try { return value == null ? null : LocalTime.parse(value); }
        catch (DateTimeException invalid) { throw new ApiException(ErrorCode.INVALID_TIME_RANGE); }
    }
    public static void invalid(String field) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field",field,"code","INVALID")))); }
}
