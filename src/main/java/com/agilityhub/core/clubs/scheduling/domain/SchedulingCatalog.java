package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.domain.LocalizedText;
import java.util.*;

/** Immutable catalog input for planning rules; persistence stays outside the domain. */
public record SchedulingCatalog(List<Level> levels, List<Resource> rings, List<Resource> instructors) {
    public SchedulingCatalog { levels = List.copyOf(levels); rings = List.copyOf(rings); instructors = List.copyOf(instructors); }
    /** `progression` (S05 §3, E29): only progression levels count for the automatic «{first} i sup.» (R-06-03). */
    public record Level(String id, LocalizedText name, int order, int capacity, boolean active, boolean progression) {
        public Level(String id, LocalizedText name, int order, int capacity, boolean active) { this(id, name, order, capacity, active, true); }
    }
    public record Resource(String id, String name, boolean active) { }
    public List<Level> orderedLevels() { return levels.stream().sorted(Comparator.comparingInt(Level::order).thenComparing(Level::id)).toList(); }
    public List<Level> activeLevels() { return orderedLevels().stream().filter(Level::active).toList(); }
    /** The active levels of the progression in `order` (R-06-03: the contiguity of «{first} i sup.» is counted on these). */
    public List<Level> activeProgression() { return activeLevels().stream().filter(Level::progression).toList(); }
}
