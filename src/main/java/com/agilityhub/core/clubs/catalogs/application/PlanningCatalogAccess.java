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
    public record RingView(String id,String name,String shortName,String color,int order,boolean active,String activeSetupId) { }
    public List<RingView> rings() { return rings.findAll().stream().sorted(Comparator.comparingInt(Ring::order).thenComparing(Ring::id))
            .map(r -> new RingView(r.id(),r.name(),r.shortName(),r.color(),r.order(),r.active(),r.activeSetupId())).toList(); }
    /** S09 R-09-02/R-09-07: every ring with its free-training fields, in catalog order (`order`, then `name`). */
    public record TrainingRingView(String id, String name, String shortName, String color, int order, boolean active, boolean allowsFreeTraining,
            Integer trainingCapacity, String activeSetupId) { }
    public List<TrainingRingView> trainingRings() {
        return rings.findAll().stream().sorted(Comparator.comparingInt(Ring::order).thenComparing(Ring::name).thenComparing(Ring::id))
                .map(r -> new TrainingRingView(r.id(), r.name(), r.shortName(), r.color(), r.order(), r.active(), r.allowsFreeTraining(), r.trainingCapacity(), r.activeSetupId()))
                .toList();
    }
    /** S09 R-09-01: `Level.grantsFreeTraining` and the localized name shown in `eligibleDogs`. */
    public record TrainingLevelView(String id, LocalizedText name, boolean grantsFreeTraining, boolean active) { }
    public Map<String, TrainingLevelView> trainingLevels() {
        var result = new LinkedHashMap<String, TrainingLevelView>();
        levels.findAll().forEach(l -> result.put(l.id(), new TrainingLevelView(l.id(), l.name(), l.grantsFreeTraining(), l.active())));
        return result;
    }
    /** Seed-file references (E4-T05): level codes and ring short names are the club-as-code keys. */
    public Map<String, String> levelIdsByCode() {
        var result = new LinkedHashMap<String, String>(); levels.findAll().forEach(l -> result.put(l.code(), l.id())); return result;
    }
    public Map<String, String> ringIdsByShortName() {
        var result = new LinkedHashMap<String, String>(); rings().forEach(r -> result.put(r.shortName(), r.id())); return result;
    }
    /** Active instructor ids in their census member order, a stable order for deterministic demo rotation. */
    public List<String> activeInstructorIds() {
        return instructors.findAll().stream().filter(Instructor::active).sorted(Comparator.comparing(Instructor::memberId).thenComparing(Instructor::id))
                .map(Instructor::id).toList();
    }
    public List<String> instructorMembers(Collection<String> ids) { return instructors.findAll().stream().filter(i -> ids.contains(i.id())).map(Instructor::memberId).toList(); }
    public static int capacity(List<Integer> capacities, boolean levelsEnabled, int defaultCapacity) {
        return CapacityCalculator.forLevels(capacities, levelsEnabled, defaultCapacity);
    }
}
