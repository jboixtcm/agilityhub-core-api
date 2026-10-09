package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link ImpersonationEvent} (S01 R-01-09, T-01-11; CATALEG_ESDEVENIMENTS `ImpersonationStarted`
 * / `ImpersonationEnded` {actorAccountId, memberId}): the grant is the aggregate and the back office the origin.
 */
class ImpersonationEventSurvivorsTest {
    @Test void T_01_11_anImpersonationEventCarriesTheCatalogShape() {
        var event = new ImpersonationEvent(ImpersonationEvent.Kind.ImpersonationEnded, "club-a", "grant-1",
                Instant.parse("2026-10-05T08:00:00Z"), "acc-admin", "member-1");

        assertThat(event.type()).isEqualTo("ImpersonationEnded");
        assertThat(event.aggregateType()).isEqualTo("ImpersonationGrant");
        assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        assertThat(event.payload()).isEqualTo(Map.of("actorAccountId", "acc-admin", "memberId", "member-1"));
    }
}
