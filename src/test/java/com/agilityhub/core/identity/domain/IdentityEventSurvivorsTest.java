package com.agilityhub.core.identity.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link IdentityEvent#aggregateType} (CATALEG_ESDEVENIMENTS): `MembershipChanged` belongs to the
 * `Membership` aggregate and every other identity event to the `Account`.
 */
class IdentityEventSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    @Test void E11_T06_aMembershipChangeIsOfTheMembershipAggregate() {
        // MembershipService.java:66-67's payload.
        var event = new IdentityEvent(IdentityEvent.Kind.MembershipChanged, "club-a", "ms-1", NOW, Map.of("accountId", "acc-1", "clubId", "club-a",
                "before", Set.of(), "after", Set.of(Role.MEMBER), "status", "ACTIVE"));

        assertThat(event.aggregateType()).isEqualTo("Membership");
    }

    @Test void E11_T06_anAccountEventIsOfTheAccountAggregate() {
        // AccountService.java:43-44's payload: the email only as its hash, and no club.
        var event = new IdentityEvent(IdentityEvent.Kind.AccountCreated, null, "acc-1", NOW,
                Map.of("accountId", "acc-1", "emailHash", "email-hash-example", "source", "CONSOLE"));

        assertThat(event.aggregateType()).isEqualTo("Account");
    }
}
