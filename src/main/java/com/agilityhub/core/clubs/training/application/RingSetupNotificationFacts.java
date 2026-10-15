package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFacts;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationFactsPort;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationTrigger;
import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.platform.application.ClubConfigService;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * S16 §8 N-31 «Recorregut nou a la pista» explained to the S11 engine (E9-T03; S09 owns its audience): `RingSetupChanged{ACTIVE}` of
 * a setup that is not `EMPTY` (T-16-13; the engine checks `messaging.notifyNewRingSetup` and the status, and `COURSES` guards the
 * code) goes to every member with a training right ({@link TrainingEligibilityService#membersWithRight()}), in the app. Variables:
 * `ring_name`, `setup_kind` (the catalog value, `AGILITY` …) and `level` (the setup's club level names in the club's default
 * language, joined by an en dash, through S16's {@link RingSetupPort}). The action's `ringId` is the engine's (E81).
 */
@Service
public class RingSetupNotificationFacts implements NotificationFactsPort {
    private static final Set<String> TYPES = Set.of("RingSetupChanged");
    private final TrainingEligibilityService eligibility; private final PlanningCatalogAccess catalogs; private final RingSetupPort setups;
    private final ClubConfigService configs;
    public RingSetupNotificationFacts(TrainingEligibilityService eligibility, PlanningCatalogAccess catalogs, RingSetupPort setups, ClubConfigService configs) {
        this.eligibility = eligibility; this.catalogs = catalogs; this.setups = setups; this.configs = configs;
    }

    @Override public Set<String> eventTypes() { return TYPES; }

    @Override public Optional<NotificationFacts> facts(NotificationTrigger trigger, String code) {
        if (!"N-31".equals(code) || !"ACTIVE".equals(trigger.text("status")) || "EMPTY".equals(trigger.text("kind"))) { return Optional.empty(); }
        String ringId = trigger.text("ringId");
        var locale = Locale.forLanguageTag(configs.get(trigger.clubId()).club().defaultLocale());
        String level = setups.active(trigger.text("setupId"), locale).map(setup -> String.join("–", setup.levelNames())).orElse("");
        String ringName = catalogs.rings().stream().filter(ring -> ring.id().equals(ringId)).map(PlanningCatalogAccess.RingView::name).findFirst().orElse("");
        var builder = NotificationFacts.builder().noMembers().value("ring_name", ringName).value("setup_kind", trigger.text("kind")).value("level", level);
        eligibility.membersWithRight().forEach(memberId -> builder.member(memberId, null));
        return Optional.of(builder.build());
    }
}
