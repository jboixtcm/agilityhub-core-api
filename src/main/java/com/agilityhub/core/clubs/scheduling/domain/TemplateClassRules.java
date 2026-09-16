package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.DayOfWeek;
import java.util.*;

public final class TemplateClassRules {
    private TemplateClassRules() { }
    public static void validate(TemplateKind kind, Set<String> bandIds, String bandId, DayOfWeek day,
            List<String> instructors, String ringId, List<String> levels, String description,
            int maxInstructors, boolean levelsEnabled, SchedulingCatalog catalog) {
        if (!bandIds.contains(bandId) || !WeekTemplateRules.days(kind).contains(day)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (instructors.isEmpty() || instructors.size() > maxInstructors || new HashSet<>(instructors).size() != instructors.size()) {
            throw new ApiException(ErrorCode.TOO_MANY_INSTRUCTORS);
        }
        if (levelsEnabled && levels.isEmpty()) { throw new ApiException(ErrorCode.LEVEL_REQUIRED); }
        if (levels.isEmpty() && (description == null || description.isBlank())) { throw new ApiException(ErrorCode.DESCRIPTION_REQUIRED); }
        if (new HashSet<>(levels).size() != levels.size()
                || !catalog.levels().stream().map(SchedulingCatalog.Level::id).toList().containsAll(levels)
                || !catalog.instructors().stream().filter(SchedulingCatalog.Resource::active).map(SchedulingCatalog.Resource::id).toList().containsAll(instructors)
                || ringId != null && catalog.rings().stream().noneMatch(r -> r.id().equals(ringId) && r.active())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
