package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CapacityCalculator;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.util.*;
import org.springframework.stereotype.Service;

/** S06 read and reference-write boundary: no catalog persistence types escape. */
@Service
public class PlanningCatalogAccess {
    public record LevelView(String id, LocalizedText name, int order, int capacity, boolean active) { }
    public record ResourceView(String id, String name, boolean active) { }
    public record Snapshot(List<LevelView> levels, List<ResourceView> rings, List<ResourceView> instructors) { }
    private final CatalogRepository<Level> levels;
    private final CatalogRepository<Ring> rings;
    private final InstructorRepository instructors;
    public PlanningCatalogAccess(CatalogRepository<Level> levels, CatalogRepository<Ring> rings, InstructorRepository instructors) {
        this.levels = levels; this.rings = rings; this.instructors = instructors;
    }
    public void lockReferences() { levels.lock(); rings.lock(); instructors.lock(); }
    public Snapshot snapshot() {
        return new Snapshot(levels.findAll().stream().map(l -> new LevelView(l.id(), l.name(), l.order(), l.capacity(), l.active())).toList(),
                rings.findAll().stream().map(r -> new ResourceView(r.id(), r.name(), r.active())).toList(),
                instructors.findAll().stream().map(i -> new ResourceView(i.id(), i.shortName(), i.active())).toList());
    }
    public static int capacity(List<Integer> capacities, boolean levelsEnabled, int defaultCapacity) {
        return CapacityCalculator.forLevels(capacities, levelsEnabled, defaultCapacity);
    }
}
