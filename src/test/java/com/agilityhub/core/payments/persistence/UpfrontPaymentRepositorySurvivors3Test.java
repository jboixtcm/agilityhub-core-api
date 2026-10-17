package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.time.Instant;
import java.util.Date;
import org.bson.Document;
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
 * E11-T06 PIT session-10 survivors of {@link UpfrontPaymentRepository} (S12 R-12-20, E8-T11; T-12-17): a late capture warns
 * the admin once, and only when the row's compensation has not already stopped with its own warning.
 */
class UpfrontPaymentRepositorySurvivors3Test {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final UpfrontPaymentRepository payments = new UpfrontPaymentRepository(mongo);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_12_17_aLateCaptureWarnsOnceAndOnlyWithoutAnEarlierInterventionWarning() {
        when(mongo.findAndModify(any(Query.class), any(UpdateDefinition.class), eq(Document.class), eq("upfront_payments")))
                .thenReturn(null, new Document("compensationInterventionAt", Date.from(NOW.minusSeconds(60))), new Document("_id", "payment-1"));

        // Already warned for a late capture: the update matched nothing.
        assertThat(payments.lateInterventionWarning("payment-1", NOW)).isFalse();
        // The row's compensation already stopped and warned.
        assertThat(payments.lateInterventionWarning("payment-1", NOW)).isFalse();
        // The first warning of the row.
        assertThat(payments.lateInterventionWarning("payment-1", NOW)).isTrue();
    }
}
