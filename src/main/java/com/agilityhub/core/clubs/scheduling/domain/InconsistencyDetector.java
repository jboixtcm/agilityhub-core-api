package com.agilityhub.core.clubs.scheduling.domain;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

/** The same input supports recurring templates and dated week items. Adjacent intervals do not overlap. */
public final class InconsistencyDetector {
    public enum Kind { CLASS, BLOCK, TRAINING }
    public record ScheduledItem(String id, Kind kind, DayOfWeek day, LocalDate date, LocalTime start, LocalTime end,
            String bandId, String ringId, List<String> instructorIds, List<String> levelIds) {
        public ScheduledItem { instructorIds = List.copyOf(instructorIds); levelIds = List.copyOf(levelIds); }
    }
    public record Inconsistency(String id, InconsistencyType type, DayOfWeek dayOfWeek, LocalDate date, String startTime,
            String bandId, String ringId, String instructorId, String levelId, List<String> itemIds, String message) { }
    private final DescriptionResolver.Messages messages;
    public InconsistencyDetector(DescriptionResolver.Messages messages) { this.messages = messages; }
    public List<Inconsistency> detect(List<ScheduledItem> input, SchedulingCatalog catalog, boolean freeTraining, Locale locale) {
        var items = input.stream().sorted(Comparator.comparing(ScheduledItem::id)).toList();
        var found = new ArrayList<Inconsistency>();
        for (int i = 0; i < items.size(); i++) {
            var left = items.get(i);
            if (left.kind() == Kind.CLASS) {
                for (String level : left.levelIds()) {
                    if (catalog.levels().stream().noneMatch(l -> l.id().equals(level) && l.active())) {
                        add(found, InconsistencyType.LEVEL_INACTIVE, left, null, null, null, level, catalog, locale);
                    }
                }
                if (left.ringId() != null && catalog.rings().stream().noneMatch(r -> r.id().equals(left.ringId()) && r.active())) {
                    add(found, InconsistencyType.RING_INACTIVE, left, null, left.ringId(), null, null, catalog, locale);
                }
                for (String instructor : left.instructorIds()) {
                    if (catalog.instructors().stream().noneMatch(r -> r.id().equals(instructor) && r.active())) {
                        add(found, InconsistencyType.INSTRUCTOR_INACTIVE, left, null, null, instructor, null, catalog, locale);
                    }
                }
            }
            for (int j = i + 1; j < items.size(); j++) {
                var right = items.get(j);
                if (left.day() != right.day() || !Objects.equals(left.date(), right.date())
                        || !left.start().isBefore(right.end()) || !right.start().isBefore(left.end())) { continue; }
                if (left.kind() == Kind.CLASS && right.kind() == Kind.CLASS) {
                    for (String instructor : new TreeSet<>(left.instructorIds())) {
                        if (right.instructorIds().contains(instructor)) {
                            add(found, InconsistencyType.INSTRUCTOR_DOUBLE_BOOKED, left, right, null, instructor, null, catalog, locale);
                        }
                    }
                }
                if (left.ringId() == null || !left.ringId().equals(right.ringId()) || left.kind() != Kind.CLASS && right.kind() != Kind.CLASS) { continue; }
                var other = left.kind() == Kind.CLASS ? right : left;
                var type = switch (other.kind()) {
                    case CLASS -> InconsistencyType.RING_DOUBLE_BOOKED;
                    case BLOCK -> InconsistencyType.RING_BLOCKED;
                    case TRAINING -> freeTraining ? InconsistencyType.RING_TRAINING_CONFLICT : null;
                };
                if (type != null) { add(found, type, left, right, left.ringId(), null, null, catalog, locale); }
            }
        }
        return found.stream().sorted(Comparator.comparing(Inconsistency::id)).toList();
    }
    private void add(List<Inconsistency> result, InconsistencyType type, ScheduledItem left, ScheduledItem right,
            String ringId, String instructorId, String levelId, SchedulingCatalog catalog, Locale locale) {
        var ids = right == null ? List.of(left.id()) : List.of(left.id(), right.id()).stream().sorted().toList();
        var first = right != null && right.start().isBefore(left.start()) ? right : left;
        String identity = type + "|" + String.join("|", ids) + "|" + Objects.toString(ringId, "") + "|"
                + Objects.toString(instructorId, "") + "|" + Objects.toString(levelId, "");
        String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        String level = catalog.levels().stream().filter(l -> l.id().equals(levelId)).map(l -> l.name().resolve(locale).value()).findFirst().orElse(Objects.toString(levelId, ""));
        var variables = new LinkedHashMap<String, Object>();
        variables.put("day", first.day().getDisplayName(TextStyle.FULL, locale)); variables.put("time", first.start().toString());
        variables.put("ring", name(catalog.rings(), ringId)); variables.put("instructor", name(catalog.instructors(), instructorId)); variables.put("level", level);
        variables.put("rings", com.ibm.icu.text.ListFormatter.getInstance(locale).format(right == null ? List.of(name(catalog.rings(), left.ringId()))
                : List.of(name(catalog.rings(), left.ringId()), name(catalog.rings(), right.ringId()))));
        result.add(new Inconsistency(id, type, first.day(), first.date(), first.start().toString(), first.bandId(), ringId,
                instructorId, levelId, ids, messages.format("scheduling.inconsistency." + type, variables, locale)));
    }
    private String name(List<SchedulingCatalog.Resource> resources, String id) {
        return resources.stream().filter(r -> r.id().equals(id)).map(SchedulingCatalog.Resource::name).findFirst().orElse(Objects.toString(id, "—"));
    }
}
