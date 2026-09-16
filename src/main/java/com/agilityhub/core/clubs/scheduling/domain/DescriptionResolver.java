package com.agilityhub.core.clubs.scheduling.domain;

import java.util.*;

/** The caller supplies ICU formatting, keeping this rule independent of Spring and IO. */
public final class DescriptionResolver {
    @FunctionalInterface public interface Messages { String format(String key, Map<String, ?> variables, Locale locale); }
    private final Messages messages;
    public DescriptionResolver(Messages messages) { this.messages = messages; }
    public String resolve(String manual, List<String> levelIds, SchedulingCatalog catalog, Locale locale) {
        if (manual != null && !manual.isBlank()) { return manual; }
        var selected = catalog.orderedLevels().stream().filter(level -> levelIds.contains(level.id())).toList();
        if (selected.isEmpty()) { return ""; }
        var active = catalog.activeLevels();
        int first = active.indexOf(selected.getFirst());
        boolean tail = selected.size() >= 2 && first >= 0 && active.subList(first, active.size()).equals(selected);
        if (tail) { return messages.format("scheduling.description.andAbove", Map.of("level", selected.getFirst().name().resolve(locale).value()), locale); }
        String join = messages.format("scheduling.description.join", Map.of(), locale);
        return String.join(join, selected.stream().map(level -> level.name().resolve(locale).value()).toList());
    }
}
