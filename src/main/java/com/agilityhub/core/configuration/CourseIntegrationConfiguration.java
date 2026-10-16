package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.common.application.ports.LapsedRingSetupsPort;
import com.agilityhub.core.clubs.common.application.ports.OutlivedBuildSessionsPort;
import com.agilityhub.core.clubs.scheduling.application.ports.CoursePlacementPort;
import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.courses.application.BuildSessionService;
import com.agilityhub.core.courses.application.RingSetupService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E9-T03 (S16 R-16-07/08/09/13): the club contexts' ports onto the ring setups and placements of `courses`, at the composition root
 * (no `clubs.*` class imports `courses`, `ArchitectureTest.E9_T01_clubsReachesCoursesOnlyThroughPorts`). S09's `RingSetupPort`
 * (R-09-15, `rings[].setup` of `GET /training-slots`) replaces `TrainingAutoConfiguration`'s null object, S06's
 * `CoursePlacementPort` the scheduling default, and S15's `LapsedRingSetupsPort` feeds P5 step f. E9-T04: S15's
 * `OutlivedBuildSessionsPort` lets P9 abandon the build sessions open for `courses.buildSessionMaxHours` (R-16-11).
 */
@Configuration(proxyBeanMethods = false)
public class CourseIntegrationConfiguration {
    @Bean RingSetupPort ringSetupPort(RingSetupService setups) {
        return new RingSetupPort() {
            @Override public Optional<Setup> active(String setupId, Locale locale) {
                return setups.active(setupId, locale).map(s -> new Setup(s.id(), s.kind().name(), s.levelNames(), s.builtAt(), s.expectedUntil()));
            }
        };
    }
    @Bean CoursePlacementPort coursePlacementPort(RingSetupService setups) {
        return new CoursePlacementPort() {
            @Override public Optional<String> ringOf(String placementId) { return setups.placementRing(placementId); }
        };
    }
    @Bean LapsedRingSetupsPort lapsedRingSetupsPort(RingSetupService setups) {
        return new LapsedRingSetupsPort() {
            @Override public List<Lapsed> lapsed(Instant now) {
                return setups.lapsed(now).stream().map(setup -> new Lapsed(setup.id(), setup.ringId(), setup.expiresAt())).toList();
            }
            @Override public boolean expire(String setupId, Instant now) { return setups.expire(setupId, now); }
        };
    }
    @Bean OutlivedBuildSessionsPort outlivedBuildSessionsPort(BuildSessionService sessions) {
        return new OutlivedBuildSessionsPort() {
            @Override public List<String> outlived(Instant now) { return sessions.outlived(now).stream().map(session -> session.id()).toList(); }
            @Override public boolean abandon(String sessionId, Instant now) { return sessions.abandon(sessionId, now); }
        };
    }
}
