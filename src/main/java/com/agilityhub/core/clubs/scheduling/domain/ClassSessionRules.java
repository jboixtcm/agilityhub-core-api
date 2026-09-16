package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

public final class ClassSessionRules {
    private ClassSessionRules() { }
    public static void transition(ClassState from, ClassState to, ClassCancellationReason reason) {
        boolean allowed = switch (from) {
            case DRAFT -> to == ClassState.ACTIVE || to == ClassState.CANCELLED && reason == ClassCancellationReason.DELETED;
            case ACTIVE -> to == ClassState.FINISHED || to == ClassState.CANCELLED && reason != null;
            default -> false;
        };
        if (!allowed) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    public static void transition(WeekState from, WeekState to) {
        if (!(from == WeekState.PENDING && (to == WeekState.GENERATED || to == WeekState.VALIDATED)
                || from == WeekState.GENERATED && to == WeekState.VALIDATED)) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    public static void editable(ClassState state, Set<String> fields) {
        if ((state == ClassState.FINISHED || state == ClassState.CANCELLED) && !Set.of("notes").containsAll(fields)) {
            throw new ApiException(ErrorCode.INVALID_STATE);
        }
    }
    public static void times(LocalDate date, LocalTime start, LocalTime end, int slotMinutes, Map<DayOfWeek, WeekTemplateRules.Opening> opening) {
        if (!start.isBefore(end)) { throw new ApiException(ErrorCode.INVALID_TIME_RANGE); }
        if (start.getNano() != 0 || end.getNano() != 0 || start.toSecondOfDay() % (slotMinutes * 60) != 0 || end.toSecondOfDay() % (slotMinutes * 60) != 0) {
            throw new ApiException(ErrorCode.INVALID_SLOT_GRANULARITY);
        }
        var hours = opening.get(date.getDayOfWeek());
        if (hours == null || start.isBefore(hours.open()) || end.isAfter(hours.close())) { throw new ApiException(ErrorCode.OUTSIDE_OPENING_HOURS); }
    }
    public static void references(List<String> instructors, String ring, List<String> levels, String description,
            int maxInstructors, boolean levelsEnabled, SchedulingCatalog catalog) {
        TemplateClassRules.validate(TemplateKind.WEEKDAYS, Set.of("class"), "class", DayOfWeek.MONDAY,
                instructors, ring, levels, description, maxInstructors, levelsEnabled, catalog);
    }
}
