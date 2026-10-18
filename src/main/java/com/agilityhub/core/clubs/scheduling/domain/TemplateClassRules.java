package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.DayOfWeek;
import java.util.*;

public final class TemplateClassRules {
    private TemplateClassRules() { }
    /**
     * The references a class already has (S05 R-05-03/07/12, R-06-05): a level, instructor or ring deactivated since then
     * stays on the class (an inconsistency, which never prevents saving); only a newly chosen one must be active.
     */
    public record Kept(List<String> instructorIds, String ringId, List<String> levelIds) {
        public static final Kept NONE = new Kept(List.of(), null, List.of());
        public Kept { instructorIds = List.copyOf(instructorIds); levelIds = List.copyOf(levelIds); }
    }
    /** A new class: every reference must be active. */
    public static void validate(TemplateKind kind, Set<String> bandIds, String bandId, DayOfWeek day,
            List<String> instructors, String ringId, List<String> levels, String description,
            int maxInstructors, boolean levelsEnabled, SchedulingCatalog catalog) {
        validate(kind, bandIds, bandId, day, instructors, ringId, levels, description, maxInstructors, levelsEnabled, catalog, Kept.NONE);
    }
    public static void validate(TemplateKind kind, Set<String> bandIds, String bandId, DayOfWeek day,
            List<String> instructors, String ringId, List<String> levels, String description,
            int maxInstructors, boolean levelsEnabled, SchedulingCatalog catalog, Kept kept) {
        if (!bandIds.contains(bandId) || !WeekTemplateRules.days(kind).contains(day)) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        // INC-40 (E11-T10), S06 §3: `instructorIds` 1..max without repeats; R-06-02: TOO_MANY_INSTRUCTORS only above the maximum.
        // An empty list gets the code the POST's Bean Validation (@NotEmpty) already answers, so a PATCH says the same.
        if (instructors.isEmpty()) { throw invalid("instructorIds", "NotEmpty"); }
        if (new HashSet<>(instructors).size() != instructors.size()) { throw invalid("instructorIds", "INVALID_VALUE"); }
        if (instructors.size() > maxInstructors) { throw new ApiException(ErrorCode.TOO_MANY_INSTRUCTORS); }
        if (levelsEnabled && levels.isEmpty()) { throw new ApiException(ErrorCode.LEVEL_REQUIRED); }
        if (levels.isEmpty() && (description == null || description.isBlank())) { throw new ApiException(ErrorCode.DESCRIPTION_REQUIRED); }
        var activeLevels = new HashMap<String, Boolean>(); catalog.levels().forEach(l -> activeLevels.put(l.id(), l.active()));
        if (new HashSet<>(levels).size() != levels.size() || !usable(levels, activeLevels, kept.levelIds())) { throw invalid("levelIds", "INVALID_VALUE"); }
        if (!usable(instructors, active(catalog.instructors()), kept.instructorIds())) { throw invalid("instructorIds", "INVALID_VALUE"); }
        if (ringId != null && !usable(List.of(ringId), active(catalog.rings()), kept.ringId() == null ? List.of() : List.of(kept.ringId()))) { throw invalid("ringId", "INVALID_VALUE"); }
    }
    /** INC-40 (E11-T10): every id is in the catalog and active, or already on the class (S05 R-05-03: the class keeps it). */
    private static boolean usable(List<String> ids, Map<String, Boolean> active, List<String> kept) {
        return ids.stream().allMatch(id -> active.containsKey(id) && (active.get(id) || kept.contains(id)));
    }
    private static Map<String, Boolean> active(List<SchedulingCatalog.Resource> resources) {
        var result = new HashMap<String, Boolean>(); resources.forEach(r -> result.put(r.id(), r.active())); return result;
    }
    private static ApiException invalid(String field, String code) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", field, "fieldErrors", List.of(Map.of("field", field, "code", code))));
    }
}
