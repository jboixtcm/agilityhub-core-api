package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.training.domain.TrainingRules;
import com.agilityhub.core.shared.application.LocaleContext;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S09 R-09-01. The right is computed, never stored: `canFreeTrain = ACTIVE ∧ (freeTrainingOverride ??
 * level.grantsFreeTraining)`, only an explicit override with `levels.enabled = false`. A member's eligible dogs are
 * the own ones plus, with FAMILY_GROUP, those of the group members whose status is ACTIVE; own first, then by name.
 */
@Service
public class TrainingEligibilityService {
    public record EligibleDog(TrainingMemberAccess.Dog dog, TrainingMemberAccess.Member owner, boolean own, TrainingRules.RightSource source, String levelName) { }
    private final TrainingContext context; private final TrainingMemberAccess census; private final PlanningCatalogAccess catalogs;
    public TrainingEligibilityService(TrainingContext context, TrainingMemberAccess census, PlanningCatalogAccess catalogs) {
        this.context = context; this.census = census; this.catalogs = catalogs;
    }
    public boolean canFreeTrain(TrainingMemberAccess.Dog dog) { return canFreeTrain(dog, catalogs.trainingLevels(), context.flag("levels.enabled")); }
    private static boolean canFreeTrain(TrainingMemberAccess.Dog dog, Map<String, PlanningCatalogAccess.TrainingLevelView> levels, boolean levelsEnabled) {
        var level = dog.levelId() == null ? null : levels.get(dog.levelId());
        return TrainingRules.canFreeTrain(dog.active(), dog.freeTrainingOverride(), levelsEnabled, level != null && level.grantsFreeTraining());
    }

    public List<EligibleDog> eligibleDogs(String memberId) {
        var levels = catalogs.trainingLevels(); boolean levelsEnabled = context.flag("levels.enabled"); var locale = LocaleContext.current();
        var result = new ArrayList<EligibleDog>();
        for (var entry : census.reachableDogs(memberId)) {
            var dog = entry.getKey();
            if (!canFreeTrain(dog, levels, levelsEnabled)) { continue; }
            var level = dog.levelId() == null ? null : levels.get(dog.levelId());
            String levelName = !levelsEnabled || level == null || level.name() == null ? null : level.name().resolve(locale).value();
            result.add(new EligibleDog(dog, entry.getValue(), dog.memberId().equals(memberId), TrainingRules.rightSource(dog.freeTrainingOverride()), levelName));
        }
        result.sort(Comparator.comparing((EligibleDog e) -> !e.own()).thenComparing(e -> Objects.toString(e.dog().name(), ""), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.dog().id()));
        return result;
    }
    public Optional<EligibleDog> eligible(String memberId, String dogId) { return eligibleDogs(memberId).stream().filter(e -> e.dog().id().equals(dogId)).findFirst(); }

    /** S16's N-31 audience (E9): ACTIVE members with at least one eligible dog, own or through their family group. */
    public Set<String> membersWithRight() {
        var levels = catalogs.trainingLevels(); boolean levelsEnabled = context.flag("levels.enabled");
        var active = new HashSet<>(census.activeMembers()); var result = new TreeSet<String>();
        var owners = new HashSet<String>();
        for (var dog : census.activeDogs()) { if (active.contains(dog.memberId()) && canFreeTrain(dog, levels, levelsEnabled)) { owners.add(dog.memberId()); } }
        for (String owner : owners) {
            result.add(owner);
            census.reachableMembers(owner).stream().filter(active::contains).forEach(result::add);
        }
        return result;
    }
}
