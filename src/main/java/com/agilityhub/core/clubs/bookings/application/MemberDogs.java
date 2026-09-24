package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.LocaleContext;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * The dog chips of 03 and 04 (S08 R-08-23): the member's ACTIVE dogs and, with FAMILY_GROUP, the ACTIVE dogs of the
 * group («Toby · B (Joan Antoni)»), own dogs first, then by name. `levelName` is the level's name in the request locale.
 */
@Service
public class MemberDogs {
    public record Chip(BookingMemberAccess.Dog dog, String levelName, boolean own, String ownerFirstName) {
        public String id() { return dog.id(); }
        public Map<String, Object> home() {
            return BookingViews.map("id", dog.id(), "name", dog.name(), "levelName", levelName, "own", own, "ownerFirstName", ownerFirstName);
        }
    }
    private final BookingMemberAccess census; private final PlanningCatalogAccess catalogs;
    public MemberDogs(BookingMemberAccess census, PlanningCatalogAccess catalogs) { this.census = census; this.catalogs = catalogs; }

    public List<Chip> chips(String memberId) {
        var levels = catalogs.trainingLevels(); var locale = LocaleContext.current(); var owners = new HashMap<String, String>();
        return census.accessibleDogs(memberId).stream().filter(BookingMemberAccess.Dog::active).map(dog -> {
            boolean own = dog.memberId().equals(memberId);
            var level = dog.levelId() == null ? null : levels.get(dog.levelId());
            String owner = own ? null : owners.computeIfAbsent(dog.memberId(), id -> census.member(id).map(BookingMemberAccess.Member::firstName).orElse(null));
            return new Chip(dog, level == null ? null : level.name().resolve(locale).value(), own, owner);
        }).sorted(Comparator.comparing((Chip c) -> !c.own()).thenComparing(c -> Objects.toString(c.dog().name(), "")).thenComparing(Chip::id)).toList();
    }
}
