package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT session-10 survivors of {@link PaymentOperationRepository} (S12 R-12-20, E8-T11; T-12-17): recording a late
 * capture's intervention amount reports whether it changed the command, so a repeated amount does not notify again.
 */
class PaymentOperationRepositorySurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final PaymentOperationRepository operations = new PaymentOperationRepository(mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_12_17_anInterventionAmountIsRecordedOnlyWhenItChanges() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PaymentOperation.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null), UpdateResult.acknowledged(1, 1L, null));

        assertThat(operations.interventionOwed("operation-1", new Money(3000, "EUR"))).isFalse();
        assertThat(operations.interventionOwed("operation-1", new Money(2000, "EUR"))).isTrue();
    }
}
