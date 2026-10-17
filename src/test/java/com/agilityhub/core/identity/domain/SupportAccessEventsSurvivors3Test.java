package com.agilityhub.core.identity.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors (session 11) of {@link SupportAccessStarted} and {@link SupportAccessEnded} (S17 R-17-10, T-17-09;
 * CATALEG_ESDEVENIMENTS: produced by the console): both events go through the outbox with the console's origin, the grant as
 * their aggregate and `{clubId, actorAccountId}` as payload. Fictional data.
 */
class SupportAccessEventsSurvivors3Test {
    static final Instant NOW = Instant.parse("2026-10-17T08:00:00Z");

    @Test void T_17_09_R_17_10_aStartedSupportAccessIsAConsoleEventOfItsGrant() {
        DomainEvent started = new SupportAccessStarted("club-a", "grant-1", NOW, "platform-1");

        assertThat(started.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        assertThat(started.type()).isEqualTo("SupportAccessStarted");
        assertThat(started.aggregateType()).isEqualTo("ImpersonationGrant");
        assertThat(started.impersonatedMemberId()).isNull();
        assertThat(started.payload()).isEqualTo(Map.of("clubId", "club-a", "actorAccountId", "platform-1"));
    }

    @Test void T_17_09_R_17_10_anEndedSupportAccessIsAConsoleEventOfItsGrant() {
        DomainEvent ended = new SupportAccessEnded("club-a", "grant-1", NOW, "platform-1");

        assertThat(ended.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
        assertThat(ended.type()).isEqualTo("SupportAccessEnded");
        assertThat(ended.aggregateType()).isEqualTo("ImpersonationGrant");
        assertThat(ended.impersonatedMemberId()).isNull();
        assertThat(ended.payload()).isEqualTo(Map.of("clubId", "club-a", "actorAccountId", "platform-1"));
    }
}
