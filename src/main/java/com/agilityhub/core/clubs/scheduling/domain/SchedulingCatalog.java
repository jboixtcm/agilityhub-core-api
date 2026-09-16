package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.LocalizedText;
import java.util.*;

/** Immutable catalog input for planning rules; persistence stays outside the domain. */
public record SchedulingCatalog(List<Level> levels, List<Resource> rings, List<Resource> instructors) {
    public SchedulingCatalog { levels = List.copyOf(levels); rings = List.copyOf(rings); instructors = List.copyOf(instructors); }
    public record Level(String id, LocalizedText name, int order, int capacity, boolean active) { }
    public record Resource(String id, String name, boolean active) { }
    public List<Level> orderedLevels() { return levels.stream().sorted(Comparator.comparingInt(Level::order).thenComparing(Level::id)).toList(); }
    public List<Level> activeLevels() { return orderedLevels().stream().filter(Level::active).toList(); }
}
