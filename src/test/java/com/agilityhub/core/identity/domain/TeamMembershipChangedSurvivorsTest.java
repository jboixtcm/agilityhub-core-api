package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link TeamMembershipChanged} (CATALEG_ESDEVENIMENTS `MembershipChanged`, emitted from the team
 * screen by TeamMembershipService:82): a `MembershipChanged` on the `Membership` aggregate, never impersonated.
 */
class TeamMembershipChangedSurvivorsTest {
    @Test void E11_T06_aTeamMembershipChangeIsAMembershipChangedOfTheMembership() {
        var event = new TeamMembershipChanged("club-a", "ms-1", Instant.parse("2026-10-05T08:00:00Z"),
                // TeamMembershipService.java:82-84's payload: the roles as sorted sets.
                Map.of("accountId", "acc-1", "clubId", "club-a", "before", new TreeSet<>(Set.of(Role.MEMBER)),
                        "after", new TreeSet<>(Set.of(Role.MEMBER, Role.INSTRUCTOR))),
                "acc-admin", DomainEvent.Origin.BACKOFFICE);

        assertThat(event.type()).isEqualTo("MembershipChanged");
        assertThat(event.aggregateType()).isEqualTo("Membership");
        assertThat(event.impersonatedMemberId()).isNull();
    }
}
